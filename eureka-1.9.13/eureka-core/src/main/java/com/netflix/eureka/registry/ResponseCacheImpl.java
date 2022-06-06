/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.registry;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.netflix.appinfo.EurekaAccept;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.converters.wrappers.EncoderWrapper;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.Version;
import com.netflix.eureka.resources.CurrentRequestVersion;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Stopwatch;
import com.netflix.servo.monitor.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class that is responsible for caching registry information that will be
 * queried by the clients.
 *
 * <p>
 * The cache is maintained in compressed and non-compressed form for three
 * categories of requests - all applications, delta changes and for individual
 * applications. The compressed form is probably the most efficient in terms of
 * network traffic especially when querying all applications.
 *
 * The cache also maintains separate pay load for <em>JSON</em> and <em>XML</em>
 * formats and for multiple versions too.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim
 */
public class ResponseCacheImpl implements ResponseCache {

    private static final Logger logger = LoggerFactory.getLogger(ResponseCacheImpl.class);

    public static final String ALL_APPS = "ALL_APPS";
    public static final String ALL_APPS_DELTA = "ALL_APPS_DELTA";

    // FIXME deprecated, here for backwards compatibility.
    private static final AtomicLong versionDeltaLegacy = new AtomicLong(0);
    private static final AtomicLong versionDeltaWithRegionsLegacy = new AtomicLong(0);

    private static final String EMPTY_PAYLOAD = "";
    private final java.util.Timer timer = new java.util.Timer("Eureka-CacheFillTimer", true);
    private final AtomicLong versionDelta = new AtomicLong(0);
    private final AtomicLong versionDeltaWithRegions = new AtomicLong(0);

    private final Timer serializeAllAppsTimer = Monitors.newTimer("serialize-all");
    private final Timer serializeDeltaAppsTimer = Monitors.newTimer("serialize-all-delta");
    private final Timer serializeAllAppsWithRemoteRegionTimer = Monitors.newTimer("serialize-all_remote_region");
    private final Timer serializeDeltaAppsWithRemoteRegionTimer = Monitors.newTimer("serialize-all-delta_remote_region");
    private final Timer serializeOneApptimer = Monitors.newTimer("serialize-one");
    private final Timer serializeViptimer = Monitors.newTimer("serialize-one-vip");
    private final Timer compressPayloadTimer = Monitors.newTimer("compress-payload");

    /**
     * This map holds mapping of keys without regions to a list of keys with region (provided by clients)
     * Since, during invalidation, triggered by a change in registry for local region, we do not know the regions
     * requested by clients, we use this mapping to get all the keys with regions to be invalidated.
     * If we do not do this, any cached user requests containing region keys will not be invalidated and will stick
     * around till expiry. Github issue: https://github.com/Netflix/eureka/issues/118
     */
    private final Multimap<Key, Key> regionSpecificKeys =
            Multimaps.newListMultimap(new ConcurrentHashMap<Key, Collection<Key>>(), new Supplier<List<Key>>() {
                @Override
                public List<Key> get() {
                    return new CopyOnWriteArrayList<Key>();
                }
            });
    // 多级缓存用来实现读写分析，避免大规模服务注册与更新时修改ConcurrentHashMap数据导致锁的竞争影响性能
    private final ConcurrentMap<Key, Value> readOnlyCacheMap = new ConcurrentHashMap<Key, Value>(); // 三级缓存，周期更新，默认每30秒从二级缓存同步服务注册信息（Eureka Client默认从三级缓存获取服务注册信息，可配为直接从二级缓存获取）

    private final LoadingCache<Key, Value> readWriteCacheMap; // 二级缓存，实时更新，缓存时间180秒
    private final boolean shouldUseReadOnlyResponseCache; // 是否开启三级缓存
    private final AbstractInstanceRegistry registry;
    private final EurekaServerConfig serverConfig;
    private final ServerCodecs serverCodecs;

    ResponseCacheImpl(EurekaServerConfig serverConfig, ServerCodecs serverCodecs, AbstractInstanceRegistry registry) { // 构造方法会启动一个定时任务将二级缓存同步到三级缓存
        this.serverConfig = serverConfig;
        this.serverCodecs = serverCodecs;
        this.shouldUseReadOnlyResponseCache = serverConfig.shouldUseReadOnlyResponseCache(); // 是否开启三级缓存，默认开启
        this.registry = registry;

        long responseCacheUpdateIntervalMs = serverConfig.getResponseCacheUpdateIntervalMs(); // 二级缓存往三级缓存同步的间隔时间，默认30秒
        this.readWriteCacheMap = // 初始化二级缓存
                CacheBuilder.newBuilder().initialCapacity(serverConfig.getInitialCapacityOfResponseCache()) // 设置二级缓存初始容量，默认1000
                        .expireAfterWrite(serverConfig.getResponseCacheAutoExpirationInSeconds(), TimeUnit.SECONDS) // 设置二级缓存过期时间，默认180秒
                        .removalListener(new RemovalListener<Key, Value>() { // 失效监听
                            @Override
                            public void onRemoval(RemovalNotification<Key, Value> notification) {
                                Key removedKey = notification.getKey();
                                if (removedKey.hasRegions()) {
                                    Key cloneWithNoRegions = removedKey.cloneWithoutRegions();
                                    regionSpecificKeys.remove(cloneWithNoRegions, removedKey);
                                }
                            }
                        })
                        .build(new CacheLoader<Key, Value>() { // 从一级缓存registry中获取数据，构建key-value缓存
                            @Override
                            public Value load(Key key) throws Exception {
                                if (key.hasRegions()) {
                                    Key cloneWithNoRegions = key.cloneWithoutRegions();
                                    regionSpecificKeys.put(cloneWithNoRegions, key);
                                }
                                Value value = generatePayload(key); // 从一级缓存registry中获取数据并封装为Value
                                return value;
                            }
                        });

        if (shouldUseReadOnlyResponseCache) { // 当开启三级缓存时创建定时任务，创建定时任务将二级缓存同步到三级缓存中
            timer.schedule(getCacheUpdateTask(),
                    new Date(((System.currentTimeMillis() / responseCacheUpdateIntervalMs) * responseCacheUpdateIntervalMs)
                            + responseCacheUpdateIntervalMs),
                    responseCacheUpdateIntervalMs); // 创建定时任务，每隔30秒将二级缓存的数据同步到三级缓存
        }

        try {
            Monitors.registerObject(this);
        } catch (Throwable e) {
            logger.warn("Cannot register the JMX monitor for the InstanceRegistry", e);
        }
    }

    private TimerTask getCacheUpdateTask() {
        return new TimerTask() {
            @Override
            public void run() {
                logger.debug("Updating the client cache from response cache");
                for (Key key : readOnlyCacheMap.keySet()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Updating the client cache from response cache for key : {} {} {} {}",
                                key.getEntityType(), key.getName(), key.getVersion(), key.getType());
                    }
                    try {
                        CurrentRequestVersion.set(key.getVersion());
                        Value cacheValue = readWriteCacheMap.get(key);
                        Value currentCacheValue = readOnlyCacheMap.get(key);
                        if (cacheValue != currentCacheValue) {
                            readOnlyCacheMap.put(key, cacheValue);
                        }
                    } catch (Throwable th) {
                        logger.error("Error while updating the client cache from response cache for key {}", key.toStringCompact(), th);
                    }
                }
            }
        };
    }

    /**
     * Get the cached information about applications.
     *
     * <p>
     * If the cached information is not available it is generated on the first
     * request. After the first request, the information is then updated
     * periodically by a background thread.
     * </p>
     *
     * @param key the key for which the cached information needs to be obtained.
     * @return payload which contains information about the applications.
     */
    public String get(final Key key) { // 获取缓存
        return get(key, shouldUseReadOnlyResponseCache);
    }

    @VisibleForTesting
    String get(final Key key, boolean useReadOnlyCache) { // 获取缓存
        Value payload = getValue(key, useReadOnlyCache); // 获取缓存逻辑
        if (payload == null || payload.getPayload().equals(EMPTY_PAYLOAD)) {
            return null;
        } else {
            return payload.getPayload();
        }
    }

    /**
     * Get the compressed information about the applications.
     *
     * @param key
     *            the key for which the compressed cached information needs to
     *            be obtained.
     * @return compressed payload which contains information about the
     *         applications.
     */
    public byte[] getGZIP(Key key) {
        Value payload = getValue(key, shouldUseReadOnlyResponseCache);
        if (payload == null) {
            return null;
        }
        return payload.getGzipped();
    }

    /**
     * Invalidate the cache of a particular application.
     *
     * @param appName the application name of the application.
     */
    @Override
    public void invalidate(String appName, @Nullable String vipAddress, @Nullable String secureVipAddress) {
        for (Key.KeyType type : Key.KeyType.values()) {
            for (Version v : Version.values()) {
                invalidate( // 让缓存失效
                        new Key(Key.EntityType.Application, appName, type, v, EurekaAccept.full),
                        new Key(Key.EntityType.Application, appName, type, v, EurekaAccept.compact),
                        new Key(Key.EntityType.Application, ALL_APPS, type, v, EurekaAccept.full),
                        new Key(Key.EntityType.Application, ALL_APPS, type, v, EurekaAccept.compact),
                        new Key(Key.EntityType.Application, ALL_APPS_DELTA, type, v, EurekaAccept.full),
                        new Key(Key.EntityType.Application, ALL_APPS_DELTA, type, v, EurekaAccept.compact)
                );
                if (null != vipAddress) {
                    invalidate(new Key(Key.EntityType.VIP, vipAddress, type, v, EurekaAccept.full));
                }
                if (null != secureVipAddress) {
                    invalidate(new Key(Key.EntityType.SVIP, secureVipAddress, type, v, EurekaAccept.full));
                }
            }
        }
    }

    /**
     * Invalidate the cache information given the list of keys.
     *
     * @param keys the list of keys for which the cache information needs to be invalidated.
     */
    public void invalidate(Key... keys) {
        for (Key key : keys) {
            logger.debug("Invalidating the response cache key : {} {} {} {}, {}",
                    key.getEntityType(), key.getName(), key.getVersion(), key.getType(), key.getEurekaAccept());

            readWriteCacheMap.invalidate(key);
            Collection<Key> keysWithRegions = regionSpecificKeys.get(key);
            if (null != keysWithRegions && !keysWithRegions.isEmpty()) {
                for (Key keysWithRegion : keysWithRegions) {
                    logger.debug("Invalidating the response cache key : {} {} {} {} {}",
                            key.getEntityType(), key.getName(), key.getVersion(), key.getType(), key.getEurekaAccept());
                    readWriteCacheMap.invalidate(keysWithRegion);
                }
            }
        }
    }

    /**
     * Gets the version number of the cached data.
     *
     * @return teh version number of the cached data.
     */
    @Override
    public AtomicLong getVersionDelta() {
        return versionDelta;
    }

    /**
     * Gets the version number of the cached data with remote regions.
     *
     * @return teh version number of the cached data with remote regions.
     */
    @Override
    public AtomicLong getVersionDeltaWithRegions() {
        return versionDeltaWithRegions;
    }

    /**
     * @deprecated use instance method {@link #getVersionDelta()}
     *
     * Gets the version number of the cached data.
     *
     * @return teh version number of the cached data.
     */
    @Deprecated
    public static AtomicLong getVersionDeltaStatic() {
        return versionDeltaLegacy;
    }

    /**
     * @deprecated use instance method {@link #getVersionDeltaWithRegions()}
     *
     * Gets the version number of the cached data with remote regions.
     *
     * @return teh version number of the cached data with remote regions.
     */
    @Deprecated
    public static AtomicLong getVersionDeltaWithRegionsLegacy() {
        return versionDeltaWithRegionsLegacy;
    }

    /**
     * Get the number of items in the response cache.
     *
     * @return int value representing the number of items in response cache.
     */
    @Monitor(name = "responseCacheSize", type = DataSourceType.GAUGE)
    public int getCurrentSize() {
        return readWriteCacheMap.asMap().size();
    }

    /**
     * Get the payload in both compressed and uncompressed form.
     */
    @VisibleForTesting
    Value getValue(final Key key, boolean useReadOnlyCache) {
        Value payload = null;
        try {
            if (useReadOnlyCache) { // 开启三级缓存时，先从三级缓存中获取、三级缓存获取不到时从二级缓存中获取并把结果同步到三级缓存（二级缓存获取不到时会触发guava的回调函数从一级缓存registry中获取数据）
                final Value currentPayload = readOnlyCacheMap.get(key);
                if (currentPayload != null) {
                    payload = currentPayload;
                } else {
                    payload = readWriteCacheMap.get(key); // 二级缓存获取不到时会触发guava的load回调函数从一级缓存registry中获取数据
                    readOnlyCacheMap.put(key, payload);
                }
            } else { // 未开启三级缓存时，直接从二级缓存中获取
                payload = readWriteCacheMap.get(key);
            }
        } catch (Throwable t) {
            logger.error("Cannot get value for key : {}", key, t);
        }
        return payload;
    }

    /**
     * Generate pay load with both JSON and XML formats for all applications.
     */
    private String getPayLoad(Key key, Applications apps) {
        EncoderWrapper encoderWrapper = serverCodecs.getEncoder(key.getType(), key.getEurekaAccept());
        String result;
        try {
            result = encoderWrapper.encode(apps);
        } catch (Exception e) {
            logger.error("Failed to encode the payload for all apps", e);
            return "";
        }
        if(logger.isDebugEnabled()) {
            logger.debug("New application cache entry {} with apps hashcode {}", key.toStringCompact(), apps.getAppsHashCode());
        }
        return result;
    }

    /**
     * Generate pay load with both JSON and XML formats for a given application.
     */
    private String getPayLoad(Key key, Application app) {
        if (app == null) {
            return EMPTY_PAYLOAD;
        }

        EncoderWrapper encoderWrapper = serverCodecs.getEncoder(key.getType(), key.getEurekaAccept());
        try {
            return encoderWrapper.encode(app);
        } catch (Exception e) {
            logger.error("Failed to encode the payload for application {}", app.getName(), e);
            return "";
        }
    }

    /*
     * Generate pay load for the given key.
     */
    private Value generatePayload(Key key) { // 根据key生成value，主要从一级缓存registry中获取数据并封装为Value
        Stopwatch tracer = null;
        try {
            String payload;
            switch (key.getEntityType()) {
                case Application:
                    boolean isRemoteRegionRequested = key.hasRegions();

                    if (ALL_APPS.equals(key.getName())) { // 全量缓存key
                        if (isRemoteRegionRequested) {
                            tracer = serializeAllAppsWithRemoteRegionTimer.start();
                            payload = getPayLoad(key, registry.getApplicationsFromMultipleRegions(key.getRegions()));
                        } else {
                            tracer = serializeAllAppsTimer.start();
                            payload = getPayLoad(key, registry.getApplications()); // 先从一级缓存中获取全量信息，再从全量信息中根据key获取数据
                        }
                    } else if (ALL_APPS_DELTA.equals(key.getName())) { // 增量缓存key
                        if (isRemoteRegionRequested) {
                            tracer = serializeDeltaAppsWithRemoteRegionTimer.start();
                            versionDeltaWithRegions.incrementAndGet();
                            versionDeltaWithRegionsLegacy.incrementAndGet();
                            payload = getPayLoad(key,
                                    registry.getApplicationDeltasFromMultipleRegions(key.getRegions()));
                        } else {
                            tracer = serializeDeltaAppsTimer.start();
                            versionDelta.incrementAndGet(); // 增量版本号递增
                            versionDeltaLegacy.incrementAndGet();
                            payload = getPayLoad(key, registry.getApplicationDeltas()); // 先获取增量信息，再从增量信息中根据key获取数据
                        }
                    } else {
                        tracer = serializeOneApptimer.start();
                        payload = getPayLoad(key, registry.getApplication(key.getName()));
                    }
                    break;
                case VIP:
                case SVIP:
                    tracer = serializeViptimer.start();
                    payload = getPayLoad(key, getApplicationsForVip(key, registry));
                    break;
                default:
                    logger.error("Unidentified entity type: {} found in the cache key.", key.getEntityType());
                    payload = "";
                    break;
            }
            return new Value(payload);
        } finally {
            if (tracer != null) {
                tracer.stop();
            }
        }
    }

    private static Applications getApplicationsForVip(Key key, AbstractInstanceRegistry registry) {
        logger.debug(
                "Retrieving applications from registry for key : {} {} {} {}",
                key.getEntityType(), key.getName(), key.getVersion(), key.getType());
        Applications toReturn = new Applications();
        Applications applications = registry.getApplications();
        for (Application application : applications.getRegisteredApplications()) {
            Application appToAdd = null;
            for (InstanceInfo instanceInfo : application.getInstances()) {
                String vipAddress;
                if (Key.EntityType.VIP.equals(key.getEntityType())) {
                    vipAddress = instanceInfo.getVIPAddress();
                } else if (Key.EntityType.SVIP.equals(key.getEntityType())) {
                    vipAddress = instanceInfo.getSecureVipAddress();
                } else {
                    // should not happen, but just in case.
                    continue;
                }

                if (null != vipAddress) {
                    String[] vipAddresses = vipAddress.split(",");
                    Arrays.sort(vipAddresses);
                    if (Arrays.binarySearch(vipAddresses, key.getName()) >= 0) {
                        if (null == appToAdd) {
                            appToAdd = new Application(application.getName());
                            toReturn.addApplication(appToAdd);
                        }
                        appToAdd.addInstance(instanceInfo);
                    }
                }
            }
        }
        toReturn.setAppsHashCode(toReturn.getReconcileHashCode());
        logger.debug(
                "Retrieved applications from registry for key : {} {} {} {}, reconcile hashcode: {}",
                key.getEntityType(), key.getName(), key.getVersion(), key.getType(),
                toReturn.getReconcileHashCode());
        return toReturn;
    }

    /**
     * The class that stores payload in both compressed and uncompressed form.
     *
     */
    public class Value { // 缓存值
        private final String payload;
        private byte[] gzipped;

        public Value(String payload) {
            this.payload = payload;
            if (!EMPTY_PAYLOAD.equals(payload)) {
                Stopwatch tracer = compressPayloadTimer.start();
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    GZIPOutputStream out = new GZIPOutputStream(bos);
                    byte[] rawBytes = payload.getBytes();
                    out.write(rawBytes);
                    // Finish creation of gzip file
                    out.finish();
                    out.close();
                    bos.close();
                    gzipped = bos.toByteArray();
                } catch (IOException e) {
                    gzipped = null;
                } finally {
                    if (tracer != null) {
                        tracer.stop();
                    }
                }
            } else {
                gzipped = null;
            }
        }

        public String getPayload() {
            return payload;
        }

        public byte[] getGzipped() {
            return gzipped;
        }

    }

}

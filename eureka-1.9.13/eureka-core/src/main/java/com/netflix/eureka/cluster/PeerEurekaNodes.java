package com.netflix.eureka.cluster;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.endpoint.EndpointUtils;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.transport.JerseyReplicationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class to manage lifecycle of a collection of {@link PeerEurekaNode}s.
 *
 * @author Tomasz Bak
 */
@Singleton
public class PeerEurekaNodes { // 所有集群节点信息

    private static final Logger logger = LoggerFactory.getLogger(PeerEurekaNodes.class);

    protected final PeerAwareInstanceRegistry registry; // PeerAwareInstanceRegistry
    protected final EurekaServerConfig serverConfig; // Eureka Server配置
    protected final EurekaClientConfig clientConfig; // Eureka Client配置
    protected final ServerCodecs serverCodecs; // Eureka-Server编解码
    private final ApplicationInfoManager applicationInfoManager; // 应用实例信息管理器

    private volatile List<PeerEurekaNode> peerEurekaNodes = Collections.emptyList(); // Eureka-Server集群节点列表，通过updatePeerEurekaNodes方法进行更新
    private volatile Set<String> peerEurekaNodeUrls = Collections.emptySet(); // Eureka-Server服务地址集合，通过updatePeerEurekaNodes方法进行更新

    private ScheduledExecutorService taskExecutor; // 定时任务服务

    @Inject
    public PeerEurekaNodes(
            PeerAwareInstanceRegistry registry,
            EurekaServerConfig serverConfig,
            EurekaClientConfig clientConfig,
            ServerCodecs serverCodecs,
            ApplicationInfoManager applicationInfoManager) {
        this.registry = registry;
        this.serverConfig = serverConfig;
        this.clientConfig = clientConfig;
        this.serverCodecs = serverCodecs;
        this.applicationInfoManager = applicationInfoManager;
    }

    public List<PeerEurekaNode> getPeerNodesView() {
        return Collections.unmodifiableList(peerEurekaNodes);
    }

    public List<PeerEurekaNode> getPeerEurekaNodes() {
        return peerEurekaNodes;
    }
    
    public int getMinNumberOfAvailablePeers() {
        return serverConfig.getHealthStatusMinNumberOfAvailablePeers();
    }

    public void start() { // 初始化集群节点列表，并开启默认每隔10分钟更新集群集群节点列表信息的定时任务
        taskExecutor = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, "Eureka-PeerNodesUpdater");
                        thread.setDaemon(true);
                        return thread;
                    }
                }
        );
        try {
            updatePeerEurekaNodes(resolvePeerUrls()); // 先获取服务地址列表，首次初始化集群节点列表
            Runnable peersUpdateTask = new Runnable() { // 创建线程任务
                @Override
                public void run() {
                    try {
                        updatePeerEurekaNodes(resolvePeerUrls()); // 重新获取服务地址列表，更新集群节点列表信息
                    } catch (Throwable e) {
                        logger.error("Cannot update the replica Nodes", e);
                    }

                }
            };
            taskExecutor.scheduleWithFixedDelay(
                    peersUpdateTask, // 默认每隔10分钟更新集群集群节点列表信息
                    serverConfig.getPeerEurekaNodesUpdateIntervalMs(), // 默认10分钟
                    serverConfig.getPeerEurekaNodesUpdateIntervalMs(), // 默认10分钟
                    TimeUnit.MILLISECONDS
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (PeerEurekaNode node : peerEurekaNodes) { // 打印集群节点信息
            logger.info("Replica node URL:  {}", node.getServiceUrl());
        }
    }

    public void shutdown() {
        taskExecutor.shutdown();
        List<PeerEurekaNode> toRemove = this.peerEurekaNodes;

        this.peerEurekaNodes = Collections.emptyList();
        this.peerEurekaNodeUrls = Collections.emptySet();

        for (PeerEurekaNode node : toRemove) {
            node.shutDown();
        }
    }

    /**
     * Resolve peer URLs.
     *
     * @return peer URLs with node's own URL filtered out
     */
    protected List<String> resolvePeerUrls() { // 获取Eureka Server集群服务地址列表
        InstanceInfo myInfo = applicationInfoManager.getInfo();
        String zone = InstanceInfo.getZone(clientConfig.getAvailabilityZones(clientConfig.getRegion()), myInfo);
        List<String> replicaUrls = EndpointUtils
                .getDiscoveryServiceUrls(clientConfig, zone, new EndpointUtils.InstanceInfoBasedUrlRandomizer(myInfo)); // 获取Eureka Server集群服务地址列表

        int idx = 0;
        while (idx < replicaUrls.size()) {
            if (isThisMyUrl(replicaUrls.get(idx))) {
                replicaUrls.remove(idx); // 在Eureka Server集群服务地址列表中移除自己，避免向自己同步
            } else {
                idx++;
            }
        }
        return replicaUrls;
    }

    /**
     * Given new set of replica URLs, destroy {@link PeerEurekaNode}s no longer available, and
     * create new ones.
     *
     * @param newPeerUrls peer node URLs; this collection should have local node's URL filtered out
     */
    protected void updatePeerEurekaNodes(List<String> newPeerUrls) { // 更新集群节点列表信息，添加新增集群节点、关闭删除集群节点
        if (newPeerUrls.isEmpty()) {
            logger.warn("The replica size seems to be empty. Check the route 53 DNS Registry");
            return;
        }

        Set<String> toShutdown = new HashSet<>(peerEurekaNodeUrls); // 计算关闭的集群节点地址，用于删除操作
        toShutdown.removeAll(newPeerUrls);
        Set<String> toAdd = new HashSet<>(newPeerUrls); // 计算新增的集群节点地址，用于添加操作
        toAdd.removeAll(peerEurekaNodeUrls);

        if (toShutdown.isEmpty() && toAdd.isEmpty()) { // No change // 集群节点地址列表无变化时，直接返回
            return;
        }

        // Remove peers no long available
        List<PeerEurekaNode> newNodeList = new ArrayList<>(peerEurekaNodes); // 拷贝原集群节点列表

        if (!toShutdown.isEmpty()) { // 有关闭集群节点地址时进行删除
            logger.info("Removing no longer available peer nodes {}", toShutdown);
            int i = 0;
            while (i < newNodeList.size()) {
                PeerEurekaNode eurekaNode = newNodeList.get(i);
                if (toShutdown.contains(eurekaNode.getServiceUrl())) {
                    newNodeList.remove(i); // 移除
                    eurekaNode.shutDown();
                } else {
                    i++;
                }
            }
        }

        // Add new peers
        if (!toAdd.isEmpty()) { // 有新增集群节点地址时进行添加
            logger.info("Adding new peer nodes {}", toAdd);
            for (String peerUrl : toAdd) {
                newNodeList.add(createPeerEurekaNode(peerUrl)); // 根据集群节点地址构建PeerEurekaNode信息并添加放到集群节点列表中
            }
        }

        this.peerEurekaNodes = newNodeList;
        this.peerEurekaNodeUrls = new HashSet<>(newPeerUrls);
    }

    protected PeerEurekaNode createPeerEurekaNode(String peerEurekaNodeUrl) { // 根据集群节点地址构建PeerEurekaNode信息
        HttpReplicationClient replicationClient = JerseyReplicationClient.createReplicationClient(serverConfig, serverCodecs, peerEurekaNodeUrl); // 创建Eureka Server集群通信客户端
        String targetHost = hostFromUrl(peerEurekaNodeUrl);
        if (targetHost == null) {
            targetHost = "host";
        }
        return new PeerEurekaNode(registry, targetHost, peerEurekaNodeUrl, replicationClient, serverConfig); // 创建PeerEurekaNode
    }

    /**
     * @deprecated 2016-06-27 use instance version of {@link #isThisMyUrl(String)}
     *
     * Checks if the given service url contains the current host which is trying
     * to replicate. Only after the EIP binding is done the host has a chance to
     * identify itself in the list of replica nodes and needs to take itself out
     * of replication traffic.
     *
     * @param url the service url of the replica node that the check is made.
     * @return true, if the url represents the current node which is trying to
     *         replicate, false otherwise.
     */
    public static boolean isThisMe(String url) {
        InstanceInfo myInfo = ApplicationInfoManager.getInstance().getInfo();
        String hostName = hostFromUrl(url);
        return hostName != null && hostName.equals(myInfo.getHostName());
    }

    /**
     * Checks if the given service url contains the current host which is trying
     * to replicate. Only after the EIP binding is done the host has a chance to
     * identify itself in the list of replica nodes and needs to take itself out
     * of replication traffic.
     *
     * @param url the service url of the replica node that the check is made.
     * @return true, if the url represents the current node which is trying to
     *         replicate, false otherwise.
     */
    public boolean isThisMyUrl(String url) {
        final String myUrlConfigured = serverConfig.getMyUrl();
        if (myUrlConfigured != null) {
            return myUrlConfigured.equals(url);
        }
        return isInstanceURL(url, applicationInfoManager.getInfo());
    }
    
    /**
     * Checks if the given service url matches the supplied instance
     *
     * @param url the service url of the replica node that the check is made.
     * @param instance the instance to check the service url against
     * @return true, if the url represents the supplied instance, false otherwise.
     */
    public boolean isInstanceURL(String url, InstanceInfo instance) {
        String hostName = hostFromUrl(url);
        String myInfoComparator = instance.getHostName();
        if (clientConfig.getTransportConfig().applicationsResolverUseIp()) {
            myInfoComparator = instance.getIPAddr();
        }
        return hostName != null && hostName.equals(myInfoComparator);
    }

    public static String hostFromUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            logger.warn("Cannot parse service URI {}", url, e);
            return null;
        }
        return uri.getHost();
    }
}

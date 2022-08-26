/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.netflix.ribbon;

import java.net.URI;

import javax.annotation.PostConstruct;

import com.netflix.client.DefaultLoadBalancerRetryHandler;
import com.netflix.client.RetryHandler;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.DefaultClientConfigImpl;
import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.ConfigurationBasedServerList;
import com.netflix.loadbalancer.DummyPing;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.IPing;
import com.netflix.loadbalancer.IRule;
import com.netflix.loadbalancer.PollingServerListUpdater;
import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.ServerList;
import com.netflix.loadbalancer.ServerListFilter;
import com.netflix.loadbalancer.ServerListUpdater;
import com.netflix.loadbalancer.ZoneAvoidanceRule;
import com.netflix.loadbalancer.ZoneAwareLoadBalancer;
import com.netflix.niws.client.http.RestClient;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.client.apache4.ApacheHttpClient4;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.params.CookiePolicy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.commons.httpclient.HttpClientConfiguration;
import org.springframework.cloud.netflix.ribbon.apache.HttpClientRibbonConfiguration;
import org.springframework.cloud.netflix.ribbon.okhttp.OkHttpRibbonConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static com.netflix.client.config.CommonClientConfigKey.DeploymentContextBasedVipAddresses;
import static org.springframework.cloud.netflix.ribbon.RibbonUtils.setRibbonProperty;
import static org.springframework.cloud.netflix.ribbon.RibbonUtils.updateToSecureConnectionIfNeeded;

/**
 * @author Dave Syer
 * @author Tim Ysewyn
 */
@SuppressWarnings("deprecation")
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties
// Order is important here, last should be the default, first should be optional
// see
// https://github.com/spring-cloud/spring-cloud-netflix/issues/2086#issuecomment-316281653
@Import({ HttpClientConfiguration.class, OkHttpRibbonConfiguration.class,
		RestClientRibbonConfiguration.class, HttpClientRibbonConfiguration.class })
public class RibbonClientConfiguration { // Ribbon的客户端默认配置类（在使用时才会加载，各服务提供者相互独立）

	/**
	 * Ribbon client default connect timeout.
	 */
	public static final int DEFAULT_CONNECT_TIMEOUT = 1000; // Ribbon客户端的默认连接超时时间

	/**
	 * Ribbon client default read timeout.
	 */
	public static final int DEFAULT_READ_TIMEOUT = 1000; // Ribbon客户端的默认读取超时时间

	/**
	 * Ribbon client default Gzip Payload flag.
	 */
	public static final boolean DEFAULT_GZIP_PAYLOAD = true;

	@RibbonClientName // 通过@Value("${ribbon.client.name}")从Environment环境中获取子容器服务名
	private String name = "client";

	// TODO: maybe re-instate autowired load balancers: identified by name they could be
	// associated with ribbon clients

	@Autowired
	private PropertiesFactory propertiesFactory; // Ribbon属性工厂，通过配置方式指定RibbonClientConfiguration的相关Bean

	@Bean
	@ConditionalOnMissingBean // Spring容器中不存在该Bean时
	public IClientConfig ribbonClientConfig() { // 创建客户端负载均衡器配置接口IClientConfig实现类对象
		DefaultClientConfigImpl config = new DefaultClientConfigImpl(); // 初始化DefaultClientConfigImpl（Ribbon客户端默认配置实现类）
		config.loadProperties(this.name); // 加载对应服务名的Ribbon客户端属性配置
		config.set(CommonClientConfigKey.ConnectTimeout, DEFAULT_CONNECT_TIMEOUT); // 重新设置连接超时时间（默认为1秒）
		config.set(CommonClientConfigKey.ReadTimeout, DEFAULT_READ_TIMEOUT); // 重新设置读取超时时间（默认为1秒）
		config.set(CommonClientConfigKey.GZipPayload, DEFAULT_GZIP_PAYLOAD);
		return config;
	}

	@Bean
	@ConditionalOnMissingBean
	public IRule ribbonRule(IClientConfig config) { // 创建负载均衡策略接口IRule实现类对象（选择一个最终的服务地址作为LoadBalancer结果，选择策略有简单轮询负载均衡(RoundRobin)、随机负载均衡(RoundRobinRule)、加权响应时间负载均衡(WeightedResponseTimeRule)、区域感知轮询负载均衡(ZoneAvoidanceRule)等）
		if (this.propertiesFactory.isSet(IRule.class, name)) {
			return this.propertiesFactory.get(IRule.class, config, name);
		}
		ZoneAvoidanceRule rule = new ZoneAvoidanceRule(); // 默认的负载均衡策略（先按照zone筛选，再进行轮询）
		rule.initWithNiwsConfig(config);
		return rule;
	}

	@Bean
	@ConditionalOnMissingBean
	public IPing ribbonPing(IClientConfig config) { // 创建负载均衡探活策略IPing实现类对象，判断服务实例是否存活
		if (this.propertiesFactory.isSet(IPing.class, name)) {
			return this.propertiesFactory.get(IPing.class, config, name);
		}
		return new DummyPing(); // 默认的ping为空操作，直接返回true（PingUrl会真正的去ping相应的服务）
	}

	@Bean
	@ConditionalOnMissingBean
	@SuppressWarnings("unchecked")
	public ServerList<Server> ribbonServerList(IClientConfig config) { // 服务列表（用于获取地址列表，可以是静态的(提供一组固定的地址)，也可以是动态的(从注册中心中定期查询地址列表)）
		if (this.propertiesFactory.isSet(ServerList.class, name)) {
			return this.propertiesFactory.get(ServerList.class, config, name);
		}
		ConfigurationBasedServerList serverList = new ConfigurationBasedServerList(); // 默认从配置文件中获取服务地址列表
		serverList.initWithNiwsConfig(config);
		return serverList;
	}

	@Bean
	@ConditionalOnMissingBean
	public ServerListUpdater ribbonServerListUpdater(IClientConfig config) { // 服务列表更新策略
		return new PollingServerListUpdater(config); // 默认创建PollingServerListUpdater对象，会启动一个ScheduledThreadPoolExecutor，周期性的执行IPing策略
	}

	@Bean
	@ConditionalOnMissingBean
	public ILoadBalancer ribbonLoadBalancer(IClientConfig config,
			ServerList<Server> serverList, ServerListFilter<Server> serverListFilter,
			IRule rule, IPing ping, ServerListUpdater serverListUpdater) {
		if (this.propertiesFactory.isSet(ILoadBalancer.class, name)) {
			return this.propertiesFactory.get(ILoadBalancer.class, config, name);
		}
		return new ZoneAwareLoadBalancer<>(config, rule, ping, serverList, // 创建负载均衡器实现类对象ZoneAwareLoadBalancer（负载均衡器包含了客户端负载均衡器配置、负载均衡策略、负载均衡探活策略、服务列表、服务列表更新策略）
				serverListFilter, serverListUpdater);
	}

	@Bean
	@ConditionalOnMissingBean
	@SuppressWarnings("unchecked")
	public ServerListFilter<Server> ribbonServerListFilter(IClientConfig config) { // 服务列表过滤策略（仅当使用动态ServerList时使用，用于在原始的服务列表中使用一定策略过虑掉一部分地址）
		if (this.propertiesFactory.isSet(ServerListFilter.class, name)) {
			return this.propertiesFactory.get(ServerListFilter.class, config, name);
		}
		ZonePreferenceServerListFilter filter = new ZonePreferenceServerListFilter();
		filter.initWithNiwsConfig(config);
		return filter;
	}

	@Bean
	@ConditionalOnMissingBean
	public RibbonLoadBalancerContext ribbonLoadBalancerContext(ILoadBalancer loadBalancer,
			IClientConfig config, RetryHandler retryHandler) {
		return new RibbonLoadBalancerContext(loadBalancer, config, retryHandler);
	}

	@Bean
	@ConditionalOnMissingBean
	public RetryHandler retryHandler(IClientConfig config) {
		return new DefaultLoadBalancerRetryHandler(config);
	}

	@Bean
	@ConditionalOnMissingBean
	public ServerIntrospector serverIntrospector() {
		return new DefaultServerIntrospector();
	}

	@PostConstruct
	public void preprocess() {
		setRibbonProperty(name, DeploymentContextBasedVipAddresses.key(), name);
	}

	static class OverrideRestClient extends RestClient {

		private IClientConfig config;

		private ServerIntrospector serverIntrospector;

		protected OverrideRestClient(IClientConfig config,
				ServerIntrospector serverIntrospector) {
			super();
			this.config = config;
			this.serverIntrospector = serverIntrospector;
			initWithNiwsConfig(this.config);
		}

		@Override
		public URI reconstructURIWithServer(Server server, URI original) {
			URI uri = updateToSecureConnectionIfNeeded(original, this.config,
					this.serverIntrospector, server);
			return super.reconstructURIWithServer(server, uri);
		}

		@Override
		protected Client apacheHttpClientSpecificInitialization() {
			ApacheHttpClient4 apache = (ApacheHttpClient4) super.apacheHttpClientSpecificInitialization();
			apache.getClientHandler().getHttpClient().getParams().setParameter(
					ClientPNames.COOKIE_POLICY, CookiePolicy.IGNORE_COOKIES);
			return apache;
		}

	}

}

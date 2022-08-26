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

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Map;

import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.Server;

import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerRequest;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;

import static org.springframework.cloud.netflix.ribbon.RibbonUtils.updateToSecureConnectionIfNeeded;

/**
 * @author Spencer Gibb
 * @author Dave Syer
 * @author Ryan Baxter
 * @author Tim Ysewyn
 */
public class RibbonLoadBalancerClient implements LoadBalancerClient { // Ribbon的默认负载均衡客户端

	private SpringClientFactory clientFactory; // Ribbon子容器工厂

	public RibbonLoadBalancerClient(SpringClientFactory clientFactory) { // 初始化RibbonLoadBalancerClient，并注入Ribbon子容器工厂
		this.clientFactory = clientFactory;
	}

	@Override
	public URI reconstructURI(ServiceInstance instance, URI original) {
		Assert.notNull(instance, "instance can not be null");
		String serviceId = instance.getServiceId();
		RibbonLoadBalancerContext context = this.clientFactory
				.getLoadBalancerContext(serviceId);

		URI uri;
		Server server;
		if (instance instanceof RibbonServer) {
			RibbonServer ribbonServer = (RibbonServer) instance;
			server = ribbonServer.getServer();
			uri = updateToSecureConnectionIfNeeded(original, ribbonServer);
		}
		else {
			server = new Server(instance.getScheme(), instance.getHost(),
					instance.getPort());
			IClientConfig clientConfig = clientFactory.getClientConfig(serviceId);
			ServerIntrospector serverIntrospector = serverIntrospector(serviceId);
			uri = updateToSecureConnectionIfNeeded(original, clientConfig,
					serverIntrospector, server);
		}
		return context.reconstructURIWithServer(server, uri);
	}

	@Override
	public ServiceInstance choose(String serviceId) { // 根据服务Id通过负载均衡获取一个服务实例
		return choose(serviceId, null);
	}

	/**
	 * New: Select a server using a 'key'.
	 * @param serviceId of the service to choose an instance for
	 * @param hint to specify the service instance
	 * @return the selected {@link ServiceInstance}
	 */
	public ServiceInstance choose(String serviceId, Object hint) {
		Server server = getServer(getLoadBalancer(serviceId), hint);
		if (server == null) {
			return null;
		}
		return new RibbonServer(serviceId, server, isSecure(server, serviceId),
				serverIntrospector(serviceId).getMetadata(server));
	}

	@Override
	public <T> T execute(String serviceId, LoadBalancerRequest<T> request) // 从负载均衡器中选择一个服务并执行
			throws IOException {
		return execute(serviceId, request, null); // 从负载均衡器中选择一个服务并执行
	}

	/**
	 * New: Execute a request by selecting server using a 'key'. The hint will have to be
	 * the last parameter to not mess with the `execute(serviceId, ServiceInstance,
	 * request)` method. This somewhat breaks the fluent coding style when using a lambda
	 * to define the LoadBalancerRequest.
	 * @param <T> returned request execution result type
	 * @param serviceId id of the service to execute the request to
	 * @param request to be executed
	 * @param hint used to choose appropriate {@link Server} instance
	 * @return request execution result
	 * @throws IOException executing the request may result in an {@link IOException}
	 */
	public <T> T execute(String serviceId, LoadBalancerRequest<T> request, Object hint) // 从负载均衡器中选择一个服务并执行
			throws IOException {
		ILoadBalancer loadBalancer = getLoadBalancer(serviceId); // 根据服务名从Ribbon子容器中获取负载均衡器（其负载均衡器在RibbonClientConfiguration中进行了创建，默认延迟加载）
		Server server = getServer(loadBalancer, hint); // 根据负载均衡器选择Server服务（默认采用轮询算法）
		if (server == null) {
			throw new IllegalStateException("No instances available for " + serviceId);
		}
		RibbonServer ribbonServer = new RibbonServer(serviceId, server, // 封装成服务实例
				isSecure(server, serviceId),
				serverIntrospector(serviceId).getMetadata(server));

		return execute(serviceId, ribbonServer, request); // 执行请求
	}

	@Override
	public <T> T execute(String serviceId, ServiceInstance serviceInstance,
			LoadBalancerRequest<T> request) throws IOException {
		Server server = null;
		if (serviceInstance instanceof RibbonServer) {
			server = ((RibbonServer) serviceInstance).getServer();
		}
		if (server == null) {
			throw new IllegalStateException("No instances available for " + serviceId);
		}

		RibbonLoadBalancerContext context = this.clientFactory
				.getLoadBalancerContext(serviceId);
		RibbonStatsRecorder statsRecorder = new RibbonStatsRecorder(context, server);

		try {
			T returnVal = request.apply(serviceInstance); // 会调用LoadBalancerRequestFactory中createRequest的函数式接口
			statsRecorder.recordStats(returnVal);
			return returnVal;
		}
		// catch IOException and rethrow so RestTemplate behaves correctly
		catch (IOException ex) {
			statsRecorder.recordStats(ex);
			throw ex;
		}
		catch (Exception ex) {
			statsRecorder.recordStats(ex);
			ReflectionUtils.rethrowRuntimeException(ex);
		}
		return null;
	}

	private ServerIntrospector serverIntrospector(String serviceId) {
		ServerIntrospector serverIntrospector = this.clientFactory.getInstance(serviceId,
				ServerIntrospector.class);
		if (serverIntrospector == null) {
			serverIntrospector = new DefaultServerIntrospector();
		}
		return serverIntrospector;
	}

	private boolean isSecure(Server server, String serviceId) {
		IClientConfig config = this.clientFactory.getClientConfig(serviceId);
		ServerIntrospector serverIntrospector = serverIntrospector(serviceId);
		return RibbonUtils.isSecure(config, serverIntrospector, server);
	}

	// Note: This method could be removed?
	protected Server getServer(String serviceId) {
		return getServer(getLoadBalancer(serviceId), null);
	}

	protected Server getServer(ILoadBalancer loadBalancer) {
		return getServer(loadBalancer, null);
	}

	protected Server getServer(ILoadBalancer loadBalancer, Object hint) {
		if (loadBalancer == null) {
			return null;
		}
		// Use 'default' on a null hint, or just pass it on?
		return loadBalancer.chooseServer(hint != null ? hint : "default"); // 默认采用区域感知轮询负载均衡策略来实现负载均衡
	}

	protected ILoadBalancer getLoadBalancer(String serviceId) {
		return this.clientFactory.getLoadBalancer(serviceId);
	}

	/**
	 * Ribbon-server-specific {@link ServiceInstance} implementation.
	 */
	public static class RibbonServer implements ServiceInstance {

		private final String serviceId;

		private final Server server;

		private final boolean secure;

		private Map<String, String> metadata;

		public RibbonServer(String serviceId, Server server) {
			this(serviceId, server, false, Collections.emptyMap());
		}

		public RibbonServer(String serviceId, Server server, boolean secure,
				Map<String, String> metadata) {
			this.serviceId = serviceId;
			this.server = server;
			this.secure = secure;
			this.metadata = metadata;
		}

		@Override
		public String getInstanceId() {
			return this.server.getId();
		}

		@Override
		public String getServiceId() {
			return this.serviceId;
		}

		@Override
		public String getHost() {
			return this.server.getHost();
		}

		@Override
		public int getPort() {
			return this.server.getPort();
		}

		@Override
		public boolean isSecure() {
			return this.secure;
		}

		@Override
		public URI getUri() {
			return DefaultServiceInstance.getUri(this);
		}

		@Override
		public Map<String, String> getMetadata() {
			return this.metadata;
		}

		public Server getServer() {
			return this.server;
		}

		@Override
		public String getScheme() {
			return this.server.getScheme();
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder("RibbonServer{");
			sb.append("serviceId='").append(serviceId).append('\'');
			sb.append(", server=").append(server);
			sb.append(", secure=").append(secure);
			sb.append(", metadata=").append(metadata);
			sb.append('}');
			return sb.toString();
		}

	}

}

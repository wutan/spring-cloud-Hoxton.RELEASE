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

package org.springframework.cloud.openfeign.ribbon;

import java.io.IOException;
import java.net.URI;

import com.netflix.client.ClientException;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.DefaultClientConfigImpl;
import com.netflix.client.config.IClientConfig;
import feign.Client;
import feign.Request;
import feign.Response;

import org.springframework.cloud.netflix.ribbon.SpringClientFactory;

/**
 * @author Dave Syer
 *
 */
public class LoadBalancerFeignClient implements Client { // 具有负载均衡功能的FeignClient（整合Ribbon的入口，在FeignRibbonClientAutoConfiguration中进行加载）

	static final Request.Options DEFAULT_OPTIONS = new Request.Options(); // 默认的超时设置

	private final Client delegate; // 第三方客户端（默认为Feign内部的Client.Default）

	private CachingSpringLoadBalancerFactory lbClientFactory; // 缓存工厂类，根据clientName获取FeignLoadBalancer（具体整合Ribbon的功能在该类中）

	private SpringClientFactory clientFactory; // Ribbon子容器工厂

	public LoadBalancerFeignClient(Client delegate, // 初始化LoadBalancerFeignClient（在FeignRibbonClientAutoConfiguration中根据上下文环境进行选择性创建）
			CachingSpringLoadBalancerFactory lbClientFactory,
			SpringClientFactory clientFactory) {
		this.delegate = delegate; // 注入第三方客户端（默认为Client.Default）
		this.lbClientFactory = lbClientFactory; // 注入CachingSpringLoadBalancerFactory对象
		this.clientFactory = clientFactory; // 注入SpringClientFactory对象
	}

	static URI cleanUrl(String originalUrl, String host) {
		String newUrl = originalUrl;
		if (originalUrl.startsWith("https://")) {
			newUrl = originalUrl.substring(0, 8)
					+ originalUrl.substring(8 + host.length());
		}
		else if (originalUrl.startsWith("http")) {
			newUrl = originalUrl.substring(0, 7)
					+ originalUrl.substring(7 + host.length());
		}
		StringBuffer buffer = new StringBuffer(newUrl);
		if ((newUrl.startsWith("https://") && newUrl.length() == 8)
				|| (newUrl.startsWith("http://") && newUrl.length() == 7)) {
			buffer.append("/");
		}
		return URI.create(buffer.toString());
	}

	@Override
	public Response execute(Request request, Request.Options options) throws IOException { // 执行请求
		try {
			URI asUri = URI.create(request.url());
			String clientName = asUri.getHost(); // 获取clientName
			URI uriWithoutHost = cleanUrl(request.url(), clientName);
			FeignLoadBalancer.RibbonRequest ribbonRequest = new FeignLoadBalancer.RibbonRequest( // 构建RibbonRequest，内部维护了第三方客户端（默认为Client.Default）
					this.delegate, request, uriWithoutHost);

			IClientConfig requestConfig = getClientConfig(options, clientName); // 获取Ribbond的IClientConfig实现类（OpenFeign的实现或是Ribbon的实现）
			return lbClient(clientName) // 从缓存中获取或创建具有负载均衡的Client--FeignLoadBalancer
					.executeWithLoadBalancer(ribbonRequest, requestConfig).toResponse(); // 根据负载均衡执行请求
		}
		catch (ClientException e) {
			IOException io = findIOException(e);
			if (io != null) {
				throw io;
			}
			throw new RuntimeException(e);
		}
	}

	IClientConfig getClientConfig(Request.Options options, String clientName) { // 获取IClientConfig实现类（OpenFeign的实现或是Ribbon的实现）
		IClientConfig requestConfig;
		if (options == DEFAULT_OPTIONS) { // 当没有设置Feign的超时，即没有在代码中自定义装配该Bean也没在在全局属性和实例属性中指定超时时间，会读取Ribbon的配置时，使用Ribbon的超时时间和重试设置，则超时时间大概是(MaxAutoRetries+1)*(MaxAutoRetriesNextServer+1)*(ConnectTimeout+ReadTimeout)
			requestConfig = this.clientFactory.getClientConfig(clientName);
		}
		else {
			requestConfig = new FeignOptionsClientConfig(options); // 否则使用OpenFeign自身的设置（两者是二选一），则超时时间大概是Retryer.Default.maxAttempts*(ConnectTimeout+ReadTimeout)，Feign与Ribbon的重试相比：重试次数包含了首次、不能设置多实例服务切换、重试有一个延迟时间
		}
		return requestConfig;
	}

	protected IOException findIOException(Throwable t) {
		if (t == null) {
			return null;
		}
		if (t instanceof IOException) {
			return (IOException) t;
		}
		return findIOException(t.getCause());
	}

	public Client getDelegate() {
		return this.delegate;
	}

	private FeignLoadBalancer lbClient(String clientName) { // 获取FeignLoadBalancer（Feign整合Ribbon的负载均衡器，且不带重试机制）
		return this.lbClientFactory.create(clientName); // 获取FeignLoadBalancer（先从缓存中获取）
	}

	static class FeignOptionsClientConfig extends DefaultClientConfigImpl { // Feign的IClientConfig继承自Ribbon的IClientConfig（只不过是在构造函数中设置了连接超时时间和读取超时时间）

		FeignOptionsClientConfig(Request.Options options) { // 实例化FeignOptionsClientConfig，并设置连接超时时间和读取超时时间
			setProperty(CommonClientConfigKey.ConnectTimeout,
					options.connectTimeoutMillis());
			setProperty(CommonClientConfigKey.ReadTimeout, options.readTimeoutMillis());
		}

		@Override
		public void loadProperties(String clientName) {

		}

		@Override
		public void loadDefaultValues() {

		}

	}

}

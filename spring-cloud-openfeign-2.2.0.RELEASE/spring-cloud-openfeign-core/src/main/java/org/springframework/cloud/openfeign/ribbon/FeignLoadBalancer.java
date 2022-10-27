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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.netflix.client.AbstractLoadBalancerAwareClient;
import com.netflix.client.ClientException;
import com.netflix.client.ClientRequest;
import com.netflix.client.IResponse;
import com.netflix.client.RequestSpecificRetryHandler;
import com.netflix.client.RetryHandler;
import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.Server;
import feign.Client;
import feign.Request;
import feign.Response;

import org.springframework.cloud.netflix.ribbon.RibbonProperties;
import org.springframework.cloud.netflix.ribbon.ServerIntrospector;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;

import static org.springframework.cloud.netflix.ribbon.RibbonUtils.updateToSecureConnectionIfNeeded;

/**
 * @author Dave Syer
 * @author Spencer Gibb
 * @author Ryan Baxter
 * @author Tim Ysewyn
 * @author Olga Maciaszek-Sharma
 */
public class FeignLoadBalancer extends // Feign整合Ribbon的负载均衡器（不带重试机制），继承自AbstractLoadBalancerAwareClient
		AbstractLoadBalancerAwareClient<FeignLoadBalancer.RibbonRequest, FeignLoadBalancer.RibbonResponse> {

	private final RibbonProperties ribbon; // RibbonProperties中维护了IClientConfig

	protected int connectTimeout; // 连接超时时间（优先级：Ribbon动态属性>Ribbon实例属性>Ribbon全局属性>Ribbon静态属性）

	protected int readTimeout; // 读取超时时间（优先级：Ribbon动态属性>Ribbon实例属性>Ribbon全局属性>Ribbon静态属性）

	protected IClientConfig clientConfig; // 通过Ribbon子容器工厂获取的默认客户端配置

	protected ServerIntrospector serverIntrospector;

	public FeignLoadBalancer(ILoadBalancer lb, IClientConfig clientConfig, // 初始化Feign的负载均衡器（不带重试机制）
			ServerIntrospector serverIntrospector) {
		super(lb, clientConfig);
		this.setRetryHandler(RetryHandler.DEFAULT);
		this.clientConfig = clientConfig; // Ribbon的默认IClientConfig实现类
		this.ribbon = RibbonProperties.from(clientConfig); // 将Ribbon的IClientConfig封装到RibbonProperties中
		RibbonProperties ribbon = this.ribbon; // RibbonProperties中维护了IClientConfig
		this.connectTimeout = ribbon.getConnectTimeout(); // 从IClientConfig中获取连接超时时间（属性配置优先）
		this.readTimeout = ribbon.getReadTimeout(); // 从IClientConfig中获取读取超时时间（属性配置优先）
		this.serverIntrospector = serverIntrospector;
	}

	@Override
	public RibbonResponse execute(RibbonRequest request, IClientConfig configOverride) // 获取真实的请求地址后最终发起请求
			throws IOException {
		Request.Options options;
		if (configOverride != null) {
			RibbonProperties override = RibbonProperties.from(configOverride);
			options = new Request.Options(override.connectTimeout(this.connectTimeout), // 在发起请求前重新设置从IClientConfig中获取的超时时间
					override.readTimeout(this.readTimeout));
		}
		else {
			options = new Request.Options(this.connectTimeout, this.readTimeout); // 在发起请求前重新设置从IClientConfig中获取的超时时间
		}
		Response response = request.client().execute(request.toRequest(), options); // 根据Client获取响应（默认Client为Client.Default）
		return new RibbonResponse(request.getUri(), response); // 设置响应
	}

	@Override
	public RequestSpecificRetryHandler getRequestSpecificRetryHandler( // 获取重试处理器
			RibbonRequest request, IClientConfig requestConfig) {
		if (this.ribbon.isOkToRetryOnAllOperations()) {
			return new RequestSpecificRetryHandler(true, true, this.getRetryHandler(), // 创建重试处理器
					requestConfig);
		}
		if (!request.toRequest().httpMethod().name().equals("GET")) {
			return new RequestSpecificRetryHandler(true, false, this.getRetryHandler(), // 创建重试处理器
					requestConfig);
		}
		else {
			return new RequestSpecificRetryHandler(true, true, this.getRetryHandler(), // 创建重试处理器
					requestConfig);
		}
	}

	@Override
	public URI reconstructURIWithServer(Server server, URI original) {
		URI uri = updateToSecureConnectionIfNeeded(original, this.clientConfig,
				this.serverIntrospector, server);
		return super.reconstructURIWithServer(server, uri);
	}

	protected static class RibbonRequest extends ClientRequest implements Cloneable {

		private final Request request; // Feign的请求

		private final Client client; // 第三方客户端

		protected RibbonRequest(Client client, Request request, URI uri) { // 初始化RibbonRequest
			this.client = client; // 注入第三方客户端（默认为Client.Default）
			setUri(uri); // 注入请求地址
			this.request = toRequest(request); // 注入Feign请求
		}

		private Request toRequest(Request request) {
			Map<String, Collection<String>> headers = new LinkedHashMap<>(
					request.headers());
			return Request.create(request.httpMethod(), getUri().toASCIIString(), headers,
					request.requestBody());
		}

		Request toRequest() {
			return toRequest(this.request);
		}

		Client client() {
			return this.client;
		}

		HttpRequest toHttpRequest() {
			return new HttpRequest() {
				@Override
				public HttpMethod getMethod() {
					return HttpMethod
							.resolve(RibbonRequest.this.toRequest().httpMethod().name());
				}

				@Override
				public String getMethodValue() {
					return getMethod().name();
				}

				@Override
				public URI getURI() {
					return RibbonRequest.this.getUri();
				}

				@Override
				public HttpHeaders getHeaders() {
					Map<String, List<String>> headers = new HashMap<>();
					Map<String, Collection<String>> feignHeaders = RibbonRequest.this
							.toRequest().headers();
					for (String key : feignHeaders.keySet()) {
						headers.put(key, new ArrayList<String>(feignHeaders.get(key)));
					}
					HttpHeaders httpHeaders = new HttpHeaders();
					httpHeaders.putAll(headers);
					return httpHeaders;

				}
			};
		}

		public Request getRequest() {
			return this.request;
		}

		public Client getClient() {
			return this.client;
		}

		@Override
		public Object clone() {
			return new RibbonRequest(this.client, this.request, getUri());
		}

	}

	protected static class RibbonResponse implements IResponse {

		private final URI uri;

		private final Response response;

		protected RibbonResponse(URI uri, Response response) {
			this.uri = uri;
			this.response = response;
		}

		@Override
		public Object getPayload() throws ClientException {
			return this.response.body();
		}

		@Override
		public boolean hasPayload() {
			return this.response.body() != null;
		}

		@Override
		public boolean isSuccess() {
			return this.response.status() == 200;
		}

		@Override
		public URI getRequestedURI() {
			return this.uri;
		}

		@Override
		public Map<String, Collection<String>> getHeaders() {
			return this.response.headers();
		}

		Response toResponse() {
			return this.response;
		}

		@Override
		public void close() throws IOException {
			if (this.response != null && this.response.body() != null) {
				this.response.body().close();
			}
		}

	}

}

/*
 * Copyright 2012-2019 the original author or authors.
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

package org.springframework.cloud.client.loadbalancer;

import java.io.IOException;
import java.net.URI;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.Assert;

/**
 * @author Spencer Gibb
 * @author Dave Syer
 * @author Ryan Baxter
 * @author William Tran
 */
public class LoadBalancerInterceptor implements ClientHttpRequestInterceptor { // LoadBalancer拦截器

	private LoadBalancerClient loadBalancer; // LoadBalancer客户端，默认为RibbonLoadBalancerClient（LoadBalancer拦截器会委托给LoadBalancer客户端进行处理）

	private LoadBalancerRequestFactory requestFactory;

	public LoadBalancerInterceptor(LoadBalancerClient loadBalancer, // 初始化拦截器LoadBalancerInterceptor（构造方法注入LoadBalancerClient、LoadBalancerRequestFactory）
			LoadBalancerRequestFactory requestFactory) {
		this.loadBalancer = loadBalancer;
		this.requestFactory = requestFactory;
	}

	public LoadBalancerInterceptor(LoadBalancerClient loadBalancer) {
		// for backwards compatibility
		this(loadBalancer, new LoadBalancerRequestFactory(loadBalancer));
	}

	@Override
	public ClientHttpResponse intercept(final HttpRequest request, final byte[] body, // 拦截请求，在InterceptingClientHttpRequest.InterceptingRequestExecution#execute方法中会被调用到
			final ClientHttpRequestExecution execution) throws IOException {
		final URI originalUri = request.getURI(); // 获取访问地址
		String serviceName = originalUri.getHost(); // 获取访问地址的服务名
		Assert.state(serviceName != null,
				"Request URI does not contain a valid hostname: " + originalUri);
		return this.loadBalancer.execute(serviceName, // 通过拦截器委托给RibbonLoadBalancerClient去调用
				this.requestFactory.createRequest(request, body, execution)); // 构建LoadBalancerRequest，在LoadBalancerClient.execute的方法中会调用LoadBalancerRequest.apply方法执行内部逻辑
	}

}

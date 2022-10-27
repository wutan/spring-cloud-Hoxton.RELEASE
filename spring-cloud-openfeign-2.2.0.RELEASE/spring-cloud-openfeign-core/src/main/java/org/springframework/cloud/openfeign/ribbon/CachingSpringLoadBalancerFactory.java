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

import java.util.Map;

import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.ILoadBalancer;

import org.springframework.cloud.client.loadbalancer.LoadBalancedRetryFactory;
import org.springframework.cloud.netflix.ribbon.ServerIntrospector;
import org.springframework.cloud.netflix.ribbon.SpringClientFactory;
import org.springframework.util.ConcurrentReferenceHashMap;

/**
 * Factory for SpringLoadBalancer instances that caches the entries created.
 *
 * @author Spencer Gibb
 * @author Dave Syer
 * @author Ryan Baxter
 * @author Gang Li
 */
public class CachingSpringLoadBalancerFactory {

	protected final SpringClientFactory factory; // Ribbon子容器工厂

	protected LoadBalancedRetryFactory loadBalancedRetryFactory = null; // 未引入spring-retry依赖包时为空

	private volatile Map<String, FeignLoadBalancer> cache = new ConcurrentReferenceHashMap<>(); // Feign整合Ribbon的负载均衡器的缓存容器

	public CachingSpringLoadBalancerFactory(SpringClientFactory factory) { // 未引入spring-retry依赖包时进行调用创建
		this.factory = factory;
	}

	public CachingSpringLoadBalancerFactory(SpringClientFactory factory, // 引入spring-retry依赖包时进行调用创建
			LoadBalancedRetryFactory loadBalancedRetryPolicyFactory) {
		this.factory = factory;
		this.loadBalancedRetryFactory = loadBalancedRetryPolicyFactory;
	}

	public FeignLoadBalancer create(String clientName) { // 生成具有负载均衡的Feign（Feign整合Ribbon的负载均衡器，且不带重试机制）
		FeignLoadBalancer client = this.cache.get(clientName); // 先从缓存中获取clientName对应的负载均衡器
		if (client != null) { // 当缓存中存在时直接返回
			return client;
		}
		IClientConfig config = this.factory.getClientConfig(clientName); // 通过Ribbon子容器工厂获取默认客户端配置
		ILoadBalancer lb = this.factory.getLoadBalancer(clientName); // 通过Ribbon子容器工厂获取负载均衡器
		ServerIntrospector serverIntrospector = this.factory.getInstance(clientName,
				ServerIntrospector.class);
		client = this.loadBalancedRetryFactory != null
				? new RetryableFeignLoadBalancer(lb, config, serverIntrospector, // 创建Feign的负载均衡器（带重试机制）
						this.loadBalancedRetryFactory)
				: new FeignLoadBalancer(lb, config, serverIntrospector); // 创建Feign的负载均衡器（不带重试机制）
		this.cache.put(clientName, client); // 放入缓存
		return client;
	}

}

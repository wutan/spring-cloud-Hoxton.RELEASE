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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * Auto-configuration for Ribbon (client-side load balancing).
 *
 * @author Spencer Gibb
 * @author Dave Syer
 * @author Will Tran
 * @author Gang Li
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RestTemplate.class)
// 默认在RibbonAutoConfiguration中创建RibbonLoadBalancerClient
@ConditionalOnBean(LoadBalancerClient.class)
@EnableConfigurationProperties(LoadBalancerRetryProperties.class)
public class LoadBalancerAutoConfiguration {

	// RibbonAutoConfiguration会在LoadBalancerAutoConfiguration之前加载（实例化LoadBalancerClient等）
    // @LoadBalanced注解
	@LoadBalanced
	@Autowired(required = false)
	private List<RestTemplate> restTemplates = Collections.emptyList(); // 收集修饰了@LoadBalanced(@Qualifier标记)的RestTemplate

	@Autowired(required = false)
	private List<LoadBalancerRequestTransformer> transformers = Collections.emptyList();


	// 3.初始化SmartInitializingSingleton
	// 用于给RestTemplate增加LoadBalancerInterceptor拦截器
	@Bean
	public SmartInitializingSingleton loadBalancedRestTemplateInitializerDeprecated(final ObjectProvider<List<RestTemplateCustomizer>> restTemplateCustomizers) {

		// 1. 函数式接口，SmartInitializingSingleton类的afterSingletonsInstantiated方法在所有类都getBean完成后调用
		// 2.
		return () -> restTemplateCustomizers.ifAvailable(customizers -> {
			// @LoadBalance修改的restTemplates
			for (RestTemplate restTemplate : LoadBalancerAutoConfiguration.this.restTemplates) {
				// 对修饰了@LoadBalanced注解的RestTemplate实例添加LoadBalancerInterceptor拦截器/请求工厂
				for (RestTemplateCustomizer customizer : customizers) {
					// 真正开始调用RestTemplateCustomizer的函数式接口将拦截器/请求工厂设置到RestTemplate中
					customizer.customize(restTemplate);
				}
			}
		});
	}


	// 0.创建LoadBalancerRequestFactory
	@Bean
	@ConditionalOnMissingBean
	public LoadBalancerRequestFactory loadBalancerRequestFactory(LoadBalancerClient loadBalancerClient) {
		return new LoadBalancerRequestFactory(loadBalancerClient, this.transformers);
	}


	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingClass("org.springframework.retry.support.RetryTemplate") // 不存在RetryTemplate类时
	static class LoadBalancerInterceptorConfig {

		// 1.创建拦截器LoadBalancerInterceptor
		@Bean
		public LoadBalancerInterceptor ribbonInterceptor(LoadBalancerClient loadBalancerClient, LoadBalancerRequestFactory requestFactory) {
			return new LoadBalancerInterceptor(loadBalancerClient, requestFactory);
		}


		// 2.初始化RestTemplateCustomizer（当容器中不存在RestTemplateCustomizer时）
		@Bean
		@ConditionalOnMissingBean
		public RestTemplateCustomizer restTemplateCustomizer(final LoadBalancerInterceptor loadBalancerInterceptor) {

			return restTemplate -> { // 函数式接口
				// 获取默认的拦截器链
				List<ClientHttpRequestInterceptor> list = new ArrayList<>(restTemplate.getInterceptors());
				// 添加Spring容器中的拦截器
				list.add(loadBalancerInterceptor);
				// 将拦截器列表设置到RestTemplate中（RestTemplate继承自InterceptingHttpAccessor）
				restTemplate.setInterceptors(list);
			};
		}

	}

	/**
	 * Auto configuration for retry mechanism.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(RetryTemplate.class)
	public static class RetryAutoConfiguration {

		@Bean
		@ConditionalOnMissingBean
		public LoadBalancedRetryFactory loadBalancedRetryFactory() {
			return new LoadBalancedRetryFactory() {};
		}

	}

	/**
	 * Auto configuration for retry intercepting mechanism.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(RetryTemplate.class) // 存在RetryTemplate类时
	public static class RetryInterceptorAutoConfiguration {

		@Bean
		@ConditionalOnMissingBean
		public RetryLoadBalancerInterceptor ribbonInterceptor( // 初始化拦截器RetryLoadBalancerInterceptor
				LoadBalancerClient loadBalancerClient,
				LoadBalancerRetryProperties properties,
				LoadBalancerRequestFactory requestFactory,
				LoadBalancedRetryFactory loadBalancedRetryFactory) {

			return new RetryLoadBalancerInterceptor(loadBalancerClient, properties, requestFactory, loadBalancedRetryFactory);
		}

		@Bean
		@ConditionalOnMissingBean
		public RestTemplateCustomizer restTemplateCustomizer(final RetryLoadBalancerInterceptor loadBalancerInterceptor) {

			return restTemplate -> { // 函数式接口
				List<ClientHttpRequestInterceptor> list = new ArrayList<>(restTemplate.getInterceptors());
				list.add(loadBalancerInterceptor);
				restTemplate.setInterceptors(list);
			};
		}

	}

}

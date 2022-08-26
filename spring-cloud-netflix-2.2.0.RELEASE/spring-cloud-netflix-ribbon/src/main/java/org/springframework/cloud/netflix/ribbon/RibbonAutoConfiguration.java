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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

import com.netflix.client.IClient;
import com.netflix.client.http.HttpRequest;
import com.netflix.ribbon.Ribbon;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.actuator.HasFeatures;
import org.springframework.cloud.client.loadbalancer.AsyncLoadBalancerAutoConfiguration;
import org.springframework.cloud.client.loadbalancer.LoadBalancedRetryFactory;
import org.springframework.cloud.client.loadbalancer.LoadBalancerAutoConfiguration;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.AsyncRestTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * Auto configuration for Ribbon (client side load balancing).
 *
 * @author Spencer Gibb
 * @author Dave Syer
 * @author Biju Kunjummen
 */
@Configuration
@Conditional(RibbonAutoConfiguration.RibbonClassesConditions.class)
@RibbonClients
@AutoConfigureAfter(
		name = "org.springframework.cloud.netflix.eureka.EurekaClientAutoConfiguration")
@AutoConfigureBefore({ LoadBalancerAutoConfiguration.class, // RibbonAutoConfiguration会在LoadBalancerAutoConfiguration之前加载
		AsyncLoadBalancerAutoConfiguration.class })
@EnableConfigurationProperties({ RibbonEagerLoadProperties.class,
		ServerIntrospectorProperties.class })
public class RibbonAutoConfiguration {

	@Autowired(required = false)
	private List<RibbonClientSpecification> configurations = new ArrayList<>(); // 收集Ribbon子容器私有规范/配置

	@Autowired
	private RibbonEagerLoadProperties ribbonEagerLoadProperties; // Ribbon饥饿加载配置

	@Bean
	public HasFeatures ribbonFeature() {
		return HasFeatures.namedFeature("Ribbon", Ribbon.class);
	}

	@Bean
	public SpringClientFactory springClientFactory() {
		SpringClientFactory factory = new SpringClientFactory(); // 创建SpringClientFactory并设置RibbonClientSpecification，即创建Ribbon子容器工厂并设置Ribbon子容器私有规范/配置
		factory.setConfigurations(this.configurations); // 将RibbonClientSpecification集合设置到SpringClientFactory中
		return factory;
	}

	@Bean
	@ConditionalOnMissingBean(LoadBalancerClient.class)
	public LoadBalancerClient loadBalancerClient() {
		return new RibbonLoadBalancerClient(springClientFactory()); // 创建LoadBalancerClient接口实例，用于委托调用
	}

	@Bean
	@ConditionalOnClass(name = "org.springframework.retry.support.RetryTemplate")
	@ConditionalOnMissingBean
	public LoadBalancedRetryFactory loadBalancedRetryPolicyFactory(
			final SpringClientFactory clientFactory) {
		return new RibbonLoadBalancedRetryFactory(clientFactory);
	}

	@Bean
	@ConditionalOnMissingBean
	public PropertiesFactory propertiesFactory() {
		return new PropertiesFactory(); // 创建PropertiesFactory - Ribbon属性工厂，通过配置方式指定RibbonClientConfiguration的相关Bean
	}

	@Bean
	@ConditionalOnProperty("ribbon.eager-load.enabled") // 当有ribbon.eager-load.enabled属性时才初始化
	public RibbonApplicationContextInitializer ribbonApplicationContextInitializer() {
		return new RibbonApplicationContextInitializer(springClientFactory(), // 创建RibbonApplicationContextInitializer，传入Ribbon子容器工厂及要饥饿加载的客户端列表
				ribbonEagerLoadProperties.getClients());
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(HttpRequest.class)
	@ConditionalOnRibbonRestClient
	protected static class RibbonClientHttpRequestFactoryConfiguration {

		@Autowired
		private SpringClientFactory springClientFactory;

		@Bean
		public RestTemplateCustomizer restTemplateCustomizer(
				final RibbonClientHttpRequestFactory ribbonClientHttpRequestFactory) {
			return restTemplate -> restTemplate
					.setRequestFactory(ribbonClientHttpRequestFactory);
		}

		@Bean
		public RibbonClientHttpRequestFactory ribbonClientHttpRequestFactory() {
			return new RibbonClientHttpRequestFactory(this.springClientFactory);
		}

	}

	// TODO: support for autoconfiguring restemplate to use apache http client or okhttp

	@Target({ ElementType.TYPE, ElementType.METHOD })
	@Retention(RetentionPolicy.RUNTIME)
	@Documented
	@Conditional(OnRibbonRestClientCondition.class)
	@interface ConditionalOnRibbonRestClient {

	}

	private static class OnRibbonRestClientCondition extends AnyNestedCondition {

		OnRibbonRestClientCondition() {
			super(ConfigurationPhase.REGISTER_BEAN);
		}

		@Deprecated // remove in Edgware"
		@ConditionalOnProperty("ribbon.http.client.enabled")
		static class ZuulProperty {

		}

		@ConditionalOnProperty("ribbon.restclient.enabled")
		static class RibbonProperty {

		}

	}

	/**
	 * {@link AllNestedConditions} that checks that either multiple classes are present.
	 */
	static class RibbonClassesConditions extends AllNestedConditions {

		RibbonClassesConditions() {
			super(ConfigurationPhase.PARSE_CONFIGURATION);
		}

		@ConditionalOnClass(IClient.class)
		static class IClientPresent {

		}

		@ConditionalOnClass(RestTemplate.class)
		static class RestTemplatePresent {

		}

		@ConditionalOnClass(AsyncRestTemplate.class)
		static class AsyncRestTemplatePresent {

		}

		@ConditionalOnClass(Ribbon.class)
		static class RibbonPresent {

		}

	}

}

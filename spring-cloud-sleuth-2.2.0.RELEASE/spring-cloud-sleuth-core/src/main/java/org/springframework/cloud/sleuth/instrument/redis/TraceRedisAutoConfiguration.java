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

package org.springframework.cloud.sleuth.instrument.redis;

import brave.Tracing;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.tracing.BraveTracing;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.sleuth.autoconfig.TraceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link org.springframework.boot.autoconfigure.EnableAutoConfiguration
 * Auto-configuration} enables Redis span information propagation.
 *
 * @author Chao Chang
 * @since 2.2.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(value = "spring.sleuth.redis.enabled", matchIfMissing = true)
@ConditionalOnBean({ Tracing.class, ClientResources.class })
@AutoConfigureAfter({ TraceAutoConfiguration.class })
@EnableConfigurationProperties(TraceRedisProperties.class)
public class TraceRedisAutoConfiguration {

	@Configuration(proxyBeanMethods = false)
	static class LettuceConfig {

		@Bean
		static TraceLettuceClientResourcesBeanPostProcessor traceLettuceClientResourcesBeanPostProcessor(
				Tracing tracing, TraceRedisProperties traceRedisProperties) {
			return new TraceLettuceClientResourcesBeanPostProcessor(tracing,
					traceRedisProperties);
		}

	}

}

class TraceLettuceClientResourcesBeanPostProcessor implements BeanPostProcessor {

	private static final Log log = LogFactory
			.getLog(TraceLettuceClientResourcesBeanPostProcessor.class);

	private final Tracing tracing;

	private final TraceRedisProperties traceRedisProperties;

	TraceLettuceClientResourcesBeanPostProcessor(Tracing tracing,
			TraceRedisProperties traceRedisProperties) {
		this.tracing = tracing;
		this.traceRedisProperties = traceRedisProperties;
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName)
			throws BeansException {
		return bean;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName)
			throws BeansException {
		if (bean instanceof ClientResources) {
			ClientResources cr = (ClientResources) bean;
			if (!cr.tracing().isEnabled()) {
				if (log.isDebugEnabled()) {
					log.debug(
							"Lettuce ClientResources bean is auto-configured to enable tracing.");
				}
				BraveTracing lettuceTracing = BraveTracing.builder().tracing(this.tracing)
						.serviceName(traceRedisProperties.getRemoteServiceName()).build();
				return cr.mutate().tracing(lettuceTracing).build();
			}
			if (log.isDebugEnabled()) {
				log.debug(
						"Lettuce ClientResources bean is skipped for auto-configuration because tracing was already enabled.");
			}
		}
		return bean;
	}

}

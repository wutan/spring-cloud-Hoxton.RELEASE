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
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import static org.assertj.core.api.BDDAssertions.then;

/**
 * @author Chao Chang
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TraceRedisAutoConfigurationTests.Config.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class TraceRedisAutoConfigurationTests {

	@Autowired
	ClientResources clientResources;

	@Autowired
	TestTraceLettuceClientResourcesBeanPostProcessor traceLettuceClientResourcesBeanPostProcessor;

	@Test
	public void tracing_should_be_set() {
		then(this.traceLettuceClientResourcesBeanPostProcessor.tracingCalled).isTrue();
		then(this.clientResources.tracing().isEnabled()).isTrue();
	}

	@Configuration
	@EnableAutoConfiguration
	protected static class Config {

		@Bean
		ClientResources clientResources() {
			ClientResources clientResources = ClientResources.create();
			then(clientResources.tracing().isEnabled()).isFalse();
			return clientResources;
		}

		@Bean
		TraceRedisProperties traceRedisProperties() {
			TraceRedisProperties traceRedisProperties = new TraceRedisProperties();
			traceRedisProperties.setEnabled(true);
			traceRedisProperties.setRemoteServiceName("redis-foo");
			return traceRedisProperties;
		}

		@Bean
		TestTraceLettuceClientResourcesBeanPostProcessor testTraceLettuceClientResourcesBeanPostProcessor(
				Tracing tracing, TraceRedisProperties traceRedisProperties) {
			return new TestTraceLettuceClientResourcesBeanPostProcessor(tracing,
					traceRedisProperties);
		}

	}

}

class TestTraceLettuceClientResourcesBeanPostProcessor
		extends TraceLettuceClientResourcesBeanPostProcessor {

	boolean tracingCalled = false;

	TestTraceLettuceClientResourcesBeanPostProcessor(Tracing tracing,
			TraceRedisProperties traceRedisProperties) {
		super(tracing, traceRedisProperties);
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName)
			throws BeansException {
		this.tracingCalled = true;
		return super.postProcessAfterInitialization(bean, beanName);
	}

}

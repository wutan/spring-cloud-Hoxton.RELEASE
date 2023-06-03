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

package org.springframework.cloud.client.serviceregistry;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * @author Spencer Gibb
 */
@Configuration(proxyBeanMethods = false)
@Import(AutoServiceRegistrationConfiguration.class)
@ConditionalOnProperty(value = "spring.cloud.service-registry.auto-registration.enabled",
		matchIfMissing = true)
public class AutoServiceRegistrationAutoConfiguration {

	@Autowired(required = false)
	private AutoServiceRegistration autoServiceRegistration; // 注入服务自动注册类

	@Autowired
	private AutoServiceRegistrationProperties properties; // 注入服务自动注册属性类

	@PostConstruct
	protected void init() {
		if (this.autoServiceRegistration == null && this.properties.isFailFast()) { // 检查没有服务自动注册类时，是否快速抛出异常
			throw new IllegalStateException("Auto Service Registration has "
					+ "been requested, but there is no AutoServiceRegistration bean");
		}
	}

}

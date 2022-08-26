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

import java.util.Map;

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.StringUtils;

/**
 * @author Dave Syer
 */
public class RibbonClientConfigurationRegistrar implements ImportBeanDefinitionRegistrar {

	@Override
	public void registerBeanDefinitions(AnnotationMetadata metadata,
			BeanDefinitionRegistry registry) {
		Map<String, Object> attrs = metadata
				.getAnnotationAttributes(RibbonClients.class.getName(), true); // 1.获取@RibbonClients注解中定义的相关信息
		if (attrs != null && attrs.containsKey("value")) { // 获取@RibbonClients注解中定义的value信息（RibbonClient类的数组信息）
			AnnotationAttributes[] clients = (AnnotationAttributes[]) attrs.get("value");
			for (AnnotationAttributes client : clients) { // 遍历RibbonClient
				registerClientConfiguration(registry, getClientName(client), // 1.1注册RibbonClient配置信息
						client.get("configuration"));
			}
		}
		if (attrs != null && attrs.containsKey("defaultConfiguration")) { // 1.2获取@RibbonClients注解中定义的defaultConfiguration信息（RibbonClient的默认配置）
			String name;
			if (metadata.hasEnclosingClass()) {
				name = "default." + metadata.getEnclosingClassName();
			}
			else {
				name = "default." + metadata.getClassName();
			}
			registerClientConfiguration(registry, name, // 注册RibbonClient的默认配置信息（默认情况下会注册一个空集合的默认配置）
					attrs.get("defaultConfiguration"));
		}
		Map<String, Object> client = metadata
				.getAnnotationAttributes(RibbonClient.class.getName(), true); // 2.获取@RibbonClient注解中定义的相关信息
		String name = getClientName(client);
		if (name != null) {
			registerClientConfiguration(registry, name, client.get("configuration")); // 注册RibbonClient配置信息
		}
	}

	private String getClientName(Map<String, Object> client) { // 获取@RibbonClient注解对应的name信息
		if (client == null) {
			return null;
		}
		String value = (String) client.get("value");
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("name");
		}
		if (StringUtils.hasText(value)) {
			return value;
		}
		throw new IllegalStateException(
				"Either 'name' or 'value' must be provided in @RibbonClient");
	}

	private void registerClientConfiguration(BeanDefinitionRegistry registry, Object name,
			Object configuration) {
		BeanDefinitionBuilder builder = BeanDefinitionBuilder
				.genericBeanDefinition(RibbonClientSpecification.class);
		builder.addConstructorArgValue(name);
		builder.addConstructorArgValue(configuration);
		registry.registerBeanDefinition(name + ".RibbonClientSpecification",
				builder.getBeanDefinition());
	}

}

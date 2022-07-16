/*
 * Copyright 2013-2014 the original author or authors.
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

import java.util.Arrays;
import java.util.Objects;

import org.springframework.cloud.context.named.NamedContextFactory;

/**
 * @author Dave Syer
 */
public class RibbonClientSpecification implements NamedContextFactory.Specification { // Ribbon子容器私有规范/配置

	private String name; // 子容器名称

	private Class<?>[] configuration; // 子容器私有配置

	public RibbonClientSpecification() {
	}

	public RibbonClientSpecification(String name, Class<?>[] configuration) { // 创建RibbonClientSpecification（通过注册BeanDefinition来实现动态创建）
		this.name = name;
		this.configuration = configuration;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Class<?>[] getConfiguration() {
		return configuration;
	}

	public void setConfiguration(Class<?>[] configuration) {
		this.configuration = configuration;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		RibbonClientSpecification that = (RibbonClientSpecification) o;
		return Arrays.equals(configuration, that.configuration)
				&& Objects.equals(name, that.name);
	}

	@Override
	public int hashCode() {
		return Objects.hash(configuration, name);
	}

	@Override
	public String toString() {
		return new StringBuilder("RibbonClientSpecification{").append("name='")
				.append(name).append("', ").append("configuration=")
				.append(Arrays.toString(configuration)).append("}").toString();
	}

}

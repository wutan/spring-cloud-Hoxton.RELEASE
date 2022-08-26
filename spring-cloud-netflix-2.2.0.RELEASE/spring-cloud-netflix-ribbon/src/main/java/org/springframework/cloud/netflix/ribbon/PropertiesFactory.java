/*
 * Copyright 2016-2019 the original author or authors.
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

import java.util.HashMap;
import java.util.Map;

import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.IPing;
import com.netflix.loadbalancer.IRule;
import com.netflix.loadbalancer.ServerList;
import com.netflix.loadbalancer.ServerListFilter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import static org.springframework.cloud.netflix.ribbon.SpringClientFactory.NAMESPACE;

/**
 * @author Spencer Gibb
 */
public class PropertiesFactory { // Ribbon属性工厂，通过配置方式指定RibbonClientConfiguration的相关Bean

	@Autowired
	private Environment environment;

	private Map<Class, String> classToProperty = new HashMap<>(); // 属性缓存，在构造函数中进行初始化设置

	public PropertiesFactory() { // 初始化PropertiesFactory
		classToProperty.put(ILoadBalancer.class, "NFLoadBalancerClassName"); // 自定义ILoadBalancer实现类
		classToProperty.put(IPing.class, "NFLoadBalancerPingClassName"); // 自定义IPing实现类
		classToProperty.put(IRule.class, "NFLoadBalancerRuleClassName"); // 自定义IRule实现类
		classToProperty.put(ServerList.class, "NIWSServerListClassName"); // 自定义ServerList实现类
		classToProperty.put(ServerListFilter.class, "NIWSServerListFilterClassName"); // 自定义ServerListFilter实现类
	}

	public boolean isSet(Class clazz, String name) { // 判断Environment中是否有自定义属性
		return StringUtils.hasText(getClassName(clazz, name));
	}

	public String getClassName(Class clazz, String name) { // 从Environment中获取自定义属性的实现类名
		if (this.classToProperty.containsKey(clazz)) {
			String classNameProperty = this.classToProperty.get(clazz);
			String className = environment
					.getProperty(name + "." + NAMESPACE + "." + classNameProperty); // 从Environment中获取自定义属性名为"服务名.ribbon.属性名"的属性值
			return className;
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public <C> C get(Class<C> clazz, IClientConfig config, String name) {
		String className = getClassName(clazz, name);
		if (StringUtils.hasText(className)) {
			try {
				Class<?> toInstantiate = Class.forName(className);
				return (C) SpringClientFactory.instantiateWithConfig(toInstantiate,
						config);
			}
			catch (ClassNotFoundException e) {
				throw new IllegalArgumentException("Unknown class to load " + className
						+ " for class " + clazz + " named " + name);
			}
		}
		return null;
	}

}

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

package org.springframework.cloud.context.named;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.MapPropertySource;

/**
 * Creates a set of child contexts that allows a set of Specifications to define the beans
 * in each child context.
 *
 * Ported from spring-cloud-netflix FeignClientFactory and SpringClientFactory
 *
 * @param <C> specification
 * @author Spencer Gibb
 * @author Dave Syer
 */
// TODO: add javadoc
public abstract class NamedContextFactory<C extends NamedContextFactory.Specification> // 子容器工厂（在Ribbon、OpenFeign中进行了应用，每个子容器的数据、配置、Bean都是相互隔离、互不影响的）
		implements DisposableBean, ApplicationContextAware {

	private final String propertySourceName;

	private final String propertyName; // 子容器名的属性key（子容器可以通过读取该属性配置来获取容器名，如："ribbon.client.name"、"feign.client.name"）

	private Map<String, AnnotationConfigApplicationContext> contexts = new ConcurrentHashMap<>(); // 子容器缓存（防止重复创建）

	private Map<String, C> configurations = new ConcurrentHashMap<>(); // 子容器私有规范/配置实例缓存（转为了Map结构）

	private ApplicationContext parent; // 父容器

	private Class<?> defaultConfigType; // 子容器默认配置类（创建子容器时，子容器默认配置类一定会被加载）

	public NamedContextFactory(Class<?> defaultConfigType, String propertySourceName, // 创建NamedContextFactory（由子类调用）
			String propertyName) {
		this.defaultConfigType = defaultConfigType; // 传入子容器默认配置类
		this.propertySourceName = propertySourceName;
		this.propertyName = propertyName;
	}

	@Override
	public void setApplicationContext(ApplicationContext parent) throws BeansException { // 通过实现ApplicationContextAware接口注入ApplicationContext
		this.parent = parent;
	}

	public void setConfigurations(List<C> configurations) { // 设置子容器私有规范/配置，将其放入缓存
		for (C client : configurations) {
			this.configurations.put(client.getName(), client);
		}
	}

	public Set<String> getContextNames() { // 获取当前已经实例化子容器的名称列表
		return new HashSet<>(this.contexts.keySet());
	}

	@Override
	public void destroy() { // 通过实现DisposableBean接口销毁Bean
		Collection<AnnotationConfigApplicationContext> values = this.contexts.values();
		for (AnnotationConfigApplicationContext context : values) {
			// This can fail, but it never throws an exception (you see stack traces
			// logged as WARN).
			context.close();
		}
		this.contexts.clear();
	}

	protected AnnotationConfigApplicationContext getContext(String name) { // 从缓存中获取子容器（子容器不存在时进行创建）
		if (!this.contexts.containsKey(name)) { // 典型的双重检查锁机制
			synchronized (this.contexts) {
				if (!this.contexts.containsKey(name)) {
					this.contexts.put(name, createContext(name)); // 当缓存不存在时进行创建
				}
			}
		}
		return this.contexts.get(name); // 返回name对应的ApplicationContext子容器
	}

	protected AnnotationConfigApplicationContext createContext(String name) { // 创建子容器
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(); // 创建子容器对象
		if (this.configurations.containsKey(name)) { // 查看是否有对应的子容器私有配置
			for (Class<?> configuration : this.configurations.get(name) // 遍历子容器私有配置
					.getConfiguration()) {
				context.register(configuration); // 将子容器私有配置注册到子容器中
			}
		}
		for (Map.Entry<String, C> entry : this.configurations.entrySet()) { // 遍历子容器名称
			if (entry.getKey().startsWith("default.")) { // 检查是否有默认子容器
				for (Class<?> configuration : entry.getValue().getConfiguration()) {
					context.register(configuration); // 将默认子容器私有配置注册到子容器中
				}
			}
		}
		context.register(PropertyPlaceholderAutoConfiguration.class,
				this.defaultConfigType); // 注册默认配置类、PropertyPlaceholderAutoConfiguration类（是RibbonClientConfiguration、FeignClientsConfiguration的入口）
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
				this.propertySourceName,
				Collections.<String, Object>singletonMap(this.propertyName, name))); // 在子容器中添加PropertySources（子容器名的属性key、子容器名的属性value）
		if (this.parent != null) { // 父容器存在时
			// Uses Environment from parent as well as beans
			context.setParent(this.parent); // 设置父容器，当在子容器找不到时，会从父容器寻找
			// jdk11 issue
			// https://github.com/spring-cloud/spring-cloud-netflix/issues/3101
			context.setClassLoader(this.parent.getClassLoader());
		}
		context.setDisplayName(generateDisplayName(name));
		context.refresh(); // 调用子容器的refresh方法
		return context;
	}

	protected String generateDisplayName(String name) {
		return this.getClass().getSimpleName() + "-" + name;
	}

	public <T> T getInstance(String name, Class<T> type) { // 从子容器中获取指定类型的实例Bean
		AnnotationConfigApplicationContext context = getContext(name); // 从缓存中获取对应的ApplicationContext
		if (BeanFactoryUtils.beanNamesForTypeIncludingAncestors(context,
				type).length > 0) {
			return context.getBean(type); // 从子容器中获取指定类型的Bean实例，当子容器获取不到时从父容器中获取
		}
		return null;
	}

	public <T> ObjectProvider<T> getLazyProvider(String name, Class<T> type) {
		return new ClientFactoryObjectProvider<>(this, name, type);
	}

	public <T> ObjectProvider<T> getProvider(String name, Class<T> type) {
		AnnotationConfigApplicationContext context = getContext(name);
		return context.getBeanProvider(type);
	}

	public <T> T getInstance(String name, Class<?> clazz, Class<?>... generics) {
		ResolvableType type = ResolvableType.forClassWithGenerics(clazz, generics);
		return getInstance(name, type);
	}

	@SuppressWarnings("unchecked")
	public <T> T getInstance(String name, ResolvableType type) {
		AnnotationConfigApplicationContext context = getContext(name);
		String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(context,
				type);
		if (beanNames.length > 0) {
			for (String beanName : beanNames) {
				if (context.isTypeMatch(beanName, type)) {
					return (T) context.getBean(beanName);
				}
			}
		}
		return null;
	}

	public <T> Map<String, T> getInstances(String name, Class<T> type) {
		AnnotationConfigApplicationContext context = getContext(name);
		if (BeanFactoryUtils.beanNamesForTypeIncludingAncestors(context,
				type).length > 0) {
			return BeanFactoryUtils.beansOfTypeIncludingAncestors(context, type);
		}
		return null;
	}

	/**
	 * Specification with name and configuration.
	 */
	public interface Specification { // 子容器私有规范/配置（由子类实现，通过ImportBeanDefinitionRegistrar接口封装成实现类并创建BeanDefinition注册到Spring容器中）

		String getName(); // 子容器名称

		Class<?>[] getConfiguration(); // 子容器私有配置

	}

}

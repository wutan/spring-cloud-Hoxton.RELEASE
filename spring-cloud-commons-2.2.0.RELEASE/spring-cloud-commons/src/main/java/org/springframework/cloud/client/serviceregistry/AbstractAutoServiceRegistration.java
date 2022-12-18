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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PreDestroy;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeansException;
import org.springframework.boot.web.context.ConfigurableWebServerApplicationContext;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.cloud.client.discovery.ManagementServerPortUtils;
import org.springframework.cloud.client.discovery.event.InstancePreRegisteredEvent;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Lifecycle methods that may be useful and common to {@link ServiceRegistry}
 * implementations.
 *
 * TODO: Document the lifecycle.
 *
 * @param <R> Registration type passed to the {@link ServiceRegistry}.
 * @author Spencer Gibb
 */
public abstract class AbstractAutoServiceRegistration<R extends Registration> // 抽象的自动服务注册类（eureka并未遵循）
		implements AutoServiceRegistration, ApplicationContextAware,
		ApplicationListener<WebServerInitializedEvent> {

	private static final Log logger = LogFactory
			.getLog(AbstractAutoServiceRegistration.class);

	private final ServiceRegistry<R> serviceRegistry; // 服务注册实现类

	private boolean autoStartup = true;

	private AtomicBoolean running = new AtomicBoolean(false);

	private int order = 0;

	private ApplicationContext context;

	private Environment environment;

	private AtomicInteger port = new AtomicInteger(0);

	private AutoServiceRegistrationProperties properties;

	@Deprecated
	protected AbstractAutoServiceRegistration(ServiceRegistry<R> serviceRegistry) {
		this.serviceRegistry = serviceRegistry;
	}

	protected AbstractAutoServiceRegistration(ServiceRegistry<R> serviceRegistry, // 实例化AbstractAutoServiceRegistration
			AutoServiceRegistrationProperties properties) {
		this.serviceRegistry = serviceRegistry;
		this.properties = properties;
	}

	protected ApplicationContext getContext() {
		return this.context;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void onApplicationEvent(WebServerInitializedEvent event) { // 监听WebServerInitializedEvent事件（finishRefresh发布事件时调用）
		bind(event); // 处理WebServerInitializedEvent事件
	}

	@Deprecated
	public void bind(WebServerInitializedEvent event) { // 处理WebServerInitializedEvent事件
		ApplicationContext context = event.getApplicationContext();
		if (context instanceof ConfigurableWebServerApplicationContext) {
			if ("management".equals(((ConfigurableWebServerApplicationContext) context)
					.getServerNamespace())) {
				return;
			}
		}
		this.port.compareAndSet(0, event.getWebServer().getPort()); // 获取端口号并进行设置
		this.start(); // 开始自动注册
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext)
			throws BeansException {
		this.context = applicationContext;
		this.environment = this.context.getEnvironment();
	}

	@Deprecated
	protected Environment getEnvironment() {
		return this.environment;
	}

	@Deprecated
	protected AtomicInteger getPort() {
		return this.port;
	}

	public boolean isAutoStartup() {
		return this.autoStartup;
	}

	public void start() { // 开始自动注册
		if (!isEnabled()) { // 如果设置为不自动注册，则直接结束
			if (logger.isDebugEnabled()) {
				logger.debug("Discovery Lifecycle disabled. Not starting");
			}
			return;
		}

		// only initialize if nonSecurePort is greater than 0 and it isn't already running
		// because of containerPortInitializer below
		if (!this.running.get()) {
			this.context.publishEvent(
					new InstancePreRegisteredEvent(this, getRegistration())); // 发布InstancePreRegisteredEvent事件
			register(); // 注册（子类重写）
			if (shouldRegisterManagement()) {
				registerManagement();
			}
			this.context.publishEvent(
					new InstanceRegisteredEvent<>(this, getConfiguration())); // 发布InstanceRegisteredEvent事件
			this.running.compareAndSet(false, true);
		}

	}

	/**
	 * @return Whether the management service should be registered with the
	 * {@link ServiceRegistry}.
	 */
	protected boolean shouldRegisterManagement() {
		if (this.properties == null || this.properties.isRegisterManagement()) {
			return getManagementPort() != null
					&& ManagementServerPortUtils.isDifferent(this.context);
		}
		return false;
	}

	/**
	 * @return The object used to configure the registration.
	 */
	@Deprecated
	protected abstract Object getConfiguration();

	/**
	 * @return True, if this is enabled.
	 */
	protected abstract boolean isEnabled();

	/**
	 * @return The serviceId of the Management Service.
	 */
	@Deprecated
	protected String getManagementServiceId() {
		// TODO: configurable management suffix
		return this.context.getId() + ":management";
	}

	/**
	 * @return The service name of the Management Service.
	 */
	@Deprecated
	protected String getManagementServiceName() {
		// TODO: configurable management suffix
		return getAppName() + ":management";
	}

	/**
	 * @return The management server port.
	 */
	@Deprecated
	protected Integer getManagementPort() {
		return ManagementServerPortUtils.getPort(this.context);
	}

	/**
	 * @return The app name (currently the spring.application.name property).
	 */
	@Deprecated
	protected String getAppName() {
		return this.environment.getProperty("spring.application.name", "application");
	}

	@PreDestroy
	public void destroy() {
		stop();
	}

	public boolean isRunning() {
		return this.running.get();
	}

	protected AtomicBoolean getRunning() {
		return this.running;
	}

	public int getOrder() {
		return this.order;
	}

	public int getPhase() {
		return 0;
	}

	protected ServiceRegistry<R> getServiceRegistry() {
		return this.serviceRegistry;
	}

	protected abstract R getRegistration();

	protected abstract R getManagementRegistration();

	/**
	 * Register the local service with the {@link ServiceRegistry}.
	 */
	protected void register() { // 注册
		this.serviceRegistry.register(getRegistration()); // 委托给ServiceRegistry进行服务注册
	}

	/**
	 * Register the local management service with the {@link ServiceRegistry}.
	 */
	protected void registerManagement() {
		R registration = getManagementRegistration();
		if (registration != null) {
			this.serviceRegistry.register(registration);
		}
	}

	/**
	 * De-register the local service with the {@link ServiceRegistry}.
	 */
	protected void deregister() {
		this.serviceRegistry.deregister(getRegistration());
	}

	/**
	 * De-register the local management service with the {@link ServiceRegistry}.
	 */
	protected void deregisterManagement() {
		R registration = getManagementRegistration();
		if (registration != null) {
			this.serviceRegistry.deregister(registration);
		}
	}

	public void stop() {
		if (this.getRunning().compareAndSet(true, false) && isEnabled()) {
			deregister();
			if (shouldRegisterManagement()) {
				deregisterManagement();
			}
			this.serviceRegistry.close();
		}
	}

}

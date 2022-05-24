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

package org.springframework.cloud.netflix.eureka.server;

import javax.servlet.ServletContext;

import com.netflix.eureka.EurekaServerConfig;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.netflix.eureka.server.event.EurekaRegistryAvailableEvent;
import org.springframework.cloud.netflix.eureka.server.event.EurekaServerStartedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.context.ServletContextAware;

/**
 * @author Dave Syer
 */
@Configuration(proxyBeanMethods = false)
public class EurekaServerInitializerConfiguration
		implements ServletContextAware, SmartLifecycle, Ordered { // 由EurekaServerAutoConfiguration导入，实现了SmartLifecycle接口，Spring容器初始化完毕后会回调start方法

	private static final Log log = LogFactory
			.getLog(EurekaServerInitializerConfiguration.class);

	@Autowired
	private EurekaServerConfig eurekaServerConfig;

	private ServletContext servletContext;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private EurekaServerBootstrap eurekaServerBootstrap;

	private boolean running;

	private int order = 1;

	@Override
	public void setServletContext(ServletContext servletContext) { // 实现ServletContextAware接口用于注入ServletContext，用于添加Eureka Server上下文属性
		this.servletContext = servletContext;
	}

	@Override
	public void start() {
		new Thread(() -> { // 异步执行
			try {
				// TODO: is this class even needed now?
				eurekaServerBootstrap.contextInitialized(
						EurekaServerInitializerConfiguration.this.servletContext); // 初始化Eureka Server并启动
				log.info("Started Eureka Server");

				publish(new EurekaRegistryAvailableEvent(getEurekaServerConfig())); // 发布Eureka Server的register注册事件
				EurekaServerInitializerConfiguration.this.running = true; // 设置启动的状态为true
				publish(new EurekaServerStartedEvent(getEurekaServerConfig())); // 发送Eureka Server的Start启动事件
			}
			catch (Exception ex) {
				// Help!
				log.error("Could not initialize Eureka servlet context", ex);
			}
		}).start();
	}

	private EurekaServerConfig getEurekaServerConfig() { // 获取Eureka Server端的配置，用于事件发布
		return this.eurekaServerConfig;
	}

	private void publish(ApplicationEvent event) { // 发布事件
		this.applicationContext.publishEvent(event);
	}

	@Override
	public void stop() {
		this.running = false;
		eurekaServerBootstrap.contextDestroyed(this.servletContext);
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

	@Override
	public int getPhase() {
		return 0;
	}

	@Override
	public boolean isAutoStartup() {
		return true;
	}

	@Override
	public void stop(Runnable callback) {
		callback.run();
	}

	@Override
	public int getOrder() {
		return this.order;
	}

}

/*
 * Copyright 2017-2019 the original author or authors.
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

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Responsible for eagerly creating the child application context holding the Ribbon
 * related configuration.
 *
 * @author Biju Kunjummen
 */
public class RibbonApplicationContextInitializer
		implements ApplicationListener<ApplicationReadyEvent> { // 监听ApplicationReadyEvent事件

	private final SpringClientFactory springClientFactory; // Ribbon子容器工厂

	// List of Ribbon client names
	private final List<String> clientNames; // 要饥饿加载的Ribbon客户端列表（从RibbonEagerLoadProperties配置类中进行注入）

	public RibbonApplicationContextInitializer(SpringClientFactory springClientFactory, // 初始化RibbonApplicationContextInitializer
			List<String> clientNames) {
		this.springClientFactory = springClientFactory;
		this.clientNames = clientNames;
	}

	protected void initialize() {
		if (clientNames != null) { // 当要饥饿加载的Ribbon客户端列表不为空时
			for (String clientName : clientNames) {
				this.springClientFactory.getContext(clientName); // 立即初始化Ribbon子容器
			}
		}
	}

	@Override
	public void onApplicationEvent(ApplicationReadyEvent event) {
		initialize(); // 消费ApplicationReadyEvent事件
	}

}

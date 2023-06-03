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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sleuth Redis properties.
 *
 * @author Daniel Albuquerque
 */
@ConfigurationProperties("spring.sleuth.redis")
public class TraceRedisProperties {

	/**
	 * Enable span information propagation when using Redis.
	 */
	private boolean enabled = true;

	/**
	 * Service name for the remote Redis endpoint.
	 */
	private String remoteServiceName = "redis";

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getRemoteServiceName() {
		return remoteServiceName;
	}

	public void setRemoteServiceName(String remoteServiceName) {
		this.remoteServiceName = remoteServiceName;
	}

}

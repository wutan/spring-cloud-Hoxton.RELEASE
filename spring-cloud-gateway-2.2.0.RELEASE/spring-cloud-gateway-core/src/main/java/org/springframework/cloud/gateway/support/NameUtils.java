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

package org.springframework.cloud.gateway.support;

import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;

/**
 * @author Spencer Gibb
 */
public final class NameUtils {

	private NameUtils() {
		throw new AssertionError("Must not instantiate utility class.");
	}

	/**
	 * Generated name prefix.
	 */
	public static final String GENERATED_NAME_PREFIX = "_genkey_";

	public static String generateName(int i) { // 给断言工厂/路由工厂生成属性名
		return GENERATED_NAME_PREFIX + i;
	}

	public static String normalizeRoutePredicateName( // 获取路由断言工厂前缀名称（当前简单类名去除RoutePredicateFactory后的名称）
			Class<? extends RoutePredicateFactory> clazz) {
		return removeGarbage(clazz.getSimpleName()
				.replace(RoutePredicateFactory.class.getSimpleName(), "")); // 获取路由断言工厂前缀名称（当前简单类名去除RoutePredicateFactory后的名称）
	}

	public static String normalizeFilterFactoryName( // 获取路由过滤工厂前缀名称（当前简单类名去除GatewayFilterFactory后的名称）
			Class<? extends GatewayFilterFactory> clazz) {
		return removeGarbage(clazz.getSimpleName()
				.replace(GatewayFilterFactory.class.getSimpleName(), "")); // 获取路由过滤工厂前缀名称（当前简单类名去除GatewayFilterFactory后的名称）
	}

	private static String removeGarbage(String s) {
		int garbageIdx = s.indexOf("$Mockito");
		if (garbageIdx > 0) {
			return s.substring(0, garbageIdx);
		}

		return s;
	}

}

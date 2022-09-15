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

package org.springframework.cloud.openfeign;

import feign.Feign;
import feign.Target;
import feign.hystrix.FallbackFactory;
import feign.hystrix.HystrixFeign;
import feign.hystrix.SetterFactory;

import org.springframework.util.StringUtils;

/**
 * @author Spencer Gibb
 * @author Erik Kringen
 */
@SuppressWarnings("unchecked")
class HystrixTargeter implements Targeter { // HystrixTargeter，在FeignAutoConfiguration中默认符合条件会进行创建

	@Override
	public <T> T target(FeignClientFactoryBean factory, Feign.Builder feign, // 生成整合了Hystrix且有负载均衡功能的Feign客户端代理类
			FeignContext context, Target.HardCodedTarget<T> target) {
		if (!(feign instanceof feign.hystrix.HystrixFeign.Builder)) { // 判断是否为HystrixFeign.Builder类型（由于feign.hystrix.enabled属性默认为false，所以Feign.Builder类型默认为feign.Feign.Builder）
			return feign.target(target); // 默认调用Feign.Builder#target(feign.Target<T>)方法，不走下面逻辑
		}
		feign.hystrix.HystrixFeign.Builder builder = (feign.hystrix.HystrixFeign.Builder) feign;
		String name = StringUtils.isEmpty(factory.getContextId()) ? factory.getName()
				: factory.getContextId();
		SetterFactory setterFactory = getOptional(name, context, SetterFactory.class);
		if (setterFactory != null) {
			builder.setterFactory(setterFactory);
		}
		Class<?> fallback = factory.getFallback(); // 获取@FeignClient注解中的fallback属性对应的服务降级类
		if (fallback != void.class) {
			return targetWithFallback(name, context, target, builder, fallback);
		}
		Class<?> fallbackFactory = factory.getFallbackFactory(); // 获取@FeignClient注解中的fallbackFactory属性对应的服务降级工厂类
		if (fallbackFactory != void.class) {
			return targetWithFallbackFactory(name, context, target, builder,
					fallbackFactory);
		}

		return feign.target(target); // 调用父类Feign.Builder#target(feign.Target<T>)方法生成代理类
	}

	private <T> T targetWithFallbackFactory(String feignClientName, FeignContext context, // 生成带有Hystrix服务降级工厂类的代理对象
			Target.HardCodedTarget<T> target, HystrixFeign.Builder builder,
			Class<?> fallbackFactoryClass) {
		FallbackFactory<? extends T> fallbackFactory = (FallbackFactory<? extends T>) getFromContext(
				"fallbackFactory", feignClientName, context, fallbackFactoryClass,
				FallbackFactory.class);
		return builder.target(target, fallbackFactory); // 通过服务降级工厂类生成带有Hystrix服务降级类的代理对象HystrixInvocationHandler
	}

	private <T> T targetWithFallback(String feignClientName, FeignContext context, // 生成带有Hystrix服务降级类的代理对象
			Target.HardCodedTarget<T> target, HystrixFeign.Builder builder,
			Class<?> fallback) {
		T fallbackInstance = getFromContext("fallback", feignClientName, context,
				fallback, target.type());
		return builder.target(target, fallbackInstance); // 通过服务降级类生成带有Hystrix服务降级类的代理对象HystrixInvocationHandler
	}

	private <T> T getFromContext(String fallbackMechanism, String feignClientName,
			FeignContext context, Class<?> beanType, Class<T> targetType) {
		Object fallbackInstance = context.getInstance(feignClientName, beanType); // 从上下文中获取服务降级类的实例对象，所以服务降级类必须添加@Component注解提前注册到BeanDefinition中
		if (fallbackInstance == null) {
			throw new IllegalStateException(String.format(
					"No " + fallbackMechanism
							+ " instance of type %s found for feign client %s",
					beanType, feignClientName));
		}

		if (!targetType.isAssignableFrom(beanType)) {
			throw new IllegalStateException(String.format("Incompatible "
					+ fallbackMechanism
					+ " instance. Fallback/fallbackFactory of type %s is not assignable to %s for feign client %s",
					beanType, targetType, feignClientName));
		}
		return (T) fallbackInstance;
	}

	private <T> T getOptional(String feignClientName, FeignContext context,
			Class<T> beanType) {
		return context.getInstance(feignClientName, beanType);
	}

}

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

import java.util.Map;
import java.util.Objects;

import feign.Client;
import feign.Contract;
import feign.Feign;
import feign.Logger;
import feign.QueryMapEncoder;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.Target.HardCodedTarget;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.cloud.openfeign.ribbon.LoadBalancerFeignClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * @author Spencer Gibb
 * @author Venil Noronha
 * @author Eko Kurniawan Khannedy
 * @author Gregor Zurowski
 */
class FeignClientFactoryBean
		implements FactoryBean<Object>, InitializingBean, ApplicationContextAware {

	/***********************************
	 * WARNING! Nothing in this class should be @Autowired. It causes NPEs because of some
	 * lifecycle race condition.
	 ***********************************/
	// 下面的八个属性是在FeignClientsRegistrar类中通过封装BeanDefinition进行定义的
	private Class<?> type;

	private String name; // 服务名称

	private String url; // 服务请求地址（不一定有值）

	private String contextId;

	private String path;

	private boolean decode404;

	private ApplicationContext applicationContext; // 由ApplicationContextAware接口进行注入

	private Class<?> fallback = void.class;

	private Class<?> fallbackFactory = void.class;

	@Override
	public void afterPropertiesSet() throws Exception { // 检查contextId、name属性值是否为空，即检查@FeignClient相关注解是否定义合理
		Assert.hasText(this.contextId, "Context id must be set");
		Assert.hasText(this.name, "Name must be set");
	}

	protected Feign.Builder feign(FeignContext context) { // 根据Feign的子容器工厂构建Feign.Builder
		FeignLoggerFactory loggerFactory = get(context, FeignLoggerFactory.class); // 从子容器中获取FeignLoggerFactory
		Logger logger = loggerFactory.create(this.type);

		// @formatter:off
		Feign.Builder builder = get(context, Feign.Builder.class) // 从子容器中获取Feign.Builder对象（默认为Feign.Builder，默认的重试机制为不重试）
				// required values
				.logger(logger) // 设置日志
				.encoder(get(context, Encoder.class)) // 从子容器中获取编码器并设置到Feign.Builder中
				.decoder(get(context, Decoder.class)) // 从子容器中获取解码器并设置到Feign.Builder中
				.contract(get(context, Contract.class)); // 从子容器中获取锲约并设置到Feign.Builder中
		// @formatter:on

		configureFeign(context, builder); // 从上下文、默认/全局属性配置、实例属性配置中设置到Feign.Builder中

		return builder;
	}

	protected void configureFeign(FeignContext context, Feign.Builder builder) { // 从上下文、默认配置、自定义配置中设置到Feign.Builder中（优先级：实例>全局，属性>代码）
		FeignClientProperties properties = this.applicationContext // 获取Feign客户端的属性配置类
				.getBean(FeignClientProperties.class);
		if (properties != null) {
			if (properties.isDefaultToProperties()) { // 默认为ture，属性优先（可设置feign.client.default-to-properties为false，使得代码配置优先）
				configureUsingConfiguration(context, builder); // 从上下文中设置Feign.Builder属性
				configureUsingProperties( // 从默认/全局属性配置中设置Feign.Builder属性
						properties.getConfig().get(properties.getDefaultConfig()),
						builder);
				configureUsingProperties(properties.getConfig().get(this.contextId), // 从实例属性配置中设置Feign.Builder属性
						builder);
			}
			else {
				configureUsingProperties( // 从默认/全局属性配置中设置Feign.Builder属性
						properties.getConfig().get(properties.getDefaultConfig()),
						builder);
				configureUsingProperties(properties.getConfig().get(this.contextId), // 从实例属性配置中设置Feign.Builder属性
						builder);
				configureUsingConfiguration(context, builder); // 从上下文中设置Feign.Builder属性
			}
		}
		else {
			configureUsingConfiguration(context, builder); // 从上下文中设置Feign.Builder属性
		}
	}

	protected void configureUsingConfiguration(FeignContext context, // 从上下文中设置FeignClient属性
			Feign.Builder builder) {
		Logger.Level level = getOptional(context, Logger.Level.class);
		if (level != null) {
			builder.logLevel(level); // 从上下文中设置日志
		}
		Retryer retryer = getOptional(context, Retryer.class);
		if (retryer != null) {
			builder.retryer(retryer); // 从上下文中设置重试策略
		}
		ErrorDecoder errorDecoder = getOptional(context, ErrorDecoder.class);
		if (errorDecoder != null) {
			builder.errorDecoder(errorDecoder); // 从上下文中设置异常解码器
		}
		Request.Options options = getOptional(context, Request.Options.class);
		if (options != null) {
			builder.options(options); // 从上下文中设置超时时间（连接超时和读取超时时间，默认会获取FeignRibbonClientAutoConfiguration#feignRequestOptions创建的全局Option）
		}
		Map<String, RequestInterceptor> requestInterceptors = context
				.getInstances(this.contextId, RequestInterceptor.class); // 从上下文中获取拦截器Map集合
		if (requestInterceptors != null) {
			builder.requestInterceptors(requestInterceptors.values()); // 添加拦截器
		}
		QueryMapEncoder queryMapEncoder = getOptional(context, QueryMapEncoder.class);
		if (queryMapEncoder != null) {
			builder.queryMapEncoder(queryMapEncoder);
		}
		if (this.decode404) {
			builder.decode404();
		}
	}

	protected void configureUsingProperties( // 从属性配置中设置FeignClient属性
			FeignClientProperties.FeignClientConfiguration config,
			Feign.Builder builder) {
		if (config == null) {
			return;
		}

		if (config.getLoggerLevel() != null) {
			builder.logLevel(config.getLoggerLevel()); // 从属性配置中设置日志
		}

		if (config.getConnectTimeout() != null && config.getReadTimeout() != null) {
			builder.options(new Request.Options(config.getConnectTimeout(), // 从属性配置中设置超时时间（连接超时和读取超时时间）
					config.getReadTimeout()));
		}

		if (config.getRetryer() != null) {
			Retryer retryer = getOrInstantiate(config.getRetryer());
			builder.retryer(retryer); // 从属性配置中设置重试策略
		}

		if (config.getErrorDecoder() != null) {
			ErrorDecoder errorDecoder = getOrInstantiate(config.getErrorDecoder());
			builder.errorDecoder(errorDecoder); // 从属性配置中设置异常解码器
		}

		if (config.getRequestInterceptors() != null
				&& !config.getRequestInterceptors().isEmpty()) {
			// this will add request interceptor to builder, not replace existing
			for (Class<RequestInterceptor> bean : config.getRequestInterceptors()) {
				RequestInterceptor interceptor = getOrInstantiate(bean);
				builder.requestInterceptor(interceptor); // 添加拦截器
			}
		}

		if (config.getDecode404() != null) {
			if (config.getDecode404()) {
				builder.decode404();
			}
		}

		if (Objects.nonNull(config.getEncoder())) {
			builder.encoder(getOrInstantiate(config.getEncoder()));
		}

		if (Objects.nonNull(config.getDecoder())) {
			builder.decoder(getOrInstantiate(config.getDecoder()));
		}

		if (Objects.nonNull(config.getContract())) {
			builder.contract(getOrInstantiate(config.getContract()));
		}
	}

	private <T> T getOrInstantiate(Class<T> tClass) {
		try {
			return this.applicationContext.getBean(tClass);
		}
		catch (NoSuchBeanDefinitionException e) {
			return BeanUtils.instantiateClass(tClass);
		}
	}

	protected <T> T get(FeignContext context, Class<T> type) {
		T instance = context.getInstance(this.contextId, type);
		if (instance == null) {
			throw new IllegalStateException(
					"No bean found of type " + type + " for " + this.contextId);
		}
		return instance;
	}

	protected <T> T getOptional(FeignContext context, Class<T> type) {
		return context.getInstance(this.contextId, type);
	}

	protected <T> T loadBalance(Feign.Builder builder, FeignContext context,
			HardCodedTarget<T> target) {
		Client client = getOptional(context, Client.class); // 从上下文中获取一个Client，默认是LoadBalancerFeignClient（整合Ribbon的入口）（在FeignRibbonClientAutoConfiguration自动装配类中通过Import实现的注入）
		if (client != null) {
			builder.client(client); // 将Client包装到Feign.Builder中
			Targeter targeter = get(context, Targeter.class);
			return targeter.target(this, builder, context, target); // 默认调用HystrixTargeter#target方法（整合Hystrix的入口）
		}

		throw new IllegalStateException(
				"No Feign Client for loadBalancing defined. Did you forget to include spring-cloud-starter-netflix-ribbon?");
	}

	@Override
	public Object getObject() throws Exception { // 基于FactoryBean接口创建复杂Bean
		return getTarget(); // 生成FeignCliet的代理对象
	}

	/**
	 * @param <T> the target type of the Feign client
	 * @return a {@link Feign} client created with the specified data and the context
	 * information
	 */
	<T> T getTarget() { // 生成FeignCliet的代理对象
		FeignContext context = this.applicationContext.getBean(FeignContext.class); // 从ApplicationContext中获取Feign的上下文对象FeignContext，即Feign的子容器工厂（在FeignAutoConfiguration注入）
		Feign.Builder builder = feign(context); // 从子容器中获取Feign.Builder对象（默认为Feign.Builder），并设置相关信息

		if (!StringUtils.hasText(this.url)) { // 当url为空时，将Feign.Builder的client属性设置为具有负载均衡功能的客户端LoadBalancerFeignClient，反之则设置为第三方客户端
			if (!this.name.startsWith("http")) {
				this.url = "http://" + this.name; // 一般情况下是没有指定url的，会添加一个http://前缀
			}
			else {
				this.url = this.name;
			}
			this.url += cleanPath();
			return (T) loadBalance(builder, context, // 先从子容器中获取具有负载均衡的客户端（默认为LoadBalancerFeignClient），并设置到Feign.Builder中，再调用Target.target()方法生成代理类
					new HardCodedTarget<>(this.type, this.name, this.url));
		}
		if (StringUtils.hasText(this.url) && !this.url.startsWith("http")) { // 当为指定url时，则生成不具有负载均衡功能的客户端代理类，即Feign.Builder中client属性为第三方Client
			this.url = "http://" + this.url;
		}
		String url = this.url + cleanPath();
		Client client = getOptional(context, Client.class); // 从子容器中获取Client实现类（默认为LoadBalancerFeignClient）
		if (client != null) {
			if (client instanceof LoadBalancerFeignClient) {
				// not load balancing because we have a url,
				// but ribbon is on the classpath, so unwrap
				client = ((LoadBalancerFeignClient) client).getDelegate(); // 获取LoadBalancerFeignClient中维护的第三方客户端（默认为Client.Default）
			}
			builder.client(client); // 将子容器中的第三方客户端设置到Feign.Builder中
		}
		Targeter targeter = get(context, Targeter.class); // 从子容器中获取Targeter实现类（默认为HystrixTargeter）
		return (T) targeter.target(this, builder, context, // 调用Target.target()方法生成代理类
				new HardCodedTarget<>(this.type, this.name, url));
	}

	private String cleanPath() {
		String path = this.path.trim();
		if (StringUtils.hasLength(path)) {
			if (!path.startsWith("/")) {
				path = "/" + path;
			}
			if (path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
		}
		return path;
	}

	@Override
	public Class<?> getObjectType() {
		return this.type;
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

	public Class<?> getType() {
		return this.type;
	}

	public void setType(Class<?> type) {
		this.type = type;
	}

	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getContextId() {
		return this.contextId;
	}

	public void setContextId(String contextId) {
		this.contextId = contextId;
	}

	public String getUrl() {
		return this.url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getPath() {
		return this.path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public boolean isDecode404() {
		return this.decode404;
	}

	public void setDecode404(boolean decode404) {
		this.decode404 = decode404;
	}

	public ApplicationContext getApplicationContext() {
		return this.applicationContext;
	}

	@Override
	public void setApplicationContext(ApplicationContext context) throws BeansException {
		this.applicationContext = context;
	}

	public Class<?> getFallback() {
		return this.fallback;
	}

	public void setFallback(Class<?> fallback) {
		this.fallback = fallback;
	}

	public Class<?> getFallbackFactory() {
		return this.fallbackFactory;
	}

	public void setFallbackFactory(Class<?> fallbackFactory) {
		this.fallbackFactory = fallbackFactory;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		FeignClientFactoryBean that = (FeignClientFactoryBean) o;
		return Objects.equals(this.applicationContext, that.applicationContext)
				&& this.decode404 == that.decode404
				&& Objects.equals(this.fallback, that.fallback)
				&& Objects.equals(this.fallbackFactory, that.fallbackFactory)
				&& Objects.equals(this.name, that.name)
				&& Objects.equals(this.path, that.path)
				&& Objects.equals(this.type, that.type)
				&& Objects.equals(this.url, that.url);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.applicationContext, this.decode404, this.fallback,
				this.fallbackFactory, this.name, this.path, this.type, this.url);
	}

	@Override
	public String toString() {
		return new StringBuilder("FeignClientFactoryBean{").append("type=")
				.append(this.type).append(", ").append("name='").append(this.name)
				.append("', ").append("url='").append(this.url).append("', ")
				.append("path='").append(this.path).append("', ").append("decode404=")
				.append(this.decode404).append(", ").append("applicationContext=")
				.append(this.applicationContext).append(", ").append("fallback=")
				.append(this.fallback).append(", ").append("fallbackFactory=")
				.append(this.fallbackFactory).append("}").toString();
	}

}

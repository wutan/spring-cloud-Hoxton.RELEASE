/**
 * Copyright 2012-2019 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import feign.InvocationHandlerFactory.MethodHandler;
import feign.Request.Options;
import feign.codec.DecodeException;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import static feign.ExceptionPropagationPolicy.UNWRAP;
import static feign.FeignException.errorExecuting;
import static feign.FeignException.errorReading;
import static feign.Util.checkNotNull;
import static feign.Util.ensureClosed;

final class SynchronousMethodHandler implements MethodHandler {

  private static final long MAX_RESPONSE_BUFFER_SIZE = 8192L;

  private final MethodMetadata metadata;
  private final Target<?> target;
  private final Client client;
  private final Retryer retryer;
  private final List<RequestInterceptor> requestInterceptors;
  private final Logger logger;
  private final Logger.Level logLevel;
  private final RequestTemplate.Factory buildTemplateFromArgs;
  private final Options options;
  private final Decoder decoder;
  private final ErrorDecoder errorDecoder;
  private final boolean decode404;
  private final boolean closeAfterDecode;
  private final ExceptionPropagationPolicy propagationPolicy;

  private SynchronousMethodHandler(Target<?> target, Client client, Retryer retryer, // 创建SynchronousMethodHandler（在SynchronousMethodHandler.Factory#create中进行创建，入参属性的优先级：默认属性<上下文容器属性<全局配置属性<实例配置属性）
      List<RequestInterceptor> requestInterceptors, Logger logger,
      Logger.Level logLevel, MethodMetadata metadata,
      RequestTemplate.Factory buildTemplateFromArgs, Options options,
      Decoder decoder, ErrorDecoder errorDecoder, boolean decode404,
      boolean closeAfterDecode, ExceptionPropagationPolicy propagationPolicy) {
    this.target = checkNotNull(target, "target");
    this.client = checkNotNull(client, "client for %s", target);
    this.retryer = checkNotNull(retryer, "retryer for %s", target);
    this.requestInterceptors =
        checkNotNull(requestInterceptors, "requestInterceptors for %s", target);
    this.logger = checkNotNull(logger, "logger for %s", target);
    this.logLevel = checkNotNull(logLevel, "logLevel for %s", target);
    this.metadata = checkNotNull(metadata, "metadata for %s", target);
    this.buildTemplateFromArgs = checkNotNull(buildTemplateFromArgs, "metadata for %s", target);
    this.options = checkNotNull(options, "options for %s", target);
    this.errorDecoder = checkNotNull(errorDecoder, "errorDecoder for %s", target);
    this.decoder = checkNotNull(decoder, "decoder for %s", target);
    this.decode404 = decode404;
    this.closeAfterDecode = closeAfterDecode;
    this.propagationPolicy = propagationPolicy;
  }

  @Override
  public Object invoke(Object[] argv) throws Throwable { // 执行FeignClient具体的代理方法逻辑（在ReflectiveFeign.FeignInvocationHandler#invoke方法中进行调用）
    RequestTemplate template = buildTemplateFromArgs.create(argv); // 根据请求参数解析生成RequestTemplate用于请求，该对象是Feign的Http请求模版
    Options options = findOptions(argv); // 从请求参数获取Options超时设置（优先级：默认属性<上下文容器属性<全局配置属性<实例配置属性<方法属性）
    Retryer retryer = this.retryer.clone(); // 构建Retryer重试机制，Retryer不是线程安全的对象，所以每一次方法调用我们都需要借助于原型模式来生成一个新的对象，默认对象为feign.Retryer.NEVER_RETRY
    while (true) {
      try {
        return executeAndDecode(template, options); // 先通过RequestTemplate生成Request请求对象，再调用Client对象发起请求获取response响应信息并进行解析
      } catch (RetryableException e) {
        try {
          retryer.continueOrPropagate(e);
        } catch (RetryableException th) {
          Throwable cause = th.getCause();
          if (propagationPolicy == UNWRAP && cause != null) {
            throw cause;
          } else {
            throw th;
          }
        }
        if (logLevel != Logger.Level.NONE) {
          logger.logRetry(metadata.configKey(), logLevel);
        }
        continue;
      }
    }
  }

  Object executeAndDecode(RequestTemplate template, Options options) throws Throwable {
    Request request = targetRequest(template); // 通过RequestTemplate生成Request请求对象，转化为Http请求报文（在生成Request请求对象前，会调用Feign的拦截器进行拦截处理）

    if (logLevel != Logger.Level.NONE) { // 判断日志等级是否输出日志
      logger.logRequest(metadata.configKey(), logLevel, request);
    }

    Response response;
    long start = System.nanoTime(); // 获取执行请求的开始时间
    try {
      response = client.execute(request, options); // 调用Client对象发起请求，默认的负载均衡客户端为LoadBalancerFeignClient、默认的第三方客户端为feign.Client.Default
    } catch (IOException e) { // 只抓IO异常
      if (logLevel != Logger.Level.NONE) {
        logger.logIOException(metadata.configKey(), logLevel, e, elapsedTime(start));
      }
      throw errorExecuting(request, e);
    }
    long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); // 统计请求调用花费的时间

    boolean shouldClose = true;
    try {
      if (logLevel != Logger.Level.NONE) {
        response =
            logger.logAndRebufferResponse(metadata.configKey(), logLevel, response, elapsedTime);
      }
      if (Response.class == metadata.returnType()) {
        if (response.body() == null) {
          return response;
        }
        if (response.body().length() == null ||
            response.body().length() > MAX_RESPONSE_BUFFER_SIZE) {
          shouldClose = false;
          return response;
        }
        // Ensure the response body is disconnected
        byte[] bodyData = Util.toByteArray(response.body().asInputStream());
        return response.toBuilder().body(bodyData).build();
      }
      if (response.status() >= 200 && response.status() < 300) { // 调用decode()进行解码，其中会对200、404等情况使用不同的接码器
        if (void.class == metadata.returnType()) {
          return null;
        } else {
          Object result = decode(response); // 解码操作
          shouldClose = closeAfterDecode;
          return result;
        }
      } else if (decode404 && response.status() == 404 && void.class != metadata.returnType()) {
        Object result = decode(response);
        shouldClose = closeAfterDecode;
        return result;
      } else {
        throw errorDecoder.decode(metadata.configKey(), response);
      }
    } catch (IOException e) {
      if (logLevel != Logger.Level.NONE) {
        logger.logIOException(metadata.configKey(), logLevel, e, elapsedTime);
      }
      throw errorReading(request, response, e);
    } finally {
      if (shouldClose) { // 关闭response，因为response可能是流操作，比如下载
        ensureClosed(response.body());
      }
    }
  }

  long elapsedTime(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  Request targetRequest(RequestTemplate template) {
    for (RequestInterceptor interceptor : requestInterceptors) {
      interceptor.apply(template);
    }
    return target.apply(template);
  }

  Object decode(Response response) throws Throwable {
    try {
      return decoder.decode(response, metadata.returnType());
    } catch (FeignException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new DecodeException(response.status(), e.getMessage(), response.request(), e);
    }
  }

  Options findOptions(Object[] argv) { // 获取超时设置
    if (argv == null || argv.length == 0) { // 当请求参数为空或不为Options类型时返回之前的超时设置（允许方法级别的覆盖超时设置）
      return this.options;
    }
    return (Options) Stream.of(argv)
        .filter(o -> o instanceof Options)
        .findFirst()
        .orElse(this.options);
  }

  static class Factory { // 用于创建一个SynchronousMethodHandler对象

    private final Client client;
    private final Retryer retryer;
    private final List<RequestInterceptor> requestInterceptors;
    private final Logger logger;
    private final Logger.Level logLevel;
    private final boolean decode404;
    private final boolean closeAfterDecode;
    private final ExceptionPropagationPolicy propagationPolicy;

    Factory(Client client, Retryer retryer, List<RequestInterceptor> requestInterceptors, // 初始化SynchronousMethodHandler.Factory（在Builder#build中进行创建，入参属性的优先级：默认属性<上下文容器属性<全局配置属性<实例配置属性）
        Logger logger, Logger.Level logLevel, boolean decode404, boolean closeAfterDecode,
        ExceptionPropagationPolicy propagationPolicy) {
      this.client = checkNotNull(client, "client");
      this.retryer = checkNotNull(retryer, "retryer");
      this.requestInterceptors = checkNotNull(requestInterceptors, "requestInterceptors");
      this.logger = checkNotNull(logger, "logger");
      this.logLevel = checkNotNull(logLevel, "logLevel");
      this.decode404 = decode404;
      this.closeAfterDecode = closeAfterDecode;
      this.propagationPolicy = propagationPolicy;
    }

    public MethodHandler create(Target<?> target, // 创建MethodHandler实例（SynchronousMethodHandler）
                                MethodMetadata md,
                                RequestTemplate.Factory buildTemplateFromArgs,
                                Options options,
                                Decoder decoder,
                                ErrorDecoder errorDecoder) {
      return new SynchronousMethodHandler(target, client, retryer, requestInterceptors, logger, // 创建SynchronousMethodHandler
          logLevel, md, buildTemplateFromArgs, options, decoder,
          errorDecoder, decode404, closeAfterDecode, propagationPolicy);
    }
  }
}

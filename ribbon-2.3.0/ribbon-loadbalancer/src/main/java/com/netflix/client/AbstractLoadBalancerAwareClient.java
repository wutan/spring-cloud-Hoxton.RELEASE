/*
*
* Copyright 2013 Netflix, Inc.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*/
package com.netflix.client;

import java.net.URI;

import rx.Observable;

import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.AvailabilityFilteringRule;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.LoadBalancerContext;
import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.reactive.LoadBalancerCommand;
import com.netflix.loadbalancer.reactive.ServerOperation;

/**
 * Abstract class that provides the integration of client with load balancers.
 * 
 * @author awang
 *
 */
public abstract class AbstractLoadBalancerAwareClient<S extends ClientRequest, T extends IResponse> extends LoadBalancerContext implements IClient<S, T>, IClientConfigAware {
    
    public AbstractLoadBalancerAwareClient(ILoadBalancer lb) {
        super(lb);
    }
    
    /**
     * Delegate to {@link #initWithNiwsConfig(IClientConfig)}
     * @param clientConfig
     */
    public AbstractLoadBalancerAwareClient(ILoadBalancer lb, IClientConfig clientConfig) {
        super(lb, clientConfig);        
    }
    
    /**
     * Determine if an exception should contribute to circuit breaker trip. If such exceptions happen consecutively
     * on a server, it will be deemed as circuit breaker tripped and enter into a time out when it will be
     * skipped by the {@link AvailabilityFilteringRule}, which is the default rule for load balancers.
     */
    @Deprecated
    protected boolean isCircuitBreakerException(Throwable e) {
        if (getRetryHandler() != null) {
            return getRetryHandler().isCircuitTrippingException(e);
        }
        return false;
    }
        
    /**
     * Determine if operation can be retried if an exception is thrown. For example, connect 
     * timeout related exceptions
     * are typically retriable.
     * 
     */
    @Deprecated
    protected boolean isRetriableException(Throwable e) {
        if (getRetryHandler() != null) {
            return getRetryHandler().isRetriableException(e, true);
        } 
        return false;
    }
    
    public T executeWithLoadBalancer(S request) throws ClientException {
        return executeWithLoadBalancer(request, null);
    }

    /**
     * This method should be used when the caller wants to dispatch the request to a server chosen by
     * the load balancer, instead of specifying the server in the request's URI. 
     * It calculates the final URI by calling {@link #reconstructURIWithServer(com.netflix.loadbalancer.Server, java.net.URI)}
     * and then calls {@link #executeWithLoadBalancer(ClientRequest, com.netflix.client.config.IClientConfig)}.
     * 
     * @param request request to be dispatched to a server chosen by the load balancer. The URI can be a partial
     * URI which does not contain the host name or the protocol.
     */
    public T executeWithLoadBalancer(final S request, final IClientConfig requestConfig) throws ClientException { // 根据负载均衡执行请求
        LoadBalancerCommand<T> command = buildLoadBalancerCommand(request, requestConfig); // 构建LoadBalancerCommand对象

        try {
            return command.submit( // 执行submit方法，调用Ribbon获取服务实例
                new ServerOperation<T>() {
                    @Override
                    public Observable<T> call(Server server) {
                        URI finalUri = reconstructURIWithServer(server, request.getUri()); // 根据服务实例及负载均衡请求地址获取真实的请求地址（子类FeignLoadBalancer进行了重写）
                        S requestForServer = (S) request.replaceUri(finalUri);
                        try {
                            return Observable.just(AbstractLoadBalancerAwareClient.this.execute(requestForServer, requestConfig)); // 最终发起请求，由子类实现
                        } 
                        catch (Exception e) {
                            return Observable.error(e);
                        }
                    }
                })
                .toBlocking()
                .single();
        } catch (Exception e) {
            Throwable t = e.getCause();
            if (t instanceof ClientException) {
                throw (ClientException) t;
            } else {
                throw new ClientException(e);
            }
        }
        
    }
    
    public abstract RequestSpecificRetryHandler getRequestSpecificRetryHandler(S request, IClientConfig requestConfig);

    protected LoadBalancerCommand<T> buildLoadBalancerCommand(final S request, final IClientConfig config) { // 构建LoadBalancerCommand对象
		RequestSpecificRetryHandler handler = getRequestSpecificRetryHandler(request, config); // 获取重试处理器
		LoadBalancerCommand.Builder<T> builder = LoadBalancerCommand.<T>builder() // 构建LoadBalancerCommand对象
				.withLoadBalancerContext(this) // 将当前类FeignLoadBalancer赋值给loadBalancerContext属性
				.withRetryHandler(handler) // 设置重试处理器
				.withLoadBalancerURI(request.getUri());
		customizeLoadBalancerCommandBuilder(request, config, builder);
		return builder.build();
	}

	protected void customizeLoadBalancerCommandBuilder(final S request, final IClientConfig config,
			final LoadBalancerCommand.Builder<T> builder) {
		// do nothing by default, give a chance to its derived class to customize the builder
	}

    @Deprecated
    protected boolean isRetriable(S request) {
        if (request.isRetriable()) {
            return true;            
        } else {
            boolean retryOkayOnOperation = okToRetryOnAllOperations;
            IClientConfig overriddenClientConfig = request.getOverrideConfig();
            if (overriddenClientConfig != null) {
                retryOkayOnOperation = overriddenClientConfig.getPropertyAsBoolean(CommonClientConfigKey.RequestSpecificRetryOn, okToRetryOnAllOperations);
            }
            return retryOkayOnOperation;
        }
    }
    
}



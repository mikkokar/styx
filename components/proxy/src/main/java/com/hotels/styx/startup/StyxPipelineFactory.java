/*
  Copyright (C) 2013-2019 Expedia Inc.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */
package com.hotels.styx.startup;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.hotels.styx.Environment;
import com.hotels.styx.StyxConfig;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.api.extension.service.spi.Registry;
import com.hotels.styx.api.extension.service.spi.StyxService;
import com.hotels.styx.infrastructure.configuration.yaml.JsonNodeConfig;
import com.hotels.styx.proxy.StyxBackendServiceClientFactory;
import com.hotels.styx.proxy.interceptors.ConfigurationContextResolverInterceptor;
import com.hotels.styx.proxy.interceptors.HopByHopHeadersRemovingInterceptor;
import com.hotels.styx.proxy.interceptors.HttpMessageLoggingInterceptor;
import com.hotels.styx.proxy.interceptors.RequestEnrichingInterceptor;
import com.hotels.styx.proxy.interceptors.TcpTunnelRequestRejector;
import com.hotels.styx.proxy.interceptors.UnexpectedRequestContentLengthRemover;
import com.hotels.styx.proxy.interceptors.ViaHeaderAppendingInterceptor;
import com.hotels.styx.proxy.plugin.NamedPlugin;
import com.hotels.styx.routing.HttpPipelineFactory;
import com.hotels.styx.routing.StaticPipelineFactory;
import com.hotels.styx.routing.config.BuiltinInterceptorsFactory;
import com.hotels.styx.routing.config.HttpHandlerFactory;
import com.hotels.styx.routing.config.HttpInterceptorFactory;
import com.hotels.styx.routing.config.RouteHandlerDefinition;
import com.hotels.styx.routing.config.RouteHandlerFactory;
import com.hotels.styx.routing.handlers.BackendServiceProxy;
import com.hotels.styx.routing.handlers.ConditionRouter;
import com.hotels.styx.routing.handlers.HttpInterceptorPipeline;
import com.hotels.styx.routing.handlers.ProxyToBackend;
import com.hotels.styx.routing.handlers.StaticResponseHandler;
import com.hotels.styx.routing.interceptors.RewriteInterceptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hotels.styx.api.configuration.ConfigurationContextResolver.EMPTY_CONFIGURATION_CONTEXT_RESOLVER;
import static java.util.stream.Collectors.toMap;

/**
 * Produces the pipeline for the Styx proxy server.
 */
public final class StyxPipelineFactory implements PipelineFactory {

    private static final ImmutableMap<String, HttpInterceptorFactory> INTERCEPTOR_FACTORIES =
            ImmutableMap.of("Rewrite", new RewriteInterceptor.ConfigFactory());


    public StyxPipelineFactory() {
    }

    @Override
    public HttpHandler create(StyxServerComponents config) {
        boolean requestTracking = config.environment().configuration().get("requestTracking", Boolean.class).orElse(false);

        Map<String, HttpHandler> httpHandlers = config.environment().configuration().get("httpHandlers", JsonNode.class)
                .map(chandlerBlock -> readHttpHandlers(chandlerBlock,
                        config.environment(),
                        config.services(),
                        config.plugins()))
                .orElse(ImmutableMap.of());

        return styxHttpPipeline(
                config.environment().styxConfig(),
                configuredPipeline(
                        config.environment(),
                        config.services(),
                        config.plugins(),
                        newRouteHandlerFactory(
                                config.environment(),
                                config.services(),
                                config.plugins(),
                                requestTracking,
                                httpHandlers)),
                requestTracking);
    }

    public static Map<String, HttpHandler> readHttpHandlers(JsonNode root, Environment environment, Map<String, StyxService> services, List<NamedPlugin> plugins) {
        Map<String, HttpHandler> handlers = new HashMap<>();

        root.fields().forEachRemaining(
                (entry) -> {
                    String name = entry.getKey();
                    RouteHandlerDefinition handlerDef = new JsonNodeConfig(entry.getValue()).as(RouteHandlerDefinition.class);

                    RouteHandlerFactory factory = newRouteHandlerFactory(environment, services, plugins, false, handlers);
                    handlers.put(name, factory.build(ImmutableList.of(), handlerDef));
                }
        );

        return handlers;
    }

    private static RouteHandlerFactory newRouteHandlerFactory(
            Environment environment,
            Map<String, StyxService> services,
            List<NamedPlugin> plugins,
            boolean requestTracking,
            Map<String, HttpHandler> handlers) {
        BuiltinInterceptorsFactory builtinInterceptorsFactory = new BuiltinInterceptorsFactory(INTERCEPTOR_FACTORIES);

        Map<String, HttpHandlerFactory> objectFactories = createBuiltinRoutingObjectFactories(
                environment,
                services,
                plugins,
                builtinInterceptorsFactory,
                requestTracking);

        return new RouteHandlerFactory(objectFactories, handlers);
    }

    private static HttpHandler styxHttpPipeline(StyxConfig config, HttpHandler interceptorsPipeline, boolean requestTracking) {
        ImmutableList.Builder<HttpInterceptor> builder = ImmutableList.builder();

        boolean loggingEnabled = config.get("request-logging.inbound.enabled", Boolean.class)
                .orElse(false);

        boolean longFormatEnabled = config.get("request-logging.inbound.longFormat", Boolean.class)
                .orElse(false);

        if (loggingEnabled) {
            builder.add(new HttpMessageLoggingInterceptor(longFormatEnabled));
        }

        builder.add(new TcpTunnelRequestRejector())
                .add(new ConfigurationContextResolverInterceptor(EMPTY_CONFIGURATION_CONTEXT_RESOLVER))
                .add(new UnexpectedRequestContentLengthRemover())
                .add(new ViaHeaderAppendingInterceptor())
                .add(new HopByHopHeadersRemovingInterceptor())
                .add(new RequestEnrichingInterceptor(config.styxHeaderConfig()));

        return new HttpInterceptorPipeline(builder.build(), interceptorsPipeline, requestTracking);
    }

    private static HttpHandler configuredPipeline(
            Environment environment,
            Map<String, StyxService> servicesFromConfig,
            Iterable<NamedPlugin> plugins,
            RouteHandlerFactory routeHandlerFactory
    ) {
        HttpPipelineFactory pipelineBuilder;

        boolean requestTracking = environment.configuration().get("requestTracking", Boolean.class).orElse(false);

        if (environment.configuration().get("httpPipeline", RouteHandlerDefinition.class).isPresent()) {
            pipelineBuilder = () -> {
                RouteHandlerDefinition pipelineConfig = environment.configuration().get("httpPipeline", RouteHandlerDefinition.class).get();
                return routeHandlerFactory.build(ImmutableList.of("httpPipeline"), pipelineConfig);
            };
        } else {
            Registry<BackendService> backendServicesRegistry = (Registry<BackendService>) servicesFromConfig.get("backendServiceRegistry");
            pipelineBuilder = new StaticPipelineFactory(environment, backendServicesRegistry, plugins, requestTracking);
        }

        return pipelineBuilder.build();
    }

    private static ImmutableMap<String, HttpHandlerFactory> createBuiltinRoutingObjectFactories(
            Environment environment,
            Map<String, StyxService> servicesFromConfig,
            Iterable<NamedPlugin> plugins,
            BuiltinInterceptorsFactory builtinInterceptorsFactory,
            boolean requestTracking) {
        return ImmutableMap.of(
                "StaticResponseHandler", new StaticResponseHandler.ConfigFactory(),
                "ConditionRouter", new ConditionRouter.ConfigFactory(),
                "BackendServiceProxy", new BackendServiceProxy.ConfigFactory(environment, backendRegistries(servicesFromConfig)),
                "InterceptorPipeline", new HttpInterceptorPipeline.ConfigFactory(plugins, builtinInterceptorsFactory, requestTracking),
                "ProxyToBackend", new ProxyToBackend.ConfigFactory(environment, new StyxBackendServiceClientFactory(environment))
        );
    }

    private static Map<String, Registry<BackendService>> backendRegistries(Map<String, StyxService> servicesFromConfig) {
        return servicesFromConfig.entrySet()
                .stream()
                .filter(entry -> entry.getValue() instanceof Registry)
                .collect(toMap(Map.Entry::getKey, entry -> (Registry<BackendService>) entry.getValue()));
    }
}

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
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.api.extension.service.spi.Registry;
import com.hotels.styx.api.extension.service.spi.StyxService;
import com.hotels.styx.infrastructure.configuration.yaml.JsonNodeConfig;
import com.hotels.styx.proxy.plugin.NamedPlugin;
import com.hotels.styx.routing.HttpPipelineFactory;
import com.hotels.styx.routing.StaticPipelineFactory;
import com.hotels.styx.routing.config.BuiltinInterceptorsFactory;
import com.hotels.styx.routing.config.HttpHandlerFactory;
import com.hotels.styx.routing.config.RouteHandlerDefinition;
import com.hotels.styx.routing.config.RouteHandlerFactory;
import com.hotels.styx.routing.handlers.HttpInterceptorPipeline;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hotels.styx.startup.BuiltInInterceptors.INTERCEPTOR_FACTORIES;
import static com.hotels.styx.startup.BuiltInInterceptors.builtInInterceptors;
import static com.hotels.styx.startup.BuiltInRoutingObjects.createBuiltinRoutingObjectFactories;

/**
 * Produces the pipeline for the Styx proxy server.
 */
public final class StyxPipelineFactory implements PipelineFactory {

    private final Environment environment;
    private final Map<String, StyxService> services;
    private final List<NamedPlugin> plugins;

    public StyxPipelineFactory(Environment environment, Map<String, StyxService> services, List<NamedPlugin> plugins) {
        this.environment = environment;
        this.services = services;
        this.plugins = plugins;
    }

    @Override
    public HttpHandler create(StyxServerComponents config) {
        boolean requestTracking = environment.configuration().get("requestTracking", Boolean.class).orElse(false);

        Map<String, HttpHandler> httpHandlers = environment.configuration().get("httpHandlers", JsonNode.class)
                .map(this::readHttpHandlers)
                .orElse(ImmutableMap.of());

        List<HttpInterceptor> builtInInterceptors = builtInInterceptors(environment.styxConfig());

        return new HttpInterceptorPipeline(
                builtInInterceptors,
                configuredPipeline(newRouteHandlerFactory(requestTracking, httpHandlers)),
                requestTracking);
    }

    public Map<String, HttpHandler> readHttpHandlers(JsonNode root) {
        Map<String, HttpHandler> handlers = new HashMap<>();

        root.fields().forEachRemaining(
                (entry) -> {
                    String name = entry.getKey();
                    RouteHandlerDefinition handlerDef = new JsonNodeConfig(entry.getValue()).as(RouteHandlerDefinition.class);
                    RouteHandlerFactory factory = newRouteHandlerFactory(false, handlers);
                    handlers.put(name, factory.build(ImmutableList.of(), handlerDef));
                }
        );

        return handlers;
    }

    private RouteHandlerFactory newRouteHandlerFactory(boolean requestTracking, Map<String, HttpHandler> handlers) {
        BuiltinInterceptorsFactory builtinInterceptorsFactory = new BuiltinInterceptorsFactory(INTERCEPTOR_FACTORIES);

        Map<String, HttpHandlerFactory> objectFactories = createBuiltinRoutingObjectFactories(
                environment,
                services,
                plugins,
                builtinInterceptorsFactory,
                requestTracking);

        return new RouteHandlerFactory(objectFactories, handlers);
    }

    private HttpHandler configuredPipeline(RouteHandlerFactory routeHandlerFactory) {
        HttpPipelineFactory pipelineBuilder;

        boolean requestTracking = environment.configuration().get("requestTracking", Boolean.class).orElse(false);

        if (environment.configuration().get("httpPipeline", RouteHandlerDefinition.class).isPresent()) {
            pipelineBuilder = () -> {
                RouteHandlerDefinition pipelineConfig = environment.configuration().get("httpPipeline", RouteHandlerDefinition.class).get();
                return routeHandlerFactory.build(ImmutableList.of("httpPipeline"), pipelineConfig);
            };
        } else {
            Registry<BackendService> backendServicesRegistry = (Registry<BackendService>) services.get("backendServiceRegistry");
            pipelineBuilder = new StaticPipelineFactory(environment, backendServicesRegistry, plugins, requestTracking);
        }

        return pipelineBuilder.build();
    }
}

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
package com.hotels.styx.routing.db;

import com.google.common.collect.ImmutableList;
import com.hotels.styx.Environment;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.extension.service.spi.StyxService;
import com.hotels.styx.proxy.plugin.NamedPlugin;
import com.hotels.styx.routing.config.BuiltinInterceptorsFactory;
import com.hotels.styx.routing.config.HttpHandlerFactory;
import com.hotels.styx.routing.config.RoutingObjectDefinition;
import com.hotels.styx.routing.config.RoutingObjectFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.hotels.styx.BuiltInInterceptors.INTERCEPTOR_FACTORIES;
import static com.hotels.styx.BuiltInRoutingObjects.createBuiltinRoutingObjectFactories;

/**
 * Styx Route Database.
 */

public class StyxRouteDatabase implements RouteDatabase {
    private final Environment environment;
    private final Map<String, StyxService> services;
    private final List<NamedPlugin> plugins;
    private ConcurrentHashMap<String, RouteDatabaseRecord> handlers;

    public StyxRouteDatabase(Environment environment, Map<String, StyxService> services, List<NamedPlugin> plugins) {
        this.environment = environment;
        this.services = services;
        this.plugins = plugins;
        handlers = new ConcurrentHashMap<>();
    }

    public void insert(String key, RoutingObjectDefinition routingObjectDef) {
        handlers.put(key, new ConfigRecord(key, routingObjectDef));
    }

    //
    // Needs to run concurrently
    //
    @Override
    public Optional<HttpHandler> handler(String key) {
        return Optional.ofNullable(handlers.get(key))
                .map(record -> {
                    if (record instanceof HandlerRecord) {
                        return ((HandlerRecord) record).handler();
                    }

                    RoutingObjectDefinition config = ((ConfigRecord) record).configuration();
                    return new RoutingObjectFactory(factories(), this).build(ImmutableList.of(key), config);
                });
    }

    private Map<String, HttpHandlerFactory> factories() {
        BuiltinInterceptorsFactory builtinInterceptorsFactories = new BuiltinInterceptorsFactory(INTERCEPTOR_FACTORIES);

        return createBuiltinRoutingObjectFactories(
                environment,
                services,
                plugins,
                builtinInterceptorsFactories,
                false //requestTracking
        );
    }

    private static class RouteDatabaseRecord {
        private final String key;

        RouteDatabaseRecord(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    private static class ConfigRecord extends RouteDatabaseRecord {
        private final RoutingObjectDefinition configuration;

        ConfigRecord(String key, RoutingObjectDefinition configuration) {
            super(key);
            this.configuration = configuration;
        }

        RoutingObjectDefinition configuration() {
            return configuration;
        }
    }

    private static class HandlerRecord extends RouteDatabaseRecord {
        private final HttpHandler handler;

        HandlerRecord(String key, HttpHandler handler) {
            super(key);
            this.handler = handler;
        }

        HttpHandler handler() {
            return handler;
        }
    }


}

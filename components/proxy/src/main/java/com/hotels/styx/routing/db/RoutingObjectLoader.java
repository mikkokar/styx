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

import static com.hotels.styx.BuiltInInterceptors.INTERCEPTOR_FACTORIES;
import static com.hotels.styx.BuiltInRoutingObjects.createBuiltinRoutingObjectFactories;

class RoutingObjectLoader implements ObjectLoader {
    private final Environment environment;
    private final Map<String, StyxService> services;
    private final List<NamedPlugin> plugins;

    public RoutingObjectLoader(Environment environment, Map<String, StyxService> services, List<NamedPlugin> plugins) {
        this.environment = environment;
        this.services = services;
        this.plugins = plugins;
    }

    public HttpHandler load(RouteDatabase routeDb, String key, RoutingObjectDefinition config) {
        return new RoutingObjectFactory(factories(), routeDb).build(ImmutableList.of(key), config);
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
}

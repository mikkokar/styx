package com.hotels.styx.routing.db;

import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.routing.config.RoutingObjectDefinition;

public interface ObjectLoader {
    HttpHandler load(RouteDatabase routeDb, String key, RoutingObjectDefinition config);
}

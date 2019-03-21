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
package com.hotels.styx.routing.handlers;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.LiveHttpRequest;
import com.hotels.styx.infrastructure.configuration.yaml.JsonNodeConfig;
import com.hotels.styx.proxy.RouteHandlerAdapter;
import com.hotels.styx.routing.config.HttpHandlerFactory;
import com.hotels.styx.routing.config.RoutingObjectDefinition;
import com.hotels.styx.routing.config.RoutingObjectFactory;
import com.hotels.styx.api.configuration.RouteDatabase;
import com.hotels.styx.server.HttpRouter;
import com.hotels.styx.server.routing.AntlrMatcher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

import static com.hotels.styx.routing.config.RoutingSupport.missingAttributeError;
import static java.lang.String.join;
import static java.util.Comparator.comparingInt;
import static java.util.Comparator.naturalOrder;

/**
 * Condition predicate based HTTP router.
 */
public class PathPrefixRouter implements HttpRouter {
    private RouteDatabase routeDb;
    private final ConcurrentSkipListMap<String, String> routes;

    private PathPrefixRouter(RouteDatabase routeDb, ConcurrentSkipListMap<String, String> routes) {
        this.routeDb = routeDb;
        this.routes = routes;
    }

    @Override
    public Optional<HttpHandler> route(LiveHttpRequest request, HttpInterceptor.Context context) {
        String path = request.path();

        Optional<HttpHandler> destination = routes.entrySet().stream()
                .filter(entry -> path.startsWith(entry.getKey()))
                .findFirst()
                .map(Map.Entry::getValue)
                .flatMap(routeDb::handler);

        return destination;
    }

    private static class Route {
        private final AntlrMatcher matcher;
        private final HttpHandler handler;

        Route(String condition, HttpHandler handler) {
            this.matcher = AntlrMatcher.antlrMatcher(condition);
            this.handler = handler;
        }

        public HttpHandler match(LiveHttpRequest request, HttpInterceptor.Context context) {
            return matcher.apply(request, context) ? handler : null;
        }
    }

    /**
     * Builds a condition router from the yaml routing configuration.
     */
    public static class Factory implements HttpHandlerFactory {
        private static class PathPrefixConfig {
            private final String prefix;
            private final String destination;

            public PathPrefixConfig(@JsonProperty("prefix") String prefix,
                                    @JsonProperty("destination") String destination) {
                this.prefix = prefix;
                this.destination = destination;
            }
        }

        private static class PathPrefixRouterConfig {
            private final List<PathPrefixConfig> routes;

            public PathPrefixRouterConfig(@JsonProperty("routes") List<PathPrefixConfig> routes) {
                this.routes = routes;
            }
        }

        public HttpHandler build(List<String> parents,
                                 RouteDatabase routeDb,
                                 RoutingObjectFactory routingObjectFactory,
                                 RoutingObjectDefinition configBlock
        ) {
            PathPrefixRouterConfig config = new JsonNodeConfig(configBlock.config()).as(PathPrefixRouterConfig.class);
            if (config.routes == null) {
                throw missingAttributeError(configBlock, join(".", parents), "routes");
            }

            ConcurrentSkipListMap<String, String> routes = new ConcurrentSkipListMap<>(
                    comparingInt(String::length).reversed()
                            .thenComparing(naturalOrder()));

            config.routes.forEach(entry -> routes.put(entry.prefix, entry.destination));

            return new RouteHandlerAdapter(new PathPrefixRouter(routeDb, routes));
        }
    }
}

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

import com.hotels.styx.Environment;
import com.hotels.styx.api.Eventual;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.Id;
import com.hotels.styx.api.LiveHttpRequest;
import com.hotels.styx.api.LiveHttpResponse;
import com.hotels.styx.api.extension.RemoteHost;
import com.hotels.styx.api.extension.loadbalancing.spi.LoadBalancer;
import com.hotels.styx.api.extension.loadbalancing.spi.LoadBalancingMetric;
import com.hotels.styx.client.StyxBackendServiceClient;
import com.hotels.styx.client.loadbalancing.strategies.PowerOfTwoStrategy;
import com.hotels.styx.infrastructure.configuration.yaml.JsonNodeConfig;
import com.hotels.styx.proxy.StyxBackendServiceClientFactory;
import com.hotels.styx.routing.config.HttpHandlerFactory;
import com.hotels.styx.routing.config.RoutingObjectDefinition;
import com.hotels.styx.routing.config.RoutingObjectFactory;
import com.hotels.styx.routing.db.RouteDatabase;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.hotels.styx.api.extension.Origin.newOriginBuilder;
import static com.hotels.styx.api.extension.RemoteHost.remoteHost;
import static com.hotels.styx.routing.config.RoutingSupport.missingAttributeError;
import static java.lang.String.join;
import static java.util.Objects.requireNonNull;

public class BackendApplication implements HttpHandler, RouteDatabase.Listener {

    private final AtomicReference<Set<RemoteHost>> remoteHosts = new AtomicReference<>();
    private final StyxBackendServiceClient client;
    private final RouteDatabase routeDatabase;
    private final Environment environment;
    private final String originsTag;
    private final String id;

    public BackendApplication(RouteDatabase routeDatabase, Environment environment, String id, String originsTag, StyxBackendServiceClientFactory styxBackendServiceClientFactory) {
        this.routeDatabase = requireNonNull(routeDatabase);
        this.originsTag = requireNonNull(originsTag);
        this.id = requireNonNull(id);
        this.environment = requireNonNull(environment);
        this.client = createClient();
    }

    private StyxBackendServiceClient createClient() {
//        clientFactory.createClient(backendService, originsInventory, new CodaHaleMetricRegistry());

        LoadBalancer loadBalancer = new PowerOfTwoStrategy(this.remoteHosts::get);

        return new StyxBackendServiceClient.Builder(Id.id(id))
                .loadBalancer(loadBalancer)
//                .stickySessionConfig(backendService.stickySessionConfig())
                .metricsRegistry(environment.metricRegistry())
//                .retryPolicy(retryPolicy)
                .enableContentValidation()
//                .rewriteRules(backendService.rewrites())
//                .originStatsFactory(originStatsFactory)
//                .originsRestrictionCookieName(originRestrictionCookie)
                .originIdHeader(environment.styxConfig().styxHeaderConfig().originIdHeaderName())
                .build();
    }

    // Concurrency issues:
    public void start() {
        this.updated(routeDatabase);
        routeDatabase.addListener(this);
    }

    public void stop() {
        routeDatabase.removeListener(this);
    }

    @Override
    public void updated(RouteDatabase db) {
        Set<HttpHandler> handlers = routeDatabase.handlers(originsTag);
        remoteHosts.set(handlers.stream()
                .map(this::toRemoteHost)
                .collect(Collectors.toSet()));
    }

    private RemoteHost toRemoteHost(HttpHandler handler) {
        return remoteHost(newOriginBuilder("xyz", 1).build(),
                handler,
                () -> new LoadBalancingMetric(1));
    }

    @Override
    public Eventual<LiveHttpResponse> handle(LiveHttpRequest request, HttpInterceptor.Context context) {
        return new Eventual<>(client.sendRequest(request));
    }

    public static class Factory implements HttpHandlerFactory {
        private final Environment environment;

        public Factory(Environment environment) {
            this.environment = environment;
        }

        @Override
        public HttpHandler build(List<String> parents, RouteDatabase routeDb, RoutingObjectFactory builder, RoutingObjectDefinition configBlock) {
            // Read origin tag

            JsonNodeConfig config = new JsonNodeConfig(configBlock.config());
            String originsTag = config.get("origins")
                    .orElseThrow(() -> missingAttributeError(configBlock, join(".", parents), "origins"));

            String id = config.get("id")
                    .orElseThrow(() -> missingAttributeError(configBlock, join(".", parents), "id"));

            BackendApplication app = new BackendApplication(routeDb, environment, id, originsTag, new StyxBackendServiceClientFactory(environment));
            app.start();
            return app;
        }
    }

}

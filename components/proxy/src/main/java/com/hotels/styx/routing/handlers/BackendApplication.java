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
import com.hotels.styx.routing.db.StyxRouteDatabase;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.hotels.styx.api.extension.Origin.newOriginBuilder;
import static com.hotels.styx.api.extension.RemoteHost.remoteHost;
import static com.hotels.styx.routing.config.RoutingSupport.missingAttributeError;
import static java.lang.String.join;

public class BackendApplication implements HttpHandler, RouteDatabase.Listener {

    private final AtomicReference<Set<RemoteHost>> remoteHosts = new AtomicReference<>();
    private final StyxBackendServiceClient client;
    private final StyxRouteDatabase routeDatabase;
    private final Environment environment;
    private final String originsTag;
    private final String id;

    public BackendApplication(StyxRouteDatabase routeDatabase, Environment environment, String id, String originsTag, StyxBackendServiceClientFactory styxBackendServiceClientFactory) {
        this.routeDatabase = routeDatabase;
        this.originsTag = originsTag;
        this.id = id;
        this.environment = environment;
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
        public HttpHandler build(List<String> parents, RoutingObjectFactory builder, RoutingObjectDefinition configBlock) {
            // Read origin tag
            StyxRouteDatabase routeDatabase = null;

            JsonNodeConfig config = new JsonNodeConfig(configBlock.config());
            String originsTag = config.get("origins")
                    .orElseThrow(() -> missingAttributeError(configBlock, join(".", parents), "origins"));

            String id = config.get("id")
                    .orElseThrow(() -> missingAttributeError(configBlock, join(".", parents), "id"));

            return new BackendApplication(routeDatabase, environment, id, originsTag, new StyxBackendServiceClientFactory(environment));
        }
    }

}

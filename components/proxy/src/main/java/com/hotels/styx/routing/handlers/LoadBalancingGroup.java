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

import com.google.common.collect.ImmutableSet;
import com.hotels.styx.api.Eventual;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.Id;
import com.hotels.styx.api.LiveHttpRequest;
import com.hotels.styx.api.LiveHttpResponse;
import com.hotels.styx.api.configuration.ObjectStore;
import com.hotels.styx.api.extension.RemoteHost;
import com.hotels.styx.api.extension.loadbalancing.spi.LoadBalancer;
import com.hotels.styx.client.StyxBackendServiceClient;
import com.hotels.styx.client.loadbalancing.strategies.PowerOfTwoStrategy;
import com.hotels.styx.common.Pair;
import com.hotels.styx.config.schema.Schema;
import com.hotels.styx.routing.RoutingObject;
import com.hotels.styx.routing.RoutingObjectAdapter;
import com.hotels.styx.routing.RoutingObjectRecord;
import com.hotels.styx.routing.config.HttpHandlerFactory;
import com.hotels.styx.routing.config.RoutingObjectDefinition;
import com.hotels.styx.routing.db.StyxObjectStore;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.hotels.styx.api.extension.Origin.newOriginBuilder;
import static com.hotels.styx.api.extension.RemoteHost.remoteHost;
import static com.hotels.styx.config.schema.SchemaDsl.bool;
import static com.hotels.styx.config.schema.SchemaDsl.field;
import static com.hotels.styx.config.schema.SchemaDsl.integer;
import static com.hotels.styx.config.schema.SchemaDsl.object;
import static com.hotels.styx.config.schema.SchemaDsl.optional;
import static com.hotels.styx.config.schema.SchemaDsl.string;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * Add an API for LoadBalancingGroup class.
 */
public class LoadBalancingGroup implements RoutingObject {
    public static final Schema.FieldType SCHEMA = object(
            field("origins", string()),
            optional("strategy", string()),
            optional("stickySession", object(
                    field("enabled", bool()),
                    field("timeoutSeconds", integer())
            ))
    );

    private StyxObjectStore<RoutingObjectRecord> routeDatabase;
    private StyxBackendServiceClient client;
    private Disposable changeWatcher;


    public LoadBalancingGroup(StyxBackendServiceClient client, Disposable changeWatcher) {
        this.client = requireNonNull(client);
        this.changeWatcher = requireNonNull(changeWatcher);
    }

    @Override
    public Eventual<LiveHttpResponse> handle(LiveHttpRequest request, HttpInterceptor.Context context) {
        return new Eventual<>(client.sendRequest(request));
    }

    @Override
    public CompletableFuture<Void> stop() {
        changeWatcher.dispose();
        return completedFuture(null);
    }

    /**
     * Add an API doc for Factory class.
     */
    public static class Factory implements HttpHandlerFactory {
        private static Consumer<? super ObjectStore<RoutingObjectRecord>> routeDatabaseChanged(
                String appId,
                AtomicReference<Set<RemoteHost>> remoteHosts) {
            return snapshot -> {
                Set<RemoteHost> newSet = snapshot.entrySet()
                        .stream()
                        .map(it -> toRemoteHost(appId, it))
                        .collect(Collectors.toSet());

                remoteHosts.set(newSet);
            };
        }

        private static RemoteHost toRemoteHost(String appId, Map.Entry<String, RoutingObjectRecord> record) {
            RoutingObjectAdapter routingObject = record.getValue().getRoutingObject();
            String originName = record.getKey();

            Pair<String, Integer> hostAndPort = Pair.pair("na", 0);

            return remoteHost(
                    newOriginBuilder(hostAndPort.key(), hostAndPort.value())
                            .applicationId(appId)
                            .id(originName)
                            .build(),
                    routingObject,
                    routingObject::metric);
        }

        @Override
        public RoutingObject build(List<String> parents, Context context, RoutingObjectDefinition configBlock) {
            String name = parents.get(parents.size() - 1);
            String originTag = "";

            StyxObjectStore<RoutingObjectRecord> routeDb = context.routeDb();
            AtomicReference<Set<RemoteHost>> remoteHosts = new AtomicReference<>(ImmutableSet.of());

            Disposable watch = Flux.from(routeDb.watch()).subscribe(
                    routeDatabaseChanged(originTag, remoteHosts),
                    this::watchFailed,
                    this::watchCompleted
            );

            LoadBalancer loadBalancer = new PowerOfTwoStrategy(remoteHosts::get);

            StyxBackendServiceClient client = new StyxBackendServiceClient.Builder(Id.id(name))
                    .loadBalancer(loadBalancer)
                    .metricsRegistry(context.environment().metricRegistry())

                    .originIdHeader(context.environment().configuration().styxHeaderConfig().originIdHeaderName())

//                .stickySessionConfig(backendService.stickySessionConfig())
//                .retryPolicy(retryPolicy)
//                .rewriteRules(backendService.rewrites())
//                .originStatsFactory(originStatsFactory)
//                .originsRestrictionCookieName(originRestrictionCookie)
                    .build();

            return new LoadBalancingGroup(client, watch);
        }

        private void watchCompleted() {
            // TODO: Log an error
        }

        private void watchFailed(Throwable throwable) {
            // TODO: Log an error
        }

    }
}

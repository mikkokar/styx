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

import com.google.common.net.HostAndPort;
import com.hotels.styx.api.Eventual;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.LiveHttpRequest;
import com.hotels.styx.api.LiveHttpResponse;
import com.hotels.styx.api.extension.Origin;
import com.hotels.styx.api.extension.service.ConnectionPoolSettings;
import com.hotels.styx.api.extension.service.TlsSettings;
import com.hotels.styx.api.metrics.codahale.CodaHaleMetricRegistry;
import com.hotels.styx.client.Connection;
import com.hotels.styx.client.OriginStatsFactory;
import com.hotels.styx.client.StyxHostHttpClient;
import com.hotels.styx.client.connectionpool.ConnectionPool;
import com.hotels.styx.client.connectionpool.ExpiringConnectionFactory;
import com.hotels.styx.client.connectionpool.SimpleConnectionPoolFactory;
import com.hotels.styx.client.netty.connectionpool.NettyConnectionFactory;
import com.hotels.styx.infrastructure.configuration.yaml.JsonNodeConfig;
import com.hotels.styx.routing.config.HttpHandlerFactory;
import com.hotels.styx.routing.config.RoutingObjectDefinition;
import com.hotels.styx.routing.config.RoutingObjectFactory;
import com.hotels.styx.api.configuration.RouteDatabase;

import java.util.List;
import java.util.Optional;

import static com.hotels.styx.api.extension.service.ConnectionPoolSettings.defaultConnectionPoolSettings;
import static com.hotels.styx.client.HttpRequestOperationFactory.Builder.httpRequestOperationFactoryBuilder;
import static com.hotels.styx.routing.config.RoutingSupport.missingAttributeError;
import static java.lang.String.join;

public class HostProxy implements HttpHandler {
    private final StyxHostHttpClient client;

    public HostProxy(HostAndPort hostAndPort, ConnectionPoolSettings poolSettings, TlsSettings tlsSettings) {
        CodaHaleMetricRegistry metricsRegistry = new CodaHaleMetricRegistry();

        OriginStatsFactory originStatsFactory = new OriginStatsFactory(new CodaHaleMetricRegistry());

        Connection.Factory cf = connectionFactory(
                tlsSettings,
                1,
                5000,
                true,
                false,
                originStatsFactory,
                100);

        ConnectionPool.Factory connectionPoolFactory = new SimpleConnectionPoolFactory.Builder()
                .connectionFactory(cf)
                .connectionPoolSettings(poolSettings)
                .metricRegistry(metricsRegistry)
                .build();

        this.client = StyxHostHttpClient.create(connectionPoolFactory.create(Origin.newOriginBuilder(hostAndPort.getHostText(), hostAndPort.getPort()).build()));
    }

    @Override
    public Eventual<LiveHttpResponse> handle(LiveHttpRequest request, HttpInterceptor.Context context) {
        return new Eventual<>(client.sendRequest(request));
    }

    private Connection.Factory connectionFactory(
            TlsSettings tlsSettings,
            int clientWorkerThreadsCount,
            int responseTimeoutMillis,
            boolean requestLoggingEnabled,
            boolean longFormat,
            OriginStatsFactory originStatsFactory,
            long connectionExpiration) {

        Connection.Factory factory = new NettyConnectionFactory.Builder()
                .name("Styx")
                .httpRequestOperationFactory(
                        httpRequestOperationFactoryBuilder()
                                .flowControlEnabled(true)
                                .originStatsFactory(originStatsFactory)
                                .responseTimeoutMillis(responseTimeoutMillis)
                                .requestLoggingEnabled(requestLoggingEnabled)
                                .longFormat(longFormat)
                                .build()
                )
                .clientWorkerThreadsCount(clientWorkerThreadsCount)
                .tlsSettings(Optional.ofNullable(tlsSettings).orElse(null))
                .build();

        if (connectionExpiration > 0) {
            return new ExpiringConnectionFactory(connectionExpiration, factory);
        } else {
            return factory;
        }
    }


    public static class Factory implements HttpHandlerFactory {
        public HttpHandler build(List<String> parents, RouteDatabase routeDb, RoutingObjectFactory builder, RoutingObjectDefinition configBlock) {
            JsonNodeConfig config = new JsonNodeConfig(configBlock.config());

            // Read hostAndPort:
            HostAndPort hostAndPort = config.get("host")
                    .map(HostAndPort::fromString)
                    .orElseThrow(() -> missingAttributeError(configBlock, join(".", parents), "host"));

            // Read connection pool
            ConnectionPoolSettings poolSettings = config.get("connectionPool", ConnectionPoolSettings.class)
                    .orElse(defaultConnectionPoolSettings());

            // Read TLS settings
            TlsSettings tlsSettings = config.get("tlsSettings", TlsSettings.class)
                    .orElse(null);

            // Create handler
            return new HostProxy(hostAndPort, poolSettings, tlsSettings);
        }
    }
}

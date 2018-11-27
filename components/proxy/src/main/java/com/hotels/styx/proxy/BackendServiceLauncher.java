/*
  Copyright (C) 2013-2018 Expedia Inc.

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
package com.hotels.styx.proxy;

import com.hotels.styx.Environment;
import com.hotels.styx.api.Eventual;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.Id;
import com.hotels.styx.api.LiveHttpRequest;
import com.hotels.styx.api.LiveHttpResponse;
import com.hotels.styx.api.MetricRegistry;
import com.hotels.styx.api.extension.ActiveOrigins;
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.api.extension.service.ConnectionPoolSettings;
import com.hotels.styx.api.extension.service.HealthCheckConfig;
import com.hotels.styx.api.extension.service.TlsSettings;
import com.hotels.styx.client.BackendServiceClient;
import com.hotels.styx.client.Connection;
import com.hotels.styx.client.ConnectionSettings;
import com.hotels.styx.client.OriginStatsFactory;
import com.hotels.styx.client.StyxClientException;
import com.hotels.styx.client.StyxHeaderConfig;
import com.hotels.styx.client.StyxHostHttpClient;
import com.hotels.styx.client.StyxHttpClient;
import com.hotels.styx.client.connectionpool.ConnectionPool;
import com.hotels.styx.client.connectionpool.ExpiringConnectionFactory;
import com.hotels.styx.client.connectionpool.SimpleConnectionPoolFactory;
import com.hotels.styx.client.healthcheck.OriginHealthCheckFunction;
import com.hotels.styx.client.healthcheck.OriginHealthStatusMonitor;
import com.hotels.styx.client.healthcheck.OriginHealthStatusMonitorFactory;
import com.hotels.styx.client.healthcheck.UrlRequestHealthCheck;
import com.hotels.styx.client.netty.connectionpool.NettyConnectionFactory;
import org.pcollections.HashTreePSet;
import org.pcollections.MapPSet;
import org.slf4j.Logger;
import rx.Subscription;
import rx.functions.Action0;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.hotels.styx.client.HttpRequestOperationFactory.Builder.httpRequestOperationFactoryBuilder;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * A {@link HttpHandler} implementation.
 */
public class BackendServiceLauncher {
    private static final Logger LOG = getLogger(BackendServiceLauncher.class);

    private final BackendServiceClientFactory clientFactory;
    private final Environment environment;
    private final int clientWorkerThreadsCount;
    private final ConfigStore configStore;

    private final ConcurrentMap<Id, Subscription> watchesByApp;

    public BackendServiceLauncher(BackendServiceClientFactory clientFactory, Environment environment) {
        this.clientFactory = checkNotNull(clientFactory);
        this.environment = environment;
        this.configStore = environment.configStore();

        this.watchesByApp = new ConcurrentHashMap<>();

        this.clientWorkerThreadsCount = environment.styxConfig().proxyServerConfig().clientWorkerThreadsCount();

        this.configStore.applications().watch()
                .subscribe(this::appsHandlerHandler);
    }

    private void appsHandlerHandler(List<String> appNames) {
        MapPSet<Id> appNamesV2 = HashTreePSet.from(appNames.stream().map(Id::id).collect(toList()));
        MapPSet<Id> appNamesV1 = HashTreePSet.from(watchesByApp.keySet());

        LOG.info("detected changes: {}", new Object[]{appNamesV2.minusAll(appNamesV1)});

        appNamesV2.minusAll(appNamesV1).forEach(this::start);
    }

    private void start(Id appId) {
        watchesByApp.put(appId,
                configStore.application().watch(appId.toString())
                        .subscribe(this::appAdded, this::appTopicError, appRemoved(appId)));
    }

    private void shut(Id appId) {
        LOG.info("shut: {}", appId);

        Optional<ProxyToClientPipeline> maybePipeline = configStore.routingObject().get(appId.toString())
                .map(handler -> (ProxyToClientPipeline) handler);

        // NOTE: Pipeline is closed only AFTER announcing its removal:
        configStore.routingObject().unset(appId.toString());
        maybePipeline.ifPresent(ProxyToClientPipeline::close);

        watchesByApp.remove(appId).unsubscribe();
    }

    private Action0 appRemoved(Id appId) {
        return () -> shut(appId);
    }

    private void appTopicError(Throwable throwable) {
        LOG.error("apps topic error detected: " + throwable);
    }

    private void appAdded(BackendService backendService) {
        LOG.info("watch notification for: {}", backendService.id());

        boolean requestLoggingEnabled = environment.styxConfig().get("request-logging.outbound.enabled", Boolean.class)
                .orElse(false);

        boolean longFormat = environment.styxConfig().get("request-logging.outbound.longFormat", Boolean.class)
                .orElse(false);

        OriginStatsFactory originStatsFactory = new OriginStatsFactory(environment.metricRegistry());
        ConnectionPoolSettings poolSettings = backendService.connectionPoolConfig();

        Connection.Factory connectionFactory = connectionFactory(
                backendService,
                requestLoggingEnabled,
                longFormat,
                originStatsFactory,
                poolSettings.connectionExpirationSeconds());

        ConnectionPool.Factory connectionPoolFactory = new SimpleConnectionPoolFactory.Builder()
                .connectionFactory(connectionFactory)
                .connectionPoolSettings(backendService.connectionPoolConfig())
                .metricRegistry(environment.metricRegistry())
                .build();
        StyxHttpClient healthCheckClient = healthCheckClient(backendService);

        OriginHealthStatusMonitor healthStatusMonitor = healthStatusMonitor(backendService, healthCheckClient);


        StyxHostHttpClient.Factory hostClientFactory = (ConnectionPool connectionPool) -> {
            StyxHeaderConfig headerConfig = environment.styxConfig().styxHeaderConfig();
            return StyxHostHttpClient.create(connectionPool);
        };

        OriginsInventory inventory = new OriginsInventory.Builder(backendService.id())
//                .eventBus(environment.eventBus())
//                .metricsRegistry(environment.metricRegistry())
//                .connectionPoolFactory(connectionPoolFactory)
//                .originHealthMonitor(healthStatusMonitor)
//                .initialOrigins(backendService.origins())
                .hostClientFactory(hostClientFactory)
                .build();

        ActiveOrigins activeOrigins = new com.hotels.styx.proxy.ActiveOrigins(backendService.id().toString(), configStore);

        BackendServiceClient client = clientFactory.createClient(backendService, activeOrigins, originStatsFactory);
        HttpHandler httpClient = newClientHandler(client);
        ProxyToClientPipeline pipeline = new ProxyToClientPipeline(backendService.id(), httpClient, inventory);
        configStore.routingObject().set(backendService.id().toString(), pipeline);
    }

    private StyxHttpClient healthCheckClient(BackendService backendService) {
        StyxHttpClient.Builder builder = new StyxHttpClient.Builder()
                .connectTimeout(backendService.connectionPoolConfig().connectTimeoutMillis(), MILLISECONDS)
                .threadName("Health-Check-Monitor-" + backendService.id())
                .userAgent("Styx/" + environment.buildInfo().releaseVersion());
        backendService.tlsSettings().ifPresent(builder::tlsSettings);
        return builder.build();
    }

    private OriginHealthStatusMonitor healthStatusMonitor(BackendService backendService, StyxHttpClient healthCheckClient) {
        return new OriginHealthStatusMonitorFactory()
                .create(backendService.id(),
                        backendService.healthCheckConfig(),
                        () -> originHealthCheckFunction(
                                backendService.id(),
                                environment.metricRegistry(),
                                backendService.healthCheckConfig()),
                        healthCheckClient);
    }

    private static OriginHealthCheckFunction originHealthCheckFunction(
            Id appId,
            MetricRegistry metricRegistry,
            HealthCheckConfig healthCheckConfig) {
        String healthCheckUri = healthCheckConfig
                .uri()
                .orElseThrow(() -> new IllegalArgumentException("Health check URI missing for " + appId));
        return new UrlRequestHealthCheck(healthCheckUri, metricRegistry);
    }

    private Connection.Factory connectionFactory(
            BackendService backendService,
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
                                .responseTimeoutMillis(backendService.responseTimeoutMillis())
                                .requestLoggingEnabled(requestLoggingEnabled)
                                .longFormat(longFormat)
                                .build()
                )
                .clientWorkerThreadsCount(clientWorkerThreadsCount)
                .tlsSettings(backendService.tlsSettings().orElse(null))
                .build();

        if (connectionExpiration > 0) {
            return new ExpiringConnectionFactory(connectionExpiration, factory);
        } else {
            return factory;
        }
    }

    private static HttpHandler newClientHandler(BackendServiceClient client) {
        return (request, context) -> new Eventual<>(client.sendRequest(request));
    }

    private static OriginHealthCheckFunction originHealthCheckFunction(
            Id appId,
            MetricRegistry metricRegistry,
            Optional<TlsSettings> tlsSettings,
            ConnectionPoolSettings connectionPoolSettings,
            HealthCheckConfig healthCheckConfig,
            String styxVersion) {

        ConnectionSettings connectionSettings = new ConnectionSettings(
                connectionPoolSettings.connectTimeoutMillis());

        String healthCheckUri = healthCheckConfig
                .uri()
                .orElseThrow(() -> new IllegalArgumentException("Health check URI missing for " + appId));

        return new UrlRequestHealthCheck(healthCheckUri, metricRegistry);
    }

    private static class ProxyToClientPipeline implements HttpHandler {
        private final HttpHandler client;
        private final OriginsInventory originsInventory;
        private final Id appId;

        private volatile boolean closed;

        ProxyToClientPipeline(Id appId, HttpHandler httpClient, OriginsInventory originsInventory) {
            this.appId = appId;
            this.client = checkNotNull(httpClient);
            this.originsInventory = originsInventory;
        }

        @Override
        public Eventual<LiveHttpResponse> handle(LiveHttpRequest request, HttpInterceptor.Context context) {
            if (closed) {
                return Eventual.error(new StyxClientException(format("Client is closed. AppId='%s'", appId)));
            } else {
                return client.handle(request, context);
            }
        }

        public void close() {
            closed = true;
//            originsInventory.close();
        }
    }
}

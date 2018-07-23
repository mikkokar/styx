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
import com.hotels.styx.api.HttpClient;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.HttpRequest;
import com.hotels.styx.api.HttpResponse;
import com.hotels.styx.api.Id;
import com.hotels.styx.api.StyxObservable;
import com.hotels.styx.api.extension.service.ConnectionPoolSettings;
import com.hotels.styx.client.Connection;
import com.hotels.styx.client.connectionpool.ConnectionPool;
import com.hotels.styx.api.MetricRegistry;
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.api.extension.service.HealthCheckConfig;
import com.hotels.styx.api.extension.service.TlsSettings;
import com.hotels.styx.client.ConnectionSettings;
import com.hotels.styx.client.OriginStatsFactory;
import com.hotels.styx.client.OriginsInventory;
import com.hotels.styx.client.SimpleHttpClient;
import com.hotels.styx.client.StyxClientException;
import com.hotels.styx.client.StyxHeaderConfig;
import com.hotels.styx.client.StyxHostHttpClient;
import com.hotels.styx.client.connectionpool.ConnectionPoolFactory;
import com.hotels.styx.client.connectionpool.ExpiringConnectionFactory;
import com.hotels.styx.client.healthcheck.OriginHealthCheckFunction;
import com.hotels.styx.client.healthcheck.OriginHealthStatusMonitor;
import com.hotels.styx.client.healthcheck.OriginHealthStatusMonitorFactory;
import com.hotels.styx.client.healthcheck.UrlRequestHealthCheck;
import com.hotels.styx.client.netty.connectionpool.NettyConnectionFactory;
import com.hotels.styx.configstore.ConfigStore;
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
import static com.hotels.styx.api.StyxInternalObservables.fromRxObservable;
import static com.hotels.styx.client.HttpRequestOperationFactory.Builder.httpRequestOperationFactoryBuilder;
import static java.lang.String.format;
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

        this.configStore.<List<String>>watch("apps")
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
                configStore.<BackendService>watch(appAttributeName(appId))
                        .subscribe(this::appAdded, this::appTopicError, appRemoved(appId)));
    }

    private static String appAttributeName(Id id) {
        return format("apps.%s", id.toString());
    }

    private void shut(Id appId) {
        LOG.info("shut: {}", appId);

        Optional<ProxyToClientPipeline> maybePipeline = configStore.get("routing.objects." + appId);

        // NOTE: Pipeline is closed only AFTER announcing its removal:
        configStore.unset("routing.objects." + appId);
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

        ConnectionPool.Factory connectionPoolFactory = new ConnectionPoolFactory.Builder()
                .connectionFactory(connectionFactory)
                .connectionPoolSettings(backendService.connectionPoolConfig())
                .metricRegistry(environment.metricRegistry())
                .build();

        OriginHealthStatusMonitor healthStatusMonitor = new OriginHealthStatusMonitorFactory()
                .create(backendService.id(),
                        backendService.healthCheckConfig(),
                        () -> originHealthCheckFunction(
                                backendService.id(),
                                environment.metricRegistry(),
                                backendService.tlsSettings(),
                                backendService.connectionPoolConfig(),
                                backendService.healthCheckConfig(),
                                environment.buildInfo().releaseVersion()
                        ));

        StyxHostHttpClient.Factory hostClientFactory = (ConnectionPool connectionPool) -> {
            StyxHeaderConfig headerConfig = environment.styxConfig().styxHeaderConfig();
            return StyxHostHttpClient.create(backendService.id(), connectionPool.getOrigin().id(), headerConfig.originIdHeaderName(), connectionPool);
        };

        //TODO: origins inventory builder assumes that appId/originId tuple is unique and it will fail on metrics registration.
        OriginsInventory inventory = new OriginsInventory.Builder(backendService.id())
                .eventBus(environment.eventBus())
                .metricsRegistry(environment.metricRegistry())
                .connectionPoolFactory(connectionPoolFactory)
                .originHealthMonitor(healthStatusMonitor)
                .initialOrigins(backendService.origins())
                .hostClientFactory(hostClientFactory)
                .build();

        ProxyToClientPipeline pipeline = new ProxyToClientPipeline(backendService.id(), newClientHandler(backendService, inventory, originStatsFactory), inventory);

        configStore.set("routing.objects." + backendService.id(), pipeline);
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

    private HttpHandler newClientHandler(BackendService backendService, OriginsInventory originsInventory, OriginStatsFactory originStatsFactory) {
        HttpClient client = clientFactory.createClient(backendService, originsInventory, originStatsFactory);
        return (request, context) -> fromRxObservable(client.sendRequest(request));
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

        SimpleHttpClient client = new SimpleHttpClient.Builder()
                .connectionSettings(connectionSettings)
                .threadName("Health-Check-Monitor-" + appId)
                .userAgent("Styx/" + styxVersion)
                .tlsSettings(tlsSettings.orElse(null))
                .build();

        String healthCheckUri = healthCheckConfig
                .uri()
                .orElseThrow(() -> new IllegalArgumentException("Health check URI missing for " + appId));

        return new UrlRequestHealthCheck(healthCheckUri, client, metricRegistry);
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
        public StyxObservable<HttpResponse> handle(HttpRequest request, HttpInterceptor.Context context) {
            if (closed) {
                return StyxObservable.error(new StyxClientException(format("Client is closed. AppId='%s'", appId)));
            } else {
                return client.handle(request, context);
            }
        }

        public void close() {
            closed = true;
            originsInventory.close();
        }
    }
}

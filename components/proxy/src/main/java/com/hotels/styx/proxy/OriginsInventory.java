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

import com.hotels.styx.api.Eventual;
import com.hotels.styx.api.Id;
import com.hotels.styx.api.extension.Origin;
import com.hotels.styx.api.extension.RemoteHost;
import com.hotels.styx.api.extension.loadbalancing.spi.LoadBalancingMetric;
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.api.metrics.codahale.CodaHaleMetricRegistry;
import com.hotels.styx.client.StyxHostHttpClient;
import com.hotels.styx.client.connectionpool.ConnectionPool;
import com.hotels.styx.client.healthcheck.OriginHealthStatusMonitor;
import com.hotels.styx.client.healthcheck.monitors.NoOriginHealthStatusMonitor;
import com.hotels.styx.common.EventProcessor;
import com.hotels.styx.common.QueueDrainingEventProcessor;
import org.slf4j.Logger;
import rx.Subscription;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.hotels.styx.api.extension.RemoteHost.remoteHost;
import static com.hotels.styx.client.connectionpool.ConnectionPools.simplePoolFactory;
import static com.hotels.styx.common.StyxFutures.await;
import static java.lang.String.format;
import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * An inventory of the origins configured for a single application.
 */
@ThreadSafe
public final class OriginsInventory implements EventProcessor {
    private static final Logger LOG = getLogger(OriginsInventory.class);

    private final Id appId;
    private ConfigStore configStore;
    private final ConnectionPool.Factory hostConnectionPoolFactory;
    private final StyxHostHttpClient.Factory hostClientFactory;
    private final QueueDrainingEventProcessor eventQueue;

    private Map<String, Subscription> subscriptionsByOrigin = new ConcurrentHashMap<>();
    private Map<String, StyxHostHttpClient> clientsByOrigin = new ConcurrentHashMap<>();


    /**
     * Construct an instance.
     *  @param appId                     the application that this inventory's origins are associated with
     * @param configStore
     * @param hostConnectionPoolFactory factory to create connection pools for origins
     */
    public OriginsInventory(Id appId,
                            ConfigStore configStore,
                            ConnectionPool.Factory hostConnectionPoolFactory,
                            StyxHostHttpClient.Factory hostClientFactory) {
        this.appId = requireNonNull(appId);
        this.configStore = requireNonNull(configStore);
        this.hostConnectionPoolFactory = requireNonNull(hostConnectionPoolFactory);
        this.hostClientFactory = requireNonNull(hostClientFactory);

        eventQueue = new QueueDrainingEventProcessor(this, true);

        configStore.origins().watch(appId.toString())
                .subscribe(origins -> origins.forEach(originId -> {
                    eventQueue.submit(new NewOriginEvent(originId));
                }));


    }

    @Override
    public void submit(Object event) {
        LOG.info("submit called: {}", event);
        if (event instanceof NewOriginEvent) {
            // TODO: Should assert that no existing subscription exists!
            NewOriginEvent originEvent = (NewOriginEvent) event;
            Subscription subscription = configStore.origin().watch(format("%s.%s", appId, originEvent.originId))
                    .subscribe(origin -> {
                                OriginUpdatedEvent event1 = new OriginUpdatedEvent(origin);
                                LOG.info("dispatch: {}", event1);
                                eventQueue.submit(event1);
                            },
                            cause -> eventQueue.submit(new OriginTopicErrorEvent(originEvent.originId, cause)),
                            () -> eventQueue.submit(new OriginRemovedEvent(originEvent.originId))
                    );
            subscriptionsByOrigin.putIfAbsent(originEvent.originId, subscription);
        } else if (event instanceof OriginUpdatedEvent) {
            OriginUpdatedEvent updateEvent = (OriginUpdatedEvent) event;
            Origin origin = updateEvent.origin;

            // 1. Set Gauges
            // TODO: Mikko: Move Gauge setup elsewhere into another actor:
            //            String gaugeName = "origins." + appId + "." + origin.id() + ".status";
            //            metricRegistry.register(gaugeName, (Gauge<Integer>) () ->
            //                    configStore.<Integer>get(originStateAttribute(this.appId, origin.id().toString())).orElse(-1));

            // 2. Create Remote Host Client
            ConnectionPool connectionPool = hostConnectionPoolFactory.create(origin);
            StyxHostHttpClient hostClient = hostClientFactory.create(connectionPool);
            clientsByOrigin.putIfAbsent(origin.id().toString(), hostClient);

            RemoteHost remoteHost = remoteHost(
                    origin,
                    (request, context) -> new Eventual<>(hostClient.sendRequest(request)),
                    () -> new LoadBalancingMetric(connectionPool.stats().busyConnectionCount() + connectionPool.stats().pendingConnectionCount()));

            // 3. Set the Remote Host Client as a config store attribute.
            configStore.remoteHost().set(format("%s.%s", this.appId, origin.id().toString()), remoteHost);
        } else if (event instanceof OriginRemovedEvent) {
            OriginRemovedEvent removedEvent = (OriginRemovedEvent) event;
            subscriptionsByOrigin.remove(removedEvent.originId);
            configStore.remoteHost().unset(format("%s.%s", this.appId, removedEvent.originId));
            clientsByOrigin.remove(removedEvent.originId);
        }
    }

    public static Builder newOriginsInventoryBuilder(Id appId) {
        return new Builder(appId);
    }

    // TODO: can be removed?
    public static Builder newOriginsInventoryBuilder(BackendService backendService) {
        return new Builder(backendService.id())
                .connectionPoolFactory(simplePoolFactory(backendService, new CodaHaleMetricRegistry()));
    }

//    public static Builder newOriginsInventoryBuilder(BackendService backendService) {
//        return new Builder(backendService.id())
//                .connectionPoolFactory(simplePoolFactory(backendService, new CodaHaleMetricRegistry()))
//                .initialOrigins(backendService.origins());
//    }


    /**
     * A builder for {@link OriginsInventory}.
     */
    public static class Builder {
        private final Id appId;
        private OriginHealthStatusMonitor originHealthMonitor = new NoOriginHealthStatusMonitor();
        private ConnectionPool.Factory connectionPoolFactory = simplePoolFactory();
        private StyxHostHttpClient.Factory hostClientFactory;
        private Set<Origin> initialOrigins = emptySet();
        private ConfigStore configStore;

        public Builder(Id appId) {
            this.appId = requireNonNull(appId);
        }

        public Builder connectionPoolFactory(ConnectionPool.Factory connectionPoolFactory) {
            this.connectionPoolFactory = requireNonNull(connectionPoolFactory);
            return this;
        }

        public Builder hostClientFactory(StyxHostHttpClient.Factory hostClientFactory) {
            this.hostClientFactory = requireNonNull(hostClientFactory);
            return this;
        }

        public Builder configStore(ConfigStore configStore) {
            this.configStore = configStore;
            return this;
        }

        public OriginsInventory build() {
            await(originHealthMonitor.start());

            if (hostClientFactory == null) {
                hostClientFactory = StyxHostHttpClient::create;
            }

            OriginsInventory originsInventory = new OriginsInventory(
                    appId,
                    configStore,
                    connectionPoolFactory,
                    hostClientFactory);

            return originsInventory;
        }
    }

    private class NewOriginEvent {
        public String originId;

        public NewOriginEvent(String originId) {
            this.originId = originId;
        }
    }

    private class OriginUpdatedEvent {
        private Origin origin;

        public OriginUpdatedEvent(Origin origin) {
            this.origin = origin;
        }
    }

    private class OriginTopicErrorEvent {
        private final String originId;
        private final Throwable cause;

        public OriginTopicErrorEvent(String originId, Throwable cause) {
            this.originId = originId;
            this.cause = cause;
        }
    }

    private class OriginRemovedEvent {
        private String originId;

        public OriginRemovedEvent(String originId) {
            this.originId = originId;
        }
    }
}

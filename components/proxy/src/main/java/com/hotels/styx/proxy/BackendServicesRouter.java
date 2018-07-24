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
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.HttpRequest;
import com.hotels.styx.api.Id;
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.common.StateMachine;
import com.hotels.styx.configstore.ConfigStore;
import com.hotels.styx.server.HttpRouter;
import org.pcollections.HashTreePSet;
import org.pcollections.MapPSet;
import org.slf4j.Logger;
import rx.Subscription;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

import static com.hotels.styx.StyxConfigStore.appsAttribute;
import static com.hotels.styx.StyxConfigStore.routingObjectAttribute;
import static com.hotels.styx.proxy.BackendServicesRouter.State.APP_PENDING;
import static com.hotels.styx.proxy.BackendServicesRouter.State.CREATED;
import static com.hotels.styx.proxy.BackendServicesRouter.State.FINISHED;
import static com.hotels.styx.proxy.BackendServicesRouter.State.WF_APP;
import static com.hotels.styx.proxy.BackendServicesRouter.State.WF_ROUTE;
import static java.util.Comparator.comparingInt;
import static java.util.Comparator.naturalOrder;
import static java.util.stream.Collectors.toList;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * A {@link HttpHandler} implementation.
 */

// TODO: Mikko: We can now add unit tests for all state transitions:


public class BackendServicesRouter implements HttpRouter {
    private static final Logger LOGGER = getLogger(BackendServicesRouter.class);

    private final ConcurrentMap<Id, AppRecord> subscriptionsById;
    private final ConcurrentMap<String, HttpHandler> routes;

    private final ConfigStore configStore;

    enum State {
        APP_PENDING,
        WF_ROUTE,
        WF_APP,
        CREATED,
        FINISHED
    }

    private static StateMachine<State> fsm() {
        return new StateMachine.Builder<State>()
                .initialState(APP_PENDING)
                .transition(APP_PENDING, AppAvailableEvent.class, event -> {
                    event.record.app = event.backendService;
                    return WF_ROUTE;
                })
                .transition(APP_PENDING, RouteAvailableEvent.class, event -> {
                    event.record.handler = event.handler;
                    return WF_APP;
                })
                .transition(WF_ROUTE, RouteAvailableEvent.class, event -> {
                    event.record.handler = event.handler;
                    event.record.routes.putIfAbsent(event.record.app.path(), event.handler);
                    return CREATED;
                })
                .transition(WF_ROUTE, AppRemovedEvent.class, event -> {
                    event.record.app = null;
                    event.record.appWatch.unsubscribe();
                    event.record.routeWatch.unsubscribe();
                    event.record.subscriptionsById.remove(event.record.app.id());
                    return FINISHED;
                })
                .transition(WF_APP, AppAvailableEvent.class, event -> {
                    event.record.app = event.backendService;
                    event.record.routes.putIfAbsent(event.record.app.path(), event.record.handler);
                    return CREATED;
                })
                .transition(WF_APP, RouteRemovedEvent.class, event -> {
                    event.record.handler = null;
                    return APP_PENDING;
                })
                .transition(CREATED, AppRemovedEvent.class, event -> {
                    event.record.appWatch.unsubscribe();
                    event.record.routeWatch.unsubscribe();
                    event.record.subscriptionsById.remove(event.record.app.id());

                    String pathPrefix = event.record.app.path();
                    event.record.routes.remove(pathPrefix);

                    event.record.app = null;
                    event.record.handler = null;
                    return FINISHED;
                })
                .transition(CREATED, RouteRemovedEvent.class, event -> {
                    String pathPrefix = event.record.app.path();
                    event.record.handler = null;
                    event.record.routes.remove(pathPrefix);
                    return WF_ROUTE;
                })

                .onInappropriateEvent((state, event) -> {
                    LOGGER.warn("State={} inappropriate event received: {}", state, event.getClass().getSimpleName());
                    return state;
                })

                .onStateChange((from, to, event) -> LOGGER.info("FSM {} {}: {} --> {}", new Object[]{((RootEvent) event).record.appId, event, from, to}))

                .build();
    }

    private static class AppRecord {
        private String appId;
        private StateMachine<State> fsm = fsm();
        private Subscription appWatch;
        private Subscription routeWatch;
        private String pathPrefix;
        private BackendService app;
        private HttpHandler handler;
        private ConcurrentMap<String, HttpHandler> routes;
        private final ConcurrentMap<Id, AppRecord> subscriptionsById;

        public AppRecord(String appId, ConcurrentMap<String, HttpHandler> routes, ConcurrentMap<Id, AppRecord> subscriptionsById) {
            this.appId = appId;
            this.routes = routes;
            this.subscriptionsById = subscriptionsById;
        }

        public void appWatch(Subscription appWatch) {
            this.appWatch = appWatch;
        }

        public void routeWatch(Subscription routeWatch) {
            this.routeWatch = routeWatch;
        }

        public void pathPrefix(String pathPrefix) {
            this.pathPrefix = pathPrefix;
        }
    }

    public BackendServicesRouter(Environment environment) {
        this.configStore = environment.configStore();

        this.routes = new ConcurrentSkipListMap<>(
                comparingInt(String::length).reversed()
                        .thenComparing(naturalOrder()));

        this.subscriptionsById = new ConcurrentHashMap<>();

        this.configStore.<List<String>>watch("apps")
                .subscribe(this::appsHandlerHandler);
    }

    @Override
    public Optional<HttpHandler> route(HttpRequest request) {
        LOGGER.info("route: {}", request.path());
        String path = request.path();

        return routes.entrySet().stream()
                .filter(entry -> path.startsWith(entry.getKey()))
                .findFirst()
                .map(Map.Entry::getValue);
    }

    private void appsHandlerHandler(List<String> appNames) {
        MapPSet<Id> previousAppNames = HashTreePSet.from(appNames.stream().map(Id::id).collect(toList()));
        MapPSet<Id> newAppNames = HashTreePSet.from(subscriptionsById.keySet());

        LOGGER.info("detected changes: {}", previousAppNames.minusAll(newAppNames));

        previousAppNames.minusAll(newAppNames).forEach(this::start);
    }

    private void start(Id appId) {
        LOGGER.info("start: {}", appId);
        AppRecord record = new AppRecord(appId.toString(), routes, subscriptionsById);
        subscriptionsById.put(appId, record);

        Subscription appWatch = configStore.<BackendService>watch(appsAttribute(appId))
                .subscribe(
                        backendService -> record.fsm.handle(new AppAvailableEvent(record, backendService)),
                        cause -> LOGGER.error("topic error on topic={}, cause={}", appsAttribute(appId), cause),
                        () -> record.fsm.handle(new AppRemovedEvent(record))
                );
        record.appWatch(appWatch);

        Subscription routeWatch = configStore.<HttpHandler>watch(routingObjectAttribute(appId))
                .subscribe(
                        handler -> record.fsm.handle(new RouteAvailableEvent(record, handler)),
                        cause -> LOGGER.error("topic error on topic={}, cause={}", routingObjectAttribute(appId), cause),
                        () -> record.fsm.handle(new RouteRemovedEvent(record)));
        record.routeWatch(routeWatch);
    }

    private class RootEvent {
        protected AppRecord record;

        private RootEvent(AppRecord record) {
            this.record = record;
        }
    }

    private class AppAvailableEvent extends RootEvent {
        private BackendService backendService;

        public AppAvailableEvent(AppRecord record, BackendService backendService) {
            super(record);
            this.backendService = backendService;
        }
    }

    private class RouteAvailableEvent extends RootEvent {
        private HttpHandler handler;

        public RouteAvailableEvent(AppRecord record, HttpHandler handler) {
            super(record);
            this.handler = handler;
        }
    }

    private class AppRemovedEvent extends RootEvent {
        public AppRemovedEvent(AppRecord record) {
            super(record);
        }
    }

    private class RouteRemovedEvent extends RootEvent {
        private AppRecord record;

        public RouteRemovedEvent(AppRecord record) {
            super(record);
        }
    }
}

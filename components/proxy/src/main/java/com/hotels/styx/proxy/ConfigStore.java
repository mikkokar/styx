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

import com.google.common.collect.ImmutableList;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.extension.Origin;
import com.hotels.styx.api.extension.RemoteHost;
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.configstore.ConfigTopic;
import com.hotels.styx.configstore.MultiValueConfigTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Scheduler;
import rx.schedulers.Schedulers;

import java.util.List;

import static java.lang.String.format;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.stream.Collectors.toList;

/**
 * Stores data about the current state of the system.
 * <p>
 * All `watch` notification events are executed sequentially in a sepearate config store worker thread.
 */
public class ConfigStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigStore.class);

    private static final Scheduler SCHEDULER = Schedulers.from(newSingleThreadExecutor(runnable -> new Thread(runnable, "Styx-ConfigStore-Worker")));

    private ConfigTopic<List<String>> applications;

    private MultiValueConfigTopic<BackendService> application;

    private MultiValueConfigTopic<List<String>> originNames;

    private MultiValueConfigTopic<Origin> origin;

    private MultiValueConfigTopic<RemoteHost> remoteHost;

    private ConfigTopic<List<String>> routingObjects;

    private MultiValueConfigTopic<HttpHandler> routingObject;

    public ConfigStore() {
        this(SCHEDULER);
    }

    public ConfigStore(Scheduler scheduler) {
        applications = new ConfigTopic<>("apps", scheduler, ImmutableList.of());
        application = new MultiValueConfigTopic<>(scheduler);
        originNames = new MultiValueConfigTopic<>(scheduler);
        origin = new MultiValueConfigTopic<>(scheduler);
        remoteHost = new MultiValueConfigTopic<>(scheduler);
        routingObjects = new ConfigTopic<>("routing.objects", scheduler, ImmutableList.of());
        routingObject = new MultiValueConfigTopic<>(scheduler);
    }

    public ConfigTopic<List<String>> applications() {
        return applications;
    }

    public MultiValueConfigTopic<BackendService> application() {
        return application;
    }

    public MultiValueConfigTopic<List<String>> origins() {
        return originNames;
    }

    public MultiValueConfigTopic<Origin> origin() {
        return origin;
    }

    public MultiValueConfigTopic<RemoteHost> remoteHost() {
        return remoteHost;
    }

    public ConfigTopic<List<String>> routingObjects() {
        return routingObjects;
    }

    public MultiValueConfigTopic<HttpHandler> routingObject() {
        return routingObject;
    }

    /**
     * Adds new application to the config store.
     * <p>
     * 1) Adds a new backend service configuration to `applications` topic using the `apps.[appId]` as a name.
     * 2) Adds `[appId]` to the list of `applicationNames` list.
     * <p>
     * It is guaranteed that the application is added to the `applications` list BEFORE it is published
     * in the `applicationNames` list.
     *
     * @param id
     * @param backendService
     */
    public void addNewApplication(String id, BackendService backendService) {
        application.set(id, backendService);
        List<String> appNames = applications.get();
        ImmutableList<String> newAppNames = ImmutableList.<String>builder()
                .addAll(appNames)
                .add(id)
                .build();
        applications.set(newAppNames);
    }

    /**
     * Removes an application from the config store.
     * <p>
     * 1) Application name is removed from the `applicationNames` topic.
     * 2) After that the application itself is removed from the `applications` topic.
     * <p>
     * The application name is guaranteed to be removed first before the application
     * is removed from the `applications` topic.
     *
     * @param id
     */
    public void removeApplication(String id) {
        List<String> appNames = applications.get();

        List<String> newAppNames = appNames.stream()
                .filter(appId -> !appId.equals(id))
                .collect(toList());

        applications.set(newAppNames);
        application.unset(id);
    }

    /**
     * Adds origin configuration.
     *
     * The origin configuration is stored against name `apps.[appId].origins.[originId]`.
     * The `originNames` topic is then udpated with originId.
     *
     * It is guaranteed that `originNames` topic is updated only *after* the origin configuration
     * is available at configuredOrigins topic.
     *
     * @param appId
     * @param originId
     * @param origin
     */
    public void addOriginConfiguration(String appId, String originId, Origin origin) {
        this.origin.set(format("%s.%s", appId, originId), origin);

        originNames.update(appId,
                maybeOrigins ->
                        maybeOrigins.map(origins ->
                                ImmutableList.<String>builder()
                                        .addAll(origins)
                                        .add(originId)
                                        .build()
                        ).orElse(ImmutableList.of(originId))
        );
    }

    /**
     * Removes origins configuration,
     *
     * Guarantees that the origin ID is removed from `originNames` list *before*
     * the origin configuration is removed from the `configuredOrigins` topic.
     *
     * @param appId
     * @param originId
     */
    public void removeOriginConfiguration(String appId, String originId) {
        originNames.update(appId,
                maybeOrigins ->
                        maybeOrigins.map(origins ->
                                origins.stream()
                                .filter(name -> !name.equals(originId))
                                .collect(toList())
                        ).orElse(ImmutableList.of())
        );

        origin.unset(format("%s.%s", appId, originId));
    }

}

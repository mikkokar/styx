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
package com.hotels.styx.proxy.healthchecks;

import com.google.common.collect.ImmutableSet;
import com.hotels.styx.api.Eventual;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.configuration.RouteDatabase;
import com.hotels.styx.api.extension.service.HealthCheckConfig;
import com.hotels.styx.api.extension.service.spi.AbstractStyxService;
import com.hotels.styx.client.healthcheck.OriginHealthStatusMonitor;
import com.hotels.styx.client.healthcheck.Schedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.lang.Thread.currentThread;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.runAsync;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * An {@link OriginHealthStatusMonitor} that monitors the origins state
 * periodically.
 */
@ThreadSafe
public class HealthCheckMonitoringService extends AbstractStyxService implements RouteDatabase.Listener {
    private final Function<HttpHandler, Eventual<Boolean>> healthCheckingFunction;
    private final ScheduledExecutorService hostHealthMonitorExecutor;
    private final HealthCheckConfig config;
    private RouteDatabase routeDb;
    private final String application;
    private final Schedule schedule;

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthCheckMonitoringService.class);

    private final Set<RouteDatabase.Record> hosts;

    /**
     * Construct an instance.
     *
     * @param application
     * @param hostHealthMonitorExecutor service that will execute health-checks on a schedule
     * @param healthCheckingFunction    function that performs health-checks
     * @param schedule                  schedule to follow for health-checking
     */
    public HealthCheckMonitoringService(
            RouteDatabase routeDb,
            String application,
            ScheduledExecutorService hostHealthMonitorExecutor,
            Function<HttpHandler, Eventual<Boolean>> healthCheckingFunction,
            Schedule schedule,
            HealthCheckConfig config) {
        super("HealthCheckMonitoringService");
        this.routeDb = routeDb;
        this.application = requireNonNull(application);
        this.hostHealthMonitorExecutor = requireNonNull(hostHealthMonitorExecutor);
        this.healthCheckingFunction = requireNonNull(healthCheckingFunction);
        this.schedule = requireNonNull(schedule);
        this.config = config;

        this.hosts = new CopyOnWriteArraySet<>();
    }

    @Override
    public void updated(RouteDatabase db) {
        Set<RouteDatabase.Record> handlers = routeDb.tagLookup(this.application).stream()
                .filter(record -> isEnabled(record.tags()))
                .collect(Collectors.toSet());
        hosts.clear();
        hosts.addAll(handlers);
    }

    @Override
    protected CompletableFuture<Void> startService() {
        LOGGER.info("HealthCheckMonitoringService - Service Started");
        scheduleHealthCheck();
        updated(routeDb);
        routeDb.addListener(this);
        return completedFuture(null);
    }

    @Override
    protected CompletableFuture<Void> stopService() {
        this.hostHealthMonitorExecutor.shutdown();

        return runAsync(() -> {
            try {
                hostHealthMonitorExecutor.awaitTermination(10, SECONDS);
            } catch (InterruptedException e) {
                currentThread().interrupt();
                throw new RuntimeException(e);
            }
        });
    }

    private void scheduleHealthCheck() {
        this.hostHealthMonitorExecutor.scheduleAtFixedRate(() ->
                healthCheck(hosts), schedule.initialDelay(), schedule.period(), schedule.unit());
    }

    private void healthCheck(Set<RouteDatabase.Record> hosts) {
        Set<RouteDatabase.Record> snapshot = ImmutableSet.copyOf(hosts);

        for (RouteDatabase.Record record: snapshot) {
            healthCheckOriginAndAnnounceListeners(record);
        }
    }

    private void healthCheckOriginAndAnnounceListeners(RouteDatabase.Record host) {
        LOGGER.info("Health check to: " + host.name());
        Mono.from(healthCheckingFunction.apply(host.handler()))
                .subscribe(result -> {
                    if (isActive(host.tags()) && !result) {
                        // Set origin to failed
                        routeDb.replaceTag(host.name(), "status=active", "status=inactive");
                    } else if (isInactive(host.tags()) && result) {
                        // Set origin to healthy
                        routeDb.replaceTag(host.name(), "status=inactive", "status=active");
                    }
                });
    }

    private boolean isActive(Set<String> tags) {
        assert(tags.stream()
                .filter(tag -> tag.startsWith("status="))
                .count() <= 1);
        return tags.stream()
                .anyMatch(tag -> tag.equals("status=active"));

    }
    private boolean isInactive(Set<String> tags) {
        assert(tags.stream()
                .filter(tag -> tag.startsWith("status="))
                .count() <= 1);
        return tags.stream()
                .anyMatch(tag -> tag.equals("status=inactive"));
    }

    private boolean isEnabled(Set<String> tags) {
        assert(tags.stream()
                .filter(tag -> tag.startsWith("status="))
                .count() <= 1);

        return tags.stream()
                .noneMatch(tag -> tag.equals("status=disabled"));
    }

}

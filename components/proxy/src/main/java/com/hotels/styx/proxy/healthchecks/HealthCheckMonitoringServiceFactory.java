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
package com.hotels.styx.proxy.healthchecks;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hotels.styx.api.Environment;
import com.hotels.styx.api.Eventual;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.HttpRequest;
import com.hotels.styx.api.configuration.Configuration;
import com.hotels.styx.api.configuration.RouteDatabase;
import com.hotels.styx.api.configuration.ServiceFactory;
import com.hotels.styx.api.extension.service.HealthCheckConfig;
import com.hotels.styx.api.extension.service.spi.StyxService;
import com.hotels.styx.client.healthcheck.Schedule;
import com.hotels.styx.server.HttpInterceptorContext;

import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class HealthCheckMonitoringServiceFactory implements ServiceFactory<StyxService> {
    @Override
    public StyxService create(Environment environment, RouteDatabase routeDb, Configuration serviceConfiguration) {
        String application = serviceConfiguration.get("application").orElseThrow(() -> new IllegalArgumentException("application is a mandatory field"));
        HealthCheckConfig healthCheckConfig = serviceConfiguration.get("monitor", HealthCheckConfig.class).orElseThrow(() -> new IllegalArgumentException("monitor is a mandatory field"));

        ScheduledExecutorService executorService = newScheduledThreadPool(1, new ThreadFactoryBuilder()
                .setNameFormat(format("STYX-ORIGINS-MONITOR-SERVICE-%s", requireNonNull(application)))
                .setDaemon(true)
                .build());

        Function<HttpHandler, Eventual<Boolean>> httpHandlerEventualFunction =
                handler -> handler.handle(HttpRequest.get(healthCheckConfig.uri().orElse("/")).build().stream(), HttpInterceptorContext.create())
                        .map(response -> true)
                        .onError(cause -> Eventual.of(false));

        return new HealthCheckMonitoringService(
                routeDb,
                application,
                executorService,
                httpHandlerEventualFunction,
                new Schedule(healthCheckConfig.intervalMillis(), MILLISECONDS),
                healthCheckConfig);
    }

}

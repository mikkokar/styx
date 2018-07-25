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
package com.hotels.styx.admin.dashboard;

import com.hotels.styx.Environment;
import com.hotels.styx.StyxConfig;
import com.hotels.styx.Version;
import com.hotels.styx.proxy.ConfigStore;
import org.slf4j.Logger;
import rx.Subscription;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static com.hotels.styx.StyxConfig.NO_JVM_ROUTE_SET;
import static java.util.Objects.requireNonNull;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Supplier of the {@link com.hotels.styx.admin.dashboard.DashboardData}.
 */
public class DashboardDataSupplier implements Supplier<DashboardData> {
    private static final Logger LOG = getLogger(DashboardDataSupplier.class);

    private volatile DashboardData data;
    private final Environment environment;
    private final ConfigStore configStore;
    private final String jvmRouteName;
    private final Version buildInfo;
    private final Map<String, Subscription> subscsriptions = new ConcurrentHashMap<>();


    public DashboardDataSupplier(Environment environment, StyxConfig styxConfig) {
        this.environment = requireNonNull(environment);
        this.jvmRouteName = styxConfig.get("jvmRouteName", String.class).orElse(NO_JVM_ROUTE_SET);
        this.buildInfo = environment.buildInfo();
        this.configStore = environment.configStore();
    }

    @Override
    public DashboardData get() {
        return DashboardData.create(environment.metricRegistry(), jvmRouteName, buildInfo.releaseVersion(), configStore);
    }
}

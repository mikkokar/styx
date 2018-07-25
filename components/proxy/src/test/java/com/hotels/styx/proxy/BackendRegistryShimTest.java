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
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.api.extension.service.spi.Registry.Changes;
import com.hotels.styx.support.matchers.LoggingTestSupport;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;

import static ch.qos.logback.classic.Level.WARN;
import static com.hotels.styx.api.extension.service.BackendService.newBackendServiceBuilder;
import static com.hotels.styx.support.matchers.LoggingEventMatcher.loggingEvent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class BackendRegistryShimTest {

    private final BackendService landingApp = newBackendServiceBuilder().id("landing").build();
    private final BackendService shoppingApp = newBackendServiceBuilder().id("shopping").build();

    ConfigStore configStore;
    private BackendRegistryShim shim;
    private LoggingTestSupport logger;

    @BeforeMethod
    public void setUp() {
        configStore = new ConfigStore();
        shim = new BackendRegistryShim(configStore);
        logger = new LoggingTestSupport(BackendRegistryShim.class);
    }

    @AfterMethod
    public void tearDown() {
        logger.stop();
    }

    @Test
    public void warnOnInconsistentInputRemovedNonExistingBackend() {
        configStore.applications().set(ImmutableList.of("shopping"));

        shim.onChange(new Changes.Builder<BackendService>()
                .removed(landingApp)
                .build());

        assertThat(logger.log(), contains(
                loggingEvent(WARN, "Backend services update: asked to remove an unknown application: 'landing'")));
    }

    @Test
    public void warnOnInconsistentInputAddedBackendServiceAlreadyExists() {
        configStore.applications().set(ImmutableList.of("landing", "shopping"));

        shim.onChange(new Changes.Builder<BackendService>()
                .added(shoppingApp)
                .build());

        assertThat(logger.log(), contains(
                loggingEvent(WARN, "Backend services update: asked to add an already existing application: 'shopping'")));
    }

    @Test
    public void warnOnInconsistentInputUpdatedNonExistingBackend() {
        configStore.applications().set(ImmutableList.of("shopping"));

        shim.onChange(new Changes.Builder<BackendService>()
                .updated(landingApp)
                .build());

        assertThat(logger.log(), contains(
                loggingEvent(WARN, "Backend services update: asked to update an unknown application: 'landing'")));
    }

    @Test
    public void addsNewAppsToConfiguration() {
        shim.onChange(new Changes.Builder<BackendService>()
                .added(landingApp, shoppingApp)
                .build());

        List<String> apps = configStore.applications().get();
        assertThat(apps, containsInAnyOrder("shopping", "landing"));

        BackendService landing = configStore.application().get("landing").orElse(null);
        assertThat(landing, is(landingApp));

        BackendService shopping = configStore.application().get("shopping").orElse(null);
        assertThat(shopping, is(shoppingApp));
    }

    @Test
    public void removesAppsFromConfiguration() {
        shim.onChange(new Changes.Builder<BackendService>()
                .added(landingApp, shoppingApp)
                .build());

        shim.onChange(new Changes.Builder<BackendService>()
                .removed(landingApp)
                .build());

        List<String> result = configStore.applications().get();
        assertThat(result, containsInAnyOrder("shopping"));

        BackendService landing = configStore.application().get("landing").orElse(null);
        assertThat(landing, nullValue());

        BackendService shopping = configStore.application().get("shopping").orElse(null);
        assertThat(shopping, is(shoppingApp));
    }


}
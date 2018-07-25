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

import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.extension.Origin;
import com.hotels.styx.api.extension.RemoteHost;
import com.hotels.styx.api.extension.loadbalancing.spi.LoadBalancingMetricSupplier;
import com.hotels.styx.api.extension.service.BackendService;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import rx.schedulers.Schedulers;

import static com.google.common.collect.Sets.newHashSet;
import static com.hotels.styx.api.extension.Origin.newOriginBuilder;
import static com.hotels.styx.api.extension.RemoteHost.remoteHost;
import static com.hotels.styx.api.extension.service.BackendService.newBackendServiceBuilder;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyIterable;
import static org.mockito.Mockito.mock;

public class ActiveOriginsTest {
    ConfigStore configStore;

    Origin originX1 = newOriginBuilder("localhost", 8081).id("x-01").build();
    Origin originX2 = newOriginBuilder("localhost", 8082).id("x-02").build();
    RemoteHost remoteHostX1 = remoteHost(originX1, mock(HttpHandler.class), mock(LoadBalancingMetricSupplier.class));
    RemoteHost remoteHostX2 = remoteHost(originX2, mock(HttpHandler.class), mock(LoadBalancingMetricSupplier.class));

    @BeforeMethod
    public void setUp() {
        configStore = new ConfigStore(Schedulers.immediate());
    }

    @Test
    public void exposesActiveOrigins() {
        configStore.addNewApplication("x", application("x", originX1, originX2));

        configStore.addOriginConfiguration("x", "x-01", originX1);
        configStore.remoteHost().set("x.x-01", remoteHostX1);

        configStore.addOriginConfiguration("x", "x-02", originX2);
        configStore.remoteHost().set("x.x-02", remoteHostX2);

        ActiveOrigins activeOrigins = new ActiveOrigins("x", configStore);

        assertThat(activeOrigins.snapshot(), containsInAnyOrder(remoteHostX1, remoteHostX2));
    }

    @Test
    public void addsNewOriginsToActiveSet() {
        ActiveOrigins activeOrigins = new ActiveOrigins("x", configStore);
        assertThat(activeOrigins.snapshot(), emptyIterable());

        configStore.addNewApplication("x", application("x", originX1, originX2));

        configStore.addOriginConfiguration("x", "x-01", originX1);
        configStore.remoteHost().set("x.x-01", remoteHostX1);

        assertThat(activeOrigins.snapshot(), containsInAnyOrder(remoteHostX1));

        configStore.addOriginConfiguration("x", "x-02", originX2);
        configStore.remoteHost().set("x.x-02", remoteHostX2);

        assertThat(activeOrigins.snapshot(), containsInAnyOrder(remoteHostX1, remoteHostX2));
    }


    @Test
    public void removesOriginsFromActiveSet() {
        ActiveOrigins activeOrigins = new ActiveOrigins("x", configStore);
        assertThat(activeOrigins.snapshot(), emptyIterable());

        configStore.addNewApplication("x", application("x", originX1));
        configStore.addOriginConfiguration("x", "x-01", originX1);
        configStore.remoteHost().set("x.x-01", remoteHostX1);

        assertThat(activeOrigins.snapshot(), containsInAnyOrder(remoteHostX1));

        configStore.removeOriginConfiguration("x", "x-01");
        configStore.remoteHost().unset("x.x-01");

        assertThat(activeOrigins.snapshot(), emptyIterable());
    }


    @Test
    public void doesntExposeInactiveOrigins() {
    }

    private static BackendService application(String id, Origin... origins) {
        return newBackendServiceBuilder()
                .id(id)
                .origins(newHashSet(origins))
                .build();
    }

}
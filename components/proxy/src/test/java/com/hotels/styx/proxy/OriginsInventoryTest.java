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

import com.hotels.styx.api.extension.Origin;
import com.hotels.styx.api.metrics.codahale.CodaHaleMetricRegistry;
import com.hotels.styx.client.StyxHostHttpClient;
import com.hotels.styx.client.connectionpool.ConnectionPool;
import com.hotels.styx.client.connectionpool.ConnectionPoolFactory;
import com.hotels.styx.client.connectionpool.stubs.StubConnectionFactory;
import com.hotels.styx.support.matchers.LoggingTestSupport;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import rx.schedulers.Schedulers;

import static com.hotels.styx.api.Id.GENERIC_APP;
import static com.hotels.styx.api.Id.id;
import static com.hotels.styx.api.extension.Origin.newOriginBuilder;
import static com.hotels.styx.api.extension.service.ConnectionPoolSettings.defaultConnectionPoolSettings;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OriginsInventoryTest {

    private ConnectionPool.Factory connectionFactory = connectionPoolFactory();

    private LoggingTestSupport logger;
    private StyxHostHttpClient.Factory hostClientFactory = pool -> mock(StyxHostHttpClient.class);
    private ConfigStore configStore;

    @BeforeMethod
    public void setUp() {
        configStore = new ConfigStore(Schedulers.immediate());
        logger = new LoggingTestSupport(OriginsInventory.class);
    }

    @AfterMethod
    public void stop() {
        logger.stop();
    }

    /*
     * Setting the origins
     */


    // Origin port number changes:
    // Origin ID remains, but port number changes
    //
    // --
    //


    @Test
    public void createsHostClientForExistingOrigins() {
        configStore.addOriginConfiguration("generic-app", "acme-01", newOriginBuilder("acme.com", 80).applicationId(GENERIC_APP).id("acme-01").build());
        configStore.addOriginConfiguration("generic-app", "acme-02", newOriginBuilder("acme.com", 80).applicationId(GENERIC_APP).id("acme-02").build());

        OriginsInventory inventory = new OriginsInventory(id("generic-app"), configStore, connectionFactory, hostClientFactory);

//        assertThat(gaugeValue("origins.generic-app.acme-01.status"), isValue(-1));

        // TODO: Assert it creates the client:
        assertThat(configStore.remoteHost().get("generic-app.acme-01").isPresent(), is(true));
        assertThat(configStore.remoteHost().get("generic-app.acme-02").isPresent(), is(true));
    }

    @Test
    public void createsHostClientForNewlyAddedOrigins() {
        OriginsInventory inventory = new OriginsInventory(id("generic-app"), configStore, connectionFactory, hostClientFactory);

        configStore.addOriginConfiguration("generic-app", "acme-01", newOriginBuilder("acme.com", 80).applicationId(GENERIC_APP).id("acme-01").build());
        configStore.addOriginConfiguration("generic-app", "acme-02", newOriginBuilder("acme.com", 80).applicationId(GENERIC_APP).id("acme-02").build());

//        assertThat(gaugeValue("origins.generic-app.acme-01.status"), isValue(-1));

        assertThat(configStore.remoteHost().get("generic-app.acme-01").isPresent(), is(true));
        assertThat(configStore.remoteHost().get("generic-app.acme-02").isPresent(), is(true));
    }

    @Test
    public void updatesHostClients() {
        configStore.addOriginConfiguration("generic-app", "acme-01", newOriginBuilder("acme.com", 80).applicationId(GENERIC_APP).id("acme-01").build());
        configStore.addOriginConfiguration("generic-app", "acme-02", newOriginBuilder("acme.com", 80).applicationId(GENERIC_APP).id("acme-02").build());

        connectionFactory = mock(ConnectionPool.Factory.class);
        when(connectionFactory.create(any(Origin.class))).thenReturn(connectionPoolFactory().create(Origin.newOriginBuilder("x",  8080).build()));

        OriginsInventory inventory = new OriginsInventory(id("generic-app"), configStore, connectionFactory, hostClientFactory);

        configStore.origin().set("generic-app.acme-02", newOriginBuilder("new.acme.com", 88).applicationId(GENERIC_APP).id("acme-02").build());

        verify(connectionFactory).create(eq(newOriginBuilder("acme.com", 80).applicationId(GENERIC_APP).id("acme-02").build()));
        verify(connectionFactory).create(eq(newOriginBuilder("acme.com", 80).applicationId(GENERIC_APP).id("acme-01").build()));
        verify(connectionFactory).create(eq(newOriginBuilder("new.acme.com", 88).applicationId(GENERIC_APP).id("acme-02").build()));

//        assertThat(gaugeValue("origins.generic-app.acme-01.status"), isValue(-1));
        assertThat(configStore.remoteHost().get("generic-app.acme-01").isPresent(), is(true));
        assertThat(configStore.remoteHost().get("generic-app.acme-02").isPresent(), is(true));
    }

    @Test
    public void removesHostClients() {
        configStore.addOriginConfiguration("generic-app", "acme-01", newOriginBuilder("acme.com", 80).applicationId(GENERIC_APP).id("acme-01").build());
        configStore.addOriginConfiguration("generic-app", "acme-02", newOriginBuilder("acme.com", 80).applicationId(GENERIC_APP).id("acme-02").build());

        connectionFactory = mock(ConnectionPool.Factory.class);
        when(connectionFactory.create(any(Origin.class))).thenReturn(connectionPoolFactory().create(Origin.newOriginBuilder("x",  8080).build()));

        OriginsInventory inventory = new OriginsInventory(id("generic-app"), configStore, connectionFactory, hostClientFactory);

        configStore.removeOriginConfiguration("generic-app", "acme-02");
        configStore.removeOriginConfiguration("generic-app", "acme-01");

        // TODO: assert it closes the client:
        assertThat(configStore.remoteHost().get("generic-app.acme-01").isPresent(), is(false));
        assertThat(configStore.remoteHost().get("generic-app.acme-02").isPresent(), is(false));
    }

    private static ConnectionPoolFactory connectionPoolFactory() {
        return new ConnectionPoolFactory.Builder()
                .connectionFactory(new StubConnectionFactory())
                .connectionPoolSettings(defaultConnectionPoolSettings())
                .metricRegistry(new CodaHaleMetricRegistry())
                .build();
    }

}
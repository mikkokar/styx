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
import com.hotels.styx.Environment;
import com.hotels.styx.api.HttpClient;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.HttpRequest;
import com.hotels.styx.api.HttpResponse;
import com.hotels.styx.api.metrics.codahale.CodaHaleMetricRegistry;
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.api.extension.service.spi.Registry;
import com.hotels.styx.client.OriginStatsFactory;
import com.hotels.styx.client.OriginsInventory;
import com.hotels.styx.configstore.ConfigStore;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import rx.Observable;
import rx.schedulers.Schedulers;

import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static com.hotels.styx.api.HttpRequest.get;
import static com.hotels.styx.api.HttpResponse.response;
import static com.hotels.styx.api.extension.Origin.newOriginBuilder;
import static com.hotels.styx.api.HttpResponseStatus.OK;
import static com.hotels.styx.api.extension.service.BackendService.newBackendServiceBuilder;
import static com.hotels.styx.client.StyxHeaderConfig.ORIGIN_ID_DEFAULT;
import static com.hotels.styx.support.matchers.IsOptional.isValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static rx.Observable.just;

public class BackendServicesRouterTest {
    private static final String APP_A = "appA";
    private static final String APP_B = "appB";
    private static final String APP_C = "appC";

    private final BackendServiceClientFactory serviceClientFactory =
            (backendService, originsInventory, originStatsFactory) -> request -> responseWithOriginIdHeader(backendService);
    private HttpInterceptor.Context context;

    private Environment environment;
    private ConfigStore configStore;

    @BeforeMethod
    public void before() {
        configStore = new ConfigStore(Schedulers.immediate());
        environment = new Environment.Builder()
                .configStore(configStore)
                .build();
    }

    @Test
    public void registersAllRoutes() {
        configStore.set("apps.appA", appA().newCopy().path("/headers").build());
        configStore.set("apps.appB", appB().newCopy().path("/cookies").build());
        configStore.set("apps.appC", appC().newCopy().path("/badheaders").build());
        configStore.set("apps", ImmutableList.of("appA", "appB", "appC"));

        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);

        assertThat(router.routes().keySet(), containsInAnyOrder("/cookies", "/headers", "/badheaders"));
    }

    @Test
    public void selectsServiceBasedOnPath() throws Exception {
        configStore.set("apps.appA", appA().newCopy().path("/").build());
        configStore.set("apps.appB", appB().newCopy().path("/appB/hotel/details.html").build());
        configStore.set("apps", ImmutableList.of("appA", "appB"));

        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);

        HttpRequest request = get("/appB/hotel/details.html").build();
        Optional<HttpHandler> route = router.route(request);

        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_B));
    }

    @Test
    public void selectsApplicationBasedOnPathIfAppsAreProvidedInOppositeOrder() throws Exception {
        configStore.set("apps.appA", appA().newCopy().path("/").build());
        configStore.set("apps.appB", appB().newCopy().path("/appB/hotel/details.html").build());
        configStore.set("apps", ImmutableList.of("appA", "appB"));

        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);

        HttpRequest request = get("/appB/hotel/details.html").build();
        Optional<HttpHandler> route = router.route(request);

        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_B));
    }


    @Test
    public void selectsUsingSingleSlashPath() throws Exception {
        configStore.set("apps.appA", appA().newCopy().path("/").build());
        configStore.set("apps.appB", appB().newCopy().path("/appB/hotel/details.html").build());
        configStore.set("apps", ImmutableList.of("appA", "appB"));

        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);

        HttpRequest request = get("/").build();
        Optional<HttpHandler> route = router.route(request);

        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_A));
    }

    // TBD: simiolar to others?
    @Test
    public void selectsUsingSingleSlashPathIfAppsAreProvidedInOppositeOrder() throws Exception {
        configStore.set("apps.appA", appA().newCopy().path("/").build());
        configStore.set("apps.appB", appB().newCopy().path("/appB/hotel/details.html").build());
        configStore.set("apps", ImmutableList.of("appA", "appB"));

        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);

        HttpRequest request = get("/").build();
        Optional<HttpHandler> route = router.route(request);

        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_A));
    }

    @Test
    public void selectsUsingPathWithNoSubsequentCharacters() throws Exception {
        configStore.set("apps.appA", appA().newCopy().path("/").build());
        configStore.set("apps.appB", appB().newCopy().path("/appB/").build());
        configStore.set("apps", ImmutableList.of("appA", "appB"));

        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);

        HttpRequest request = get("/appB/").build();
        Optional<HttpHandler> route = router.route(request);

        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_B));
    }

    @Test
    public void doesNotMatchRequestIfFinalSlashIsMissing() {
        configStore.set("apps.appB", appB().newCopy().path("/appB/hotel/details.html").build());
        configStore.set("apps", ImmutableList.of("appB"));

        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);

        HttpRequest request = get("/ba/").build();
        Optional<HttpHandler> route = router.route(request);
        System.out.println("route: " + route);

        assertThat(route, is(Optional.empty()));
    }

    @Test
    public void throwsExceptionWhenNoApplicationMatches() {
        configStore.set("apps.appB", appB().newCopy().path("/appB/hotel/details.html").build());
        configStore.set("apps", ImmutableList.of("appB"));

        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);

        HttpRequest request = get("/qwertyuiop").build();
        assertThat(router.route(request), is(Optional.empty()));
    }

    @Test
    public void removesExistingServicesBeforeAddingNewOnes() throws Exception {
        configStore.set("apps.appB", appB());
        configStore.set("apps", ImmutableList.of("appB"));

        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);

        configStore.set("apps.X", newBackendServiceBuilder(appB()).id("X").build());
        configStore.set("apps", ImmutableList.of("X"));

        HttpRequest request = get("/appB/").build();
        Optional<HttpHandler> route = router.route(request);
        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue("X"));
    }

    @Test
    public void updatesRoutesOnBackendServicesChange() throws Exception {
        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);

        HttpRequest request = get("/appB/").build();

        configStore.set("apps.appB", appB());
        configStore.set("apps", ImmutableList.of("appB"));

        Optional<HttpHandler> route = router.route(request);
        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_B));

        configStore.set("apps", ImmutableList.of("appB"));

        Optional<HttpHandler> route2 = router.route(request);
        assertThat(proxyTo(route2, request).header(ORIGIN_ID_DEFAULT), isValue(APP_B));
    }

    @Test
    public void closesClientWhenBackendServicesAreUpdated() {
        HttpClient firstClient = mock(HttpClient.class);
        HttpClient secondClient = mock(HttpClient.class);

        BackendServiceClientFactory clientFactory = mock(BackendServiceClientFactory.class);
        when(clientFactory.createClient(any(BackendService.class), any(OriginsInventory.class), any(OriginStatsFactory.class)))
                .thenReturn(firstClient)
                .thenReturn(secondClient);

        BackendServicesRouter router = new BackendServicesRouter(clientFactory, environment);

        BackendService bookingApp = appB();
        configStore.set("apps.appB", bookingApp);
        configStore.set("apps", ImmutableList.of("appB"));

        ArgumentCaptor<OriginsInventory> originsInventory = forClass(OriginsInventory.class);
        verify(clientFactory).createClient(eq(bookingApp), originsInventory.capture(), any(OriginStatsFactory.class));

        BackendService bookingAppMinusOneOrigin = bookingAppMinusOneOrigin();
        configStore.set("apps.appB", bookingAppMinusOneOrigin);

        assertThat(originsInventory.getValue().closed(), is(true));
        verify(clientFactory).createClient(eq(bookingAppMinusOneOrigin), any(OriginsInventory.class), any(OriginStatsFactory.class));
    }

    @Test
    public void closesClientWhenBackendServicesAreRemoved() {
        HttpClient firstClient = mock(HttpClient.class);
        HttpClient secondClient = mock(HttpClient.class);

        ArgumentCaptor<OriginsInventory> originsInventory = forClass(OriginsInventory.class);
        BackendServiceClientFactory clientFactory = mock(BackendServiceClientFactory.class);
        when(clientFactory.createClient(any(BackendService.class), any(OriginsInventory.class), any(OriginStatsFactory.class)))
                .thenReturn(firstClient)
                .thenReturn(secondClient);

        BackendServicesRouter router = new BackendServicesRouter(clientFactory, environment);

        BackendService bookingApp = appB();
        configStore.set("apps.appB", bookingApp);
        configStore.set("apps", ImmutableList.of("appB"));

        verify(clientFactory).createClient(eq(bookingApp), originsInventory.capture(), any(OriginStatsFactory.class));

        configStore.set("apps", ImmutableList.of("appB"));

        assertThat(originsInventory.getValue().closed(), is(true));
    }

    // This test exists due to a real bug we had when reloading in prod
    @Test
    public void deregistersAndReregistersMetricsAppropriately() {
        CodaHaleMetricRegistry metrics = new CodaHaleMetricRegistry();

        Environment environment = new Environment.Builder()
                .configStore(configStore)
                .metricsRegistry(metrics)
                .build();
        BackendServicesRouter router = new BackendServicesRouter(
                new StyxBackendServiceClientFactory(environment), environment);

        configStore.set("apps.appB", backendService(APP_B, "/appB/", 9094, "appB-01", 9095, "appB-02"));
        configStore.set("apps", ImmutableList.of("appB"));

        assertThat(metrics.getGauges().get("origins.appB.appB-01.status").getValue(), is(1));
        assertThat(metrics.getGauges().get("origins.appB.appB-02.status").getValue(), is(1));

        BackendService appMinusOneOrigin = backendService(APP_B, "/appB/", 9094, "appB-01");

        configStore.set("apps.appB", appMinusOneOrigin);

        assertThat(metrics.getGauges().get("origins.appB.appB-01.status").getValue(), is(1));
        assertThat(metrics.getGauges().get("origins.appB.appB-02.status"), is(nullValue()));
    }

    private HttpResponse proxyTo(Optional<HttpHandler> pipeline, HttpRequest request) throws ExecutionException, InterruptedException {
        return pipeline.get().handle(request, context).asCompletableFuture().get();
    }

    private static Registry.Changes<BackendService> added(BackendService... backendServices) {
        return new Registry.Changes.Builder<BackendService>().added(backendServices).build();
    }

    private static Registry.Changes<BackendService> updated(BackendService... backendServices) {
        return new Registry.Changes.Builder<BackendService>().updated(backendServices).build();
    }

    private static Registry.Changes<BackendService> removed(BackendService... backendServices) {
        return new Registry.Changes.Builder<BackendService>().removed(backendServices).build();
    }

    private static BackendService appA() {
        return newBackendServiceBuilder()
                .id(APP_A)
                .path("/")
                .origins(newOriginBuilder("localhost", 9090).applicationId(APP_A).id("appA-01").build())
                .build();
    }

    private static BackendService appB() {
        return newBackendServiceBuilder()
                .id(APP_B)
                .path("/appB/")
                .origins(
                        newOriginBuilder("localhost", 9094).applicationId(APP_B).id("appB-01").build(),
                        newOriginBuilder("localhost", 9095).applicationId(APP_B).id("appB-02").build())
                .build();
    }

    private static BackendService appC() {
        return newBackendServiceBuilder()
                .id(APP_C)
                .path("/appC/")
                .origins(
                        newOriginBuilder("localhost", 9084).applicationId(APP_C).id("appC-01").build(),
                        newOriginBuilder("localhost", 9085).applicationId(APP_C).id("appC-02").build())
                .build();
    }

    private static BackendService backendService(String id, String path, int originPort1, String originId1, int originPort2, String originId2) {
        return newBackendServiceBuilder()
                .id(id)
                .path(path)
                .origins(
                        newOriginBuilder("localhost", originPort1).applicationId(id).id(originId1).build(),
                        newOriginBuilder("localhost", originPort2).applicationId(id).id(originId2).build())
                .build();
    }

    private static BackendService bookingAppMinusOneOrigin() {
        return newBackendServiceBuilder()
                .id(APP_B)
                .path("/appB/")
                .origins(newOriginBuilder("localhost", 9094).applicationId(APP_B).id("appB-01").build())
                .build();
    }

    private static BackendService backendService(String id, String path, int originPort, String originId) {
        return newBackendServiceBuilder()
                .id(id)
                .path(path)
                .origins(newOriginBuilder("localhost", originPort).applicationId(id).id(originId).build())
                .build();
    }

    private static Observable<HttpResponse> responseWithOriginIdHeader(BackendService backendService) {
        return just(response(OK)
                .header(ORIGIN_ID_DEFAULT, backendService.id())
                .build());
    }
}
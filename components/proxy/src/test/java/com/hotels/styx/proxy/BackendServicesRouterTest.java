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
import com.hotels.styx.api.FullHttpRequest;
import com.hotels.styx.api.FullHttpResponse;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.HttpResponseStatus;
import com.hotels.styx.api.StyxObservable;
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.common.StyxFutures;
import com.hotels.styx.configstore.ConfigStore;
import com.hotels.styx.server.HttpInterceptorContext;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import rx.schedulers.Schedulers;

import java.util.Optional;

import static com.hotels.styx.api.HttpResponse.response;
import static com.hotels.styx.api.HttpResponseStatus.BAD_GATEWAY;
import static com.hotels.styx.api.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static com.hotels.styx.api.HttpResponseStatus.OK;
import static com.hotels.styx.api.extension.Origin.newOriginBuilder;
import static com.hotels.styx.api.extension.service.BackendService.newBackendServiceBuilder;
import static com.hotels.styx.client.StyxHeaderConfig.ORIGIN_ID_DEFAULT;
import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static rx.Observable.just;

public class BackendServicesRouterTest {
    private static final String APP_A = "appA";
    private static final String APP_B = "appB";
    private static final String APP_C = "appC";


    private final BackendServiceClientFactory serviceClientFactory =
            (backendService, originsInventory, originStatsFactory) -> request -> just(response(OK)
                    .header(ORIGIN_ID_DEFAULT, backendService.id())
                    .build());
    private HttpInterceptor.Context context;
    private FullHttpRequest request = FullHttpRequest.get("/").build();

    private FullHttpResponse internalServerError = FullHttpResponse.response(INTERNAL_SERVER_ERROR).build();
    private HttpHandler internalServerErrorHandler = (request, context) -> StyxObservable.of(internalServerError.toStreamingResponse());

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
    public void enablesRouteWhenApplicationAndHandlerAreBothReady() {
        configStore.set("apps.a", app("a", "/a"));
        configStore.set("apps.b", app("b", "/b"));
        configStore.set("apps", ImmutableList.of("a", "b"));

        BackendServicesRouter router = new BackendServicesRouter(environment);

        assertThat(testRoute(router, "/a"), is(Optional.empty()));
        assertThat(testRoute(router, "/b"), is(Optional.empty()));

        HttpHandler handlerA = handlerTo("a");

        configStore.set("routing.objects.a", handlerA);

        assertThat(testRoute(router, "/a"), is(Optional.of(OK)));
        assertThat(testRoute(router, "/b"), is(Optional.empty()));
    }

    @Test
    public void appFirstThenRoute() {
        BackendServicesRouter router = new BackendServicesRouter(environment);
        HttpHandler handlerA = handlerTo("a");

        configStore.set("apps.a", app("a", "/a"));
        configStore.set("apps", ImmutableList.of("a"));

        assertThat(testRoute(router, "/a"), is(Optional.empty()));

        configStore.set("routing.objects.a", handlerA);

        assertThat(testRoute(router, "/a"), is(Optional.of(OK)));
    }

    @Test
    public void routeFirstThenApp() {
        BackendServicesRouter router = new BackendServicesRouter(environment);
        HttpHandler handlerA = handlerTo("a");

        configStore.set("routing.objects.a", handlerA);

        assertThat(testRoute(router, "/a"), is(Optional.empty()));

        configStore.set("apps.a", app("a", "/a"));
        configStore.set("apps", ImmutableList.of("a"));

        assertThat(testRoute(router, "/a"), is(Optional.of(OK)));
    }

    @Test
    public void removesRouteWhenAppDisappears() {
        BackendServicesRouter router = new BackendServicesRouter(environment);
        HttpHandler handlerA = handlerTo("a");

        configStore.set("routing.objects.a", handlerA);
        configStore.set("apps.a", app("a", "/a"));
        configStore.set("apps", ImmutableList.of("a"));

        assertThat(testRoute(router, "/a"), is(Optional.of(OK)));

        configStore.unset("apps.a");

        assertThat(testRoute(router, "/a"), is(Optional.empty()));
    }

    @Test
    public void removesRouteWhenRouteDisappears() {
        BackendServicesRouter router = new BackendServicesRouter(environment);
        HttpHandler handlerA = handlerTo("a");

        configStore.set("routing.objects.a", handlerA);
        configStore.set("apps.a", app("a", "/a"));
        configStore.set("apps", ImmutableList.of("a"));

        assertThat(testRoute(router, "/a"), is(Optional.of(OK)));

        configStore.unset("route.objects.a");
        configStore.unset("apps.a");

        assertThat(testRoute(router, "/a"), is(Optional.empty()));
    }

    private static HttpHandler handlerTo(String a) {
        return (request, context) -> StyxObservable.of(FullHttpResponse.response(OK)
                .header("X-Origin-Id", a)
                .build().toStreamingResponse());
    }


    private static BackendService app(String id, String path) {
        return newBackendServiceBuilder()
                .id(id)
                .path(path)
                .origins(newOriginBuilder("localhost", 9090).applicationId(id).id(format("%s-01", id)).build())
                .build();
    }

    private static Optional<HttpResponseStatus> testRoute(BackendServicesRouter router, String path) {
        FullHttpRequest request = FullHttpRequest.get(path).build();

        return router.route(request.toStreamingRequest())
                .map(handler -> StyxFutures.await(
                        handler.handle(request.toStreamingRequest(), HttpInterceptorContext.create())
                                .flatMap(response -> response.toFullResponse(100000))
                                .map(FullHttpResponse::status)
                                .onError(cause -> StyxObservable.of(BAD_GATEWAY))
                                .asCompletableFuture()
                        )
                );
    }


//
//
//    @Test
//    public void selectsServiceBasedOnPath() throws Exception {
//        configStore.set("apps.appA", appA().newCopy().path("/").build());
//        configStore.set("apps.appB", appB().newCopy().path("/appB/hotel/details.html").build());
//        configStore.set("apps", ImmutableList.of("appA", "appB"));
//
//        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);
//
//        HttpRequest request = get("/appB/hotel/details.html").build();
//        Optional<HttpHandler> route = router.route(request);
//
//        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_B));
//    }
//
//    @Test
//    public void selectsApplicationBasedOnPathIfAppsAreProvidedInOppositeOrder() throws Exception {
//        configStore.set("apps.appA", appA().newCopy().path("/").build());
//        configStore.set("apps.appB", appB().newCopy().path("/appB/hotel/details.html").build());
//        configStore.set("apps", ImmutableList.of("appA", "appB"));
//
//        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);
//
//        HttpRequest request = get("/appB/hotel/details.html").build();
//        Optional<HttpHandler> route = router.route(request);
//
//        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_B));
//    }
//
//
//    @Test
//    public void selectsUsingSingleSlashPath() throws Exception {
//        configStore.set("apps.appA", appA().newCopy().path("/").build());
//        configStore.set("apps.appB", appB().newCopy().path("/appB/hotel/details.html").build());
//        configStore.set("apps", ImmutableList.of("appA", "appB"));
//
//        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);
//
//        HttpRequest request = get("/").build();
//        Optional<HttpHandler> route = router.route(request);
//
//        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_A));
//    }
//
//    // TBD: simiolar to others?
//    @Test
//    public void selectsUsingSingleSlashPathIfAppsAreProvidedInOppositeOrder() throws Exception {
//        configStore.set("apps.appA", appA().newCopy().path("/").build());
//        configStore.set("apps.appB", appB().newCopy().path("/appB/hotel/details.html").build());
//        configStore.set("apps", ImmutableList.of("appA", "appB"));
//
//        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);
//
//        HttpRequest request = get("/").build();
//        Optional<HttpHandler> route = router.route(request);
//
//        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_A));
//    }
//
//    @Test
//    public void selectsUsingPathWithNoSubsequentCharacters() throws Exception {
//        configStore.set("apps.appA", appA().newCopy().path("/").build());
//        configStore.set("apps.appB", appB().newCopy().path("/appB/").build());
//        configStore.set("apps", ImmutableList.of("appA", "appB"));
//
//        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);
//
//        HttpRequest request = get("/appB/").build();
//        Optional<HttpHandler> route = router.route(request);
//
//        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_B));
//    }
//
//    @Test
//    public void doesNotMatchRequestIfFinalSlashIsMissing() {
//        configStore.set("apps.appB", appB().newCopy().path("/appB/hotel/details.html").build());
//        configStore.set("apps", ImmutableList.of("appB"));
//
//        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);
//
//        HttpRequest request = get("/ba/").build();
//        Optional<HttpHandler> route = router.route(request);
//        System.out.println("route: " + route);
//
//        assertThat(route, is(Optional.empty()));
//    }
//
//    @Test
//    public void throwsExceptionWhenNoApplicationMatches() {
//        configStore.set("apps.appB", appB().newCopy().path("/appB/hotel/details.html").build());
//        configStore.set("apps", ImmutableList.of("appB"));
//
//        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);
//
//        HttpRequest request = get("/qwertyuiop").build();
//        assertThat(router.route(request), is(Optional.empty()));
//    }
//
//    @Test
//    public void removesExistingServicesBeforeAddingNewOnes() throws Exception {
//        configStore.set("apps.appB", appB());
//        configStore.set("apps", ImmutableList.of("appB"));
//
//        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);
//
//        configStore.set("apps.X", newBackendServiceBuilder(appB()).id("X").build());
//        configStore.set("apps", ImmutableList.of("X"));
//
//        HttpRequest request = get("/appB/").build();
//        Optional<HttpHandler> route = router.route(request);
//        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue("X"));
//    }
//
//    @Test
//    public void updatesRoutesOnBackendServicesChange() throws Exception {
//        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory, environment);
//
//        HttpRequest request = get("/appB/").build();
//
//        configStore.set("apps.appB", appB());
//        configStore.set("apps", ImmutableList.of("appB"));
//
//        Optional<HttpHandler> route = router.route(request);
//        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_B));
//
//        configStore.set("apps", ImmutableList.of("appB"));
//
//        Optional<HttpHandler> route2 = router.route(request);
//        assertThat(proxyTo(route2, request).header(ORIGIN_ID_DEFAULT), isValue(APP_B));
//    }
//
//    @Test
//    public void closesClientWhenBackendServicesAreUpdated() {
//        HttpClient firstClient = mock(HttpClient.class);
//        HttpClient secondClient = mock(HttpClient.class);
//
//        BackendServiceClientFactory clientFactory = mock(BackendServiceClientFactory.class);
//        when(clientFactory.createClient(any(BackendService.class), any(OriginsInventory.class), any(OriginStatsFactory.class)))
//                .thenReturn(firstClient)
//                .thenReturn(secondClient);
//
//        BackendServicesRouter router = new BackendServicesRouter(clientFactory, environment);
//
//        BackendService bookingApp = appB();
//        configStore.set("apps.appB", bookingApp);
//        configStore.set("apps", ImmutableList.of("appB"));
//
//        ArgumentCaptor<OriginsInventory> originsInventory = forClass(OriginsInventory.class);
//        verify(clientFactory).createClient(eq(bookingApp), originsInventory.capture(), any(OriginStatsFactory.class));
//
//        BackendService bookingAppMinusOneOrigin = bookingAppMinusOneOrigin();
//        configStore.set("apps.appB", bookingAppMinusOneOrigin);
//
//        assertThat(originsInventory.getValue().closed(), is(true));
//        verify(clientFactory).createClient(eq(bookingAppMinusOneOrigin), any(OriginsInventory.class), any(OriginStatsFactory.class));
//    }
//
//    @Test
//    public void closesClientWhenBackendServicesAreRemoved() {
//        HttpClient firstClient = mock(HttpClient.class);
//        HttpClient secondClient = mock(HttpClient.class);
//
//        ArgumentCaptor<OriginsInventory> originsInventory = forClass(OriginsInventory.class);
//        BackendServiceClientFactory clientFactory = mock(BackendServiceClientFactory.class);
//        when(clientFactory.createClient(any(BackendService.class), any(OriginsInventory.class), any(OriginStatsFactory.class)))
//                .thenReturn(firstClient)
//                .thenReturn(secondClient);
//
//        BackendServicesRouter router = new BackendServicesRouter(clientFactory, environment);
//
//        BackendService bookingApp = appB();
//        configStore.set("apps.appB", bookingApp);
//        configStore.set("apps", ImmutableList.of("appB"));
//
//        verify(clientFactory).createClient(eq(bookingApp), originsInventory.capture(), any(OriginStatsFactory.class));
//
//        configStore.set("apps", ImmutableList.of("appB"));
//
//        assertThat(originsInventory.getValue().closed(), is(true));
//    }
//
//    // This test exists due to a real bug we had when reloading in prod
//    @Test
//    public void deregistersAndReregistersMetricsAppropriately() {
//        CodaHaleMetricRegistry metrics = new CodaHaleMetricRegistry();
//
//        Environment environment = new Environment.Builder()
//                .configStore(configStore)
//                .metricsRegistry(metrics)
//                .build();
//        BackendServicesRouter router = new BackendServicesRouter(
//                new StyxBackendServiceClientFactory(environment), environment);
//
//        configStore.set("apps.appB", backendService(APP_B, "/appB/", 9094, "appB-01", 9095, "appB-02"));
//        configStore.set("apps", ImmutableList.of("appB"));
//
//        assertThat(metrics.getGauges().get("origins.appB.appB-01.status").getValue(), is(1));
//        assertThat(metrics.getGauges().get("origins.appB.appB-02.status").getValue(), is(1));
//
//        BackendService appMinusOneOrigin = backendService(APP_B, "/appB/", 9094, "appB-01");
//
//        configStore.set("apps.appB", appMinusOneOrigin);
//
//        assertThat(metrics.getGauges().get("origins.appB.appB-01.status").getValue(), is(1));
//        assertThat(metrics.getGauges().get("origins.appB.appB-02.status"), is(nullValue()));
//    }
//
//    private HttpResponse proxyTo(Optional<HttpHandler> pipeline, HttpRequest request) throws ExecutionException, InterruptedException {
//        return pipeline.get().handle(request, context).asCompletableFuture().get();
//    }
//
//    private static Registry.Changes<BackendService> added(BackendService... backendServices) {
//        return new Registry.Changes.Builder<BackendService>().added(backendServices).build();
//    }
//
//    private static Registry.Changes<BackendService> updated(BackendService... backendServices) {
//        return new Registry.Changes.Builder<BackendService>().updated(backendServices).build();
//    }
//
//    private static Registry.Changes<BackendService> removed(BackendService... backendServices) {
//        return new Registry.Changes.Builder<BackendService>().removed(backendServices).build();
//    }
//
//    private static BackendService appA() {
//        return newBackendServiceBuilder()
//                .id(APP_A)
//                .path("/")
//                .origins(newOriginBuilder("localhost", 9090).applicationId(APP_A).id("appA-01").build())
//                .build();
//    }
//
//    private static BackendService appB() {
//        return newBackendServiceBuilder()
//                .id(APP_B)
//                .path("/appB/")
//                .origins(
//                        newOriginBuilder("localhost", 9094).applicationId(APP_B).id("appB-01").build(),
//                        newOriginBuilder("localhost", 9095).applicationId(APP_B).id("appB-02").build())
//                .build();
//    }
//
//    private static BackendService appC() {
//        return newBackendServiceBuilder()
//                .id(APP_C)
//                .path("/appC/")
//                .origins(
//                        newOriginBuilder("localhost", 9084).applicationId(APP_C).id("appC-01").build(),
//                        newOriginBuilder("localhost", 9085).applicationId(APP_C).id("appC-02").build())
//                .build();
//    }
//
//    private static BackendService backendService(String id, String path, int originPort1, String originId1, int originPort2, String originId2) {
//        return newBackendServiceBuilder()
//                .id(id)
//                .path(path)
//                .origins(
//                        newOriginBuilder("localhost", originPort1).applicationId(id).id(originId1).build(),
//                        newOriginBuilder("localhost", originPort2).applicationId(id).id(originId2).build())
//                .build();
//    }
//
//    private static BackendService bookingAppMinusOneOrigin() {
//        return newBackendServiceBuilder()
//                .id(APP_B)
//                .path("/appB/")
//                .origins(newOriginBuilder("localhost", 9094).applicationId(APP_B).id("appB-01").build())
//                .build();
//    }
//
//    private static BackendService backendService(String id, String path, int originPort, String originId) {
//        return newBackendServiceBuilder()
//                .id(id)
//                .path(path)
//                .origins(newOriginBuilder("localhost", originPort).applicationId(id).id(originId).build())
//                .build();
//    }
//
//    private static Observable<HttpResponse> responseWithOriginIdHeader(BackendService backendService) {
//        return just(response(OK)
//                .header(ORIGIN_ID_DEFAULT, backendService.id())
//                .build());
//    }
}
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
import com.hotels.styx.api.FullHttpRequest;
import com.hotels.styx.api.FullHttpResponse;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.HttpResponseStatus;
import com.hotels.styx.api.StyxObservable;
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.common.StyxFutures;
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
        configStore.addNewApplication("a", app("a", "/a"));
        configStore.addNewApplication("b", app("b", "/b"));

        BackendServicesRouter router = new BackendServicesRouter(environment);

        assertThat(testRoute(router, "/a"), is(Optional.empty()));
        assertThat(testRoute(router, "/b"), is(Optional.empty()));

        HttpHandler handlerA = handlerTo("a");

        configStore.routingObject().set("a", handlerA);

        assertThat(testRoute(router, "/a"), is(Optional.of(OK)));
        assertThat(testRoute(router, "/b"), is(Optional.empty()));
    }

    @Test
    public void appFirstThenRoute() {
        BackendServicesRouter router = new BackendServicesRouter(environment);
        HttpHandler handlerA = handlerTo("a");

        configStore.addNewApplication("a", app("a", "/a"));

        assertThat(testRoute(router, "/a"), is(Optional.empty()));

        configStore.routingObject().set("a", handlerA);

        assertThat(testRoute(router, "/a"), is(Optional.of(OK)));
    }

    @Test
    public void routeFirstThenApp() {
        BackendServicesRouter router = new BackendServicesRouter(environment);
        HttpHandler handlerA = handlerTo("a");

        configStore.routingObject().set("a", handlerA);

        assertThat(testRoute(router, "/a"), is(Optional.empty()));

        configStore.addNewApplication("a", app("a", "/a"));

        assertThat(testRoute(router, "/a"), is(Optional.of(OK)));
    }

    @Test
    public void removesRouteWhenAppDisappears() {
        BackendServicesRouter router = new BackendServicesRouter(environment);
        HttpHandler handlerA = handlerTo("a");

        configStore.routingObject().set("a", handlerA);
        configStore.addNewApplication("a", app("a", "/a"));

        assertThat(testRoute(router, "/a"), is(Optional.of(OK)));

        configStore.removeApplication("a");

        assertThat(testRoute(router, "/a"), is(Optional.empty()));
    }

    @Test
    public void removesRouteWhenRouteDisappears() {
        BackendServicesRouter router = new BackendServicesRouter(environment);
        HttpHandler handlerA = handlerTo("a");

        configStore.routingObject().set("a", handlerA);
        configStore.addNewApplication("a", app("a", "/a"));

        assertThat(testRoute(router, "/a"), is(Optional.of(OK)));

        configStore.routingObject().unset("a");
        configStore.removeApplication("a");

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

}
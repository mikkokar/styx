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
import com.hotels.styx.api.StyxObservable;
import com.hotels.styx.api.HttpResponseStatus;
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.common.StyxFutures;
import com.hotels.styx.server.HttpInterceptorContext;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import rx.schedulers.Schedulers;

import static com.hotels.styx.api.HttpResponse.response;
import static com.hotels.styx.api.extension.Origin.newOriginBuilder;
import static com.hotels.styx.api.HttpResponseStatus.BAD_GATEWAY;
import static com.hotels.styx.api.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static com.hotels.styx.api.HttpResponseStatus.NOT_FOUND;
import static com.hotels.styx.api.HttpResponseStatus.OK;
import static com.hotels.styx.api.extension.service.BackendService.newBackendServiceBuilder;
import static com.hotels.styx.client.StyxHeaderConfig.ORIGIN_ID_DEFAULT;
import static java.lang.String.format;
import static java.util.Optional.empty;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static rx.Observable.just;

public class BackendServiceLauncherTest {
    private Environment environment;
    private ConfigStore configStore;

    private final BackendServiceClientFactory serviceClientFactory =
            (backendService, originsInventory, originStatsFactory) -> request -> just(response(OK)
                    .header(ORIGIN_ID_DEFAULT, backendService.id())
                    .build());
    private FullHttpRequest request = FullHttpRequest.get("/").build();

    private FullHttpResponse internalServerError = FullHttpResponse.response(INTERNAL_SERVER_ERROR).build();
    private HttpHandler internalServerErrorHandler = (request, context) -> StyxObservable.of(internalServerError.toStreamingResponse());

    @BeforeMethod
    public void before() {
        configStore = new ConfigStore(Schedulers.immediate());
        environment = new Environment.Builder()
                .configStore(configStore)
                .build();
    }

    @Test
    public void createsApplications() {
        BackendServiceLauncher launcher = new BackendServiceLauncher(serviceClientFactory, environment);

        configStore.addNewApplication("a", app("a", "/"));

        assertThat(configStore.routingObject().get("a").orElse(null), instanceOf(HttpHandler.class));

        configStore.addNewApplication("b", app("b", "/b"));

        assertThat(configStore.routingObject().get("a")
                        .map(handler -> ping(handler, request))
                        .orElse(NOT_FOUND),
                is(OK));

        assertThat(configStore.routingObject().get("b")
                        .map(handler -> ping(handler, request))
                        .orElse(NOT_FOUND),
                is(OK));
    }

    @Test
    public void removesApplications() {
        BackendServiceLauncher launcher = new BackendServiceLauncher(serviceClientFactory, environment);

        configStore.addNewApplication("a", app("a", "/"));

        HttpHandler handler = configStore.routingObject().get("a")
                .orElse(internalServerErrorHandler);

        configStore.removeApplication("a");

        assertThat(configStore.routingObject().get("a"), is(empty()));
        assertThat(ping(handler, request), is(BAD_GATEWAY));
    }

    private static BackendService app(String id, String path) {
        return newBackendServiceBuilder()
                .id(id)
                .path(path)
                .origins(newOriginBuilder("localhost", 9090).applicationId(id).id(format("%s-01", id)).build())
                .build();
    }

    private static HttpResponseStatus ping(HttpHandler handler, FullHttpRequest request) {
        return StyxFutures.await(
                handler.handle(request.toStreamingRequest(), HttpInterceptorContext.create())
                        .flatMap(response -> response.toFullResponse(100000))
                        .map(FullHttpResponse::status)
                        .onError(cause -> StyxObservable.of(BAD_GATEWAY))
                        .asCompletableFuture()
        );
    }

}
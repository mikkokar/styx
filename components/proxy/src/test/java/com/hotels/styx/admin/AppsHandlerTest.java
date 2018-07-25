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
package com.hotels.styx.admin;

import com.google.common.collect.ImmutableList;
import com.hotels.styx.api.FullHttpResponse;
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.proxy.BackendRegistryShim;
import com.hotels.styx.proxy.ConfigStore;
import com.hotels.styx.server.HttpInterceptorContext;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static com.hotels.styx.api.FullHttpRequest.delete;
import static com.hotels.styx.api.FullHttpRequest.get;
import static com.hotels.styx.api.FullHttpRequest.post;
import static com.hotels.styx.api.FullHttpRequest.put;
import static com.hotels.styx.api.HttpResponseStatus.CONFLICT;
import static com.hotels.styx.api.HttpResponseStatus.CREATED;
import static com.hotels.styx.api.HttpResponseStatus.NOT_FOUND;
import static com.hotels.styx.api.HttpResponseStatus.OK;
import static com.hotels.styx.api.Id.id;
import static com.hotels.styx.api.extension.service.BackendService.newBackendServiceBuilder;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.not;

public class AppsHandlerTest {
    private ConfigStore configStore;
    private BackendRegistryShim shim;
    private AppsHandler appsHandler;

    private String fullAppX = "{" +
            "    \"id\": \"x\"," +
            "    \"path\": \"/x/\"," +
            "    \"origins\": [" +
            "        {" +
            "            \"id\": \"x-02\"," +
            "            \"host\": \"localhost:40002\"" +
            "        }," +
            "        {" +
            "            \"id\": \"x-01\"," +
            "            \"host\": \"localhost:40001\"" +
            "        }" +
            "    ]," +
            "    \"healthCheck\": {" +
            "        \"uri\": \"/admin/healthcheck\"," +
            "        \"intervalMillis\": 5000," +
            "        \"timeoutMillis\": 2000," +
            "        \"healthyThreshold\": 2," +
            "        \"unhealthyThreshold\": 2" +
            "    }," +
            "    \"stickySession\": {" +
            "        \"enabled\": false," +
            "        \"timeoutSeconds\": 43200" +
            "    }," +
            "    \"rewrites\": [" +
            "    {" +
            "        \"urlPattern\": \"/x/(.*)\"," +
            "        \"replacement\": \"/$1\"" +
            "    }" +
            "    ]," +
            "    \"responseTimeoutMillis\": 30000," +
            "    \"connectionPool\": {" +
            "        \"maxConnectionsPerHost\": 15," +
            "        \"maxPendingConnectionsPerHost\": 20," +
            "        \"connectTimeoutMillis\": 5000," +
            "        \"socketTimeoutMillis\": 30000," +
            "        \"pendingConnectionTimeoutMillis\": 30000," +
            "        \"connectionExpirationSeconds\": -1" +
            "    }" +
            "},";

    private String minimalAppX = "{" +
            "    \"id\": \"x\"," +
            "    \"path\": \"/x/\"" +
            "},";

    @BeforeMethod
    public void setup() {
        configStore = new ConfigStore();
        shim = new BackendRegistryShim(configStore);
        appsHandler = new AppsHandler(shim, configStore);
    }

    @Test
    public void post_newApplicationIdWithDefaults() throws Exception {
        FullHttpResponse response = appsHandler.handle(
                post("/admin/apps/myapp").build().toStreamingRequest(),
                HttpInterceptorContext.create())
                .flatMap(r -> r.toFullResponse(10000))
                .asCompletableFuture().get();

        assertThat(response.status(), is(CREATED));

        assertThat(configStore.applications().get(), is(ImmutableList.of("myapp")));
        assertThat(configStore.application().get("myapp").get(), isA(BackendService.class));
    }

    @Test
    public void post_newApplicationFromJsonObjectWithAppId() throws Exception {
        FullHttpResponse response = appsHandler.handle(
                post("/admin/apps/x")
                        .body(fullAppX, UTF_8)
                        .build()
                        .toStreamingRequest(),
                HttpInterceptorContext.create())
                .flatMap(r -> r.toFullResponse(10000))
                .asCompletableFuture().get();

        assertThat(response.status(), is(CREATED));

        assertThat(configStore.applications().get(), is(ImmutableList.of("x")));

        BackendService myapp = configStore.application().get("x").get();
        assertThat(myapp.id(), is(id("x")));
        assertThat(myapp.path(), is("/x/"));
    }

    @Test
    public void post_newApplicationFromJsonObject() throws Exception {
        FullHttpResponse response = appsHandler.handle(
                post("/admin/apps")
                        .body(fullAppX, UTF_8)
                        .build()
                        .toStreamingRequest(),
                HttpInterceptorContext.create())
                .flatMap(r -> r.toFullResponse(10000))
                .asCompletableFuture().get();

        assertThat(response.status(), is(CREATED));

        assertThat(configStore.applications().get(), is(ImmutableList.of("x")));

        BackendService myapp = configStore.application().get("x").get();
        assertThat(myapp.id(), is(id("x")));
        assertThat(myapp.path(), is("/x/"));
    }

    @Test
    public void post_newApplicationFromMinimalJsonObject() throws Exception {
        FullHttpResponse response = appsHandler.handle(
                post("/admin/apps/x")
                        .body(minimalAppX, UTF_8)
                        .build()
                        .toStreamingRequest(),
                HttpInterceptorContext.create())
                .flatMap(r -> r.toFullResponse(10000))
                .asCompletableFuture().get();

        assertThat(response.status(), is(CREATED));

        assertThat(configStore.applications().get(), is(ImmutableList.of("x")));

        BackendService myapp = configStore.application().get("x").get();
        assertThat(myapp.id(), is(id("x")));
        assertThat(myapp.path(), is("/x/"));
    }

    @Test
    public void post_createApplictionWithConflictingId() throws Exception {
        BackendService existing = newBackendServiceBuilder().id("existing").build();
        configStore.addNewApplication("x", existing);

        FullHttpResponse response = appsHandler.handle(
                post("/admin/apps")
                        .body(fullAppX, UTF_8)
                        .build()
                        .toStreamingRequest(),
                HttpInterceptorContext.create())
                .flatMap(r -> r.toFullResponse(10000))
                .asCompletableFuture().get();

        assertThat(response.status(), is(CONFLICT));
        assertThat(configStore.applications().get(), is(ImmutableList.of("x")));
    }

    @Test
    public void post_createApplicationWithConflictingId2() throws Exception {
        BackendService existing = newBackendServiceBuilder().id("existing").build();
        configStore.addNewApplication("existing", existing);

        FullHttpResponse response = appsHandler.handle(
                post("/admin/apps/existing").build().toStreamingRequest(),
                HttpInterceptorContext.create())
                .flatMap(r -> r.toFullResponse(10000))
                .asCompletableFuture().get();

        assertThat(response.status(), is(CONFLICT));

        assertThat(configStore.applications().get(), is(ImmutableList.of("existing")));
        assertThat(configStore.application().get("existing").get(), isA(BackendService.class));
    }

    @Test
    public void get_allApps() throws ExecutionException, InterruptedException {
        configStore.addNewApplication("app-x", newBackendServiceBuilder().id("app-x").build());
        configStore.addNewApplication("app-y", newBackendServiceBuilder().id("app-y").build());

        FullHttpResponse response = appsHandler.handle(
                get("/admin/apps").build().toStreamingRequest(),
                HttpInterceptorContext.create())
                .flatMap(r -> r.toFullResponse(10000))
                .asCompletableFuture().get();

        assertThat(response.status(), is(OK));

        System.out.println("response body: " + response.bodyAs(UTF_8));
        assertThat(response.bodyAs(UTF_8), containsString("app-x"));
        assertThat(response.bodyAs(UTF_8), containsString("app-y"));
    }

    @Test
    public void get_specificApp() throws ExecutionException, InterruptedException {
        configStore.addNewApplication("app-x", newBackendServiceBuilder().id("app-x").build());
        configStore.addNewApplication("app-y", newBackendServiceBuilder().id("app-y").build());

        FullHttpResponse response = appsHandler.handle(
                get("/admin/apps/app-y").build().toStreamingRequest(),
                HttpInterceptorContext.create())
                .flatMap(r -> r.toFullResponse(10000))
                .asCompletableFuture().get();

        assertThat(response.status(), is(OK));

        System.out.println("response body: " + response.bodyAs(UTF_8));
        assertThat(response.bodyAs(UTF_8), containsString("app-y"));
        assertThat(response.bodyAs(UTF_8), not(containsString("app-x")));
    }

    @Test
    public void get_specificApp_notFound() throws ExecutionException, InterruptedException {
        configStore.addNewApplication("app-x", newBackendServiceBuilder().id("app-x").build());
        configStore.addNewApplication("app-y", newBackendServiceBuilder().id("app-y").build());

        FullHttpResponse response = appsHandler.handle(
                get("/admin/apps/app-z").build().toStreamingRequest(),
                HttpInterceptorContext.create())
                .flatMap(r -> r.toFullResponse(10000))
                .asCompletableFuture().get();

        assertThat(response.status(), is(NOT_FOUND));
    }

    // NOTE: The PUT updates *all* attributes. Not just those that are present in the update:
    @Test
    public void put_updatesApp() throws ExecutionException, InterruptedException {
        configStore.addNewApplication("app-x", newBackendServiceBuilder().id("app-x").build());
        configStore.addNewApplication("app-y", newBackendServiceBuilder().id("app-y").build());

        FullHttpResponse response = appsHandler.handle(
                put("/admin/apps/app-x")
                        .body("{" +
                                "    \"id\": \"app-x\"," +
                                "    \"path\": \"/updatedPath/\"" +
                                "},", UTF_8)
                        .build().toStreamingRequest(),
                HttpInterceptorContext.create())
                .flatMap(r -> r.toFullResponse(10000))
                .asCompletableFuture().get();

        assertThat(response.status(), is(OK));
        assertThat(configStore.application().get("app-x").get().path(), is("/updatedPath/"));
    }

    @Test
    public void put_updatesToUnknownApp() throws ExecutionException, InterruptedException {
        configStore.addNewApplication("app-x", newBackendServiceBuilder().id("app-x").build());
        configStore.addNewApplication("app-y", newBackendServiceBuilder().id("app-y").build());

        FullHttpResponse response = appsHandler.handle(
                put("/admin/apps/app-z")
                        .body("{" +
                                "    \"id\": \"x\"," +
                                "    \"path\": \"/updatedPath/\"" +
                                "},", UTF_8)
                        .build().toStreamingRequest(),
                HttpInterceptorContext.create())
                .flatMap(r -> r.toFullResponse(10000))
                .asCompletableFuture().get();

        assertThat(response.status(), is(NOT_FOUND));
    }

    @Test
    public void delete_removeApp() throws ExecutionException, InterruptedException {
        configStore.addNewApplication("app-x", newBackendServiceBuilder().id("app-x").build());
        configStore.addNewApplication("app-y", newBackendServiceBuilder().id("app-y").build());

        FullHttpResponse response = appsHandler.handle(
                delete("/admin/apps/app-x")
                        .build().toStreamingRequest(),
                HttpInterceptorContext.create())
                .flatMap(r -> r.toFullResponse(10000))
                .asCompletableFuture().get();

        assertThat(response.status(), is(OK));
        assertThat(configStore.applications().get(), is(ImmutableList.of("app-y")));
        assertThat(configStore.application().get("app-x"), is(Optional.empty()));
        assertThat(configStore.application().get("app-y").get(), instanceOf(BackendService.class));
    }

    @Test
    public void delete_attemptToRemoveUnknownApp() throws ExecutionException, InterruptedException {
        configStore.addNewApplication("app-x", newBackendServiceBuilder().id("app-x").build());
        configStore.addNewApplication("app-y", newBackendServiceBuilder().id("app-y").build());

        FullHttpResponse response = appsHandler.handle(
                delete("/admin/apps/app-z")
                        .build().toStreamingRequest(),
                HttpInterceptorContext.create())
                .flatMap(r -> r.toFullResponse(10000))
                .asCompletableFuture().get();

        assertThat(response.status(), is(NOT_FOUND));
    }

}
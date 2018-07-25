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
package com.hotels.styx.admin.handlers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotels.styx.api.HttpResponse;
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.proxy.ConfigStore;
import com.hotels.styx.server.HttpInterceptorContext;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static com.google.common.net.MediaType.JSON_UTF_8;
import static com.hotels.styx.api.HttpResponseStatus.OK;
import static com.hotels.styx.api.LiveHttpRequest.get;
import static com.hotels.styx.api.extension.service.HealthCheckConfig.newHealthCheckConfigBuilder;
import static com.hotels.styx.infrastructure.configuration.json.ObjectMappers.addStyxMixins;
import static com.hotels.styx.support.api.BlockingObservables.waitForResponse;
import static com.hotels.styx.support.matchers.IsOptional.isValue;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static rx.schedulers.Schedulers.immediate;

public class OriginsHandlerTest {
    private static final ObjectMapper MAPPER = addStyxMixins(new ObjectMapper().disable(FAIL_ON_UNKNOWN_PROPERTIES));

    @Test
    public void respondsToRequestWithJsonResponse() throws IOException {
        BackendService app1 = BackendService.newBackendServiceBuilder()
                .id("webapp")
                .build();

        BackendService app2 = BackendService.newBackendServiceBuilder()
                .id("shopping")
                .build();

        OriginsHandler handler = new OriginsHandler(populatedConfigStore(app1, app2));

        HttpResponse response = waitForResponse(handler.handle(get("/admin/configuration/origins?pretty").build(), HttpInterceptorContext.create()));

        assertThat(response.status(), is(OK));
        assertThat(response.contentType(), isValue(JSON_UTF_8.toString()));
        assertThat(unmarshalApplications(response.bodyAs(UTF_8)), containsInAnyOrder(app2, app1));
    }

    @Test
    public void respondsWithEmptyArrayWhenNoOrigins() {
        ConfigStore configStore = new ConfigStore();
        OriginsHandler handler = new OriginsHandler(configStore);

        HttpResponse response = waitForResponse(handler.handle(get("/admin/configuration/origins").build(), HttpInterceptorContext.create()));

        assertThat(response.status(), is(OK));
        assertThat(response.contentType(), isValue(JSON_UTF_8.toString()));

        assertThat(response.bodyAs(UTF_8), is("[]"));
    }

    @Test
    public void healthCheckIsAbsentWhenNotConfigured() throws IOException {
        BackendService app1 = BackendService.newBackendServiceBuilder()
                .id("webapp")
                .healthCheckConfig(newHealthCheckConfigBuilder().uri(Optional.empty()).build())
                .build();

        BackendService app2 = BackendService.newBackendServiceBuilder()
                .id("shopping")
                .healthCheckConfig(newHealthCheckConfigBuilder().uri(Optional.empty()).build())
                .build();

        OriginsHandler handler = new OriginsHandler(populatedConfigStore(app1, app2));

        HttpResponse response = waitForResponse(handler.handle(get("/admin/configuration/origins").build(), HttpInterceptorContext.create()));
        String body = response.bodyAs(UTF_8);

        assertThat(response.status(), is(OK));
        assertThat(response.contentType(), isValue(JSON_UTF_8.toString()));
        assertThat(body, not(containsString("healthCheck")));
        assertThat(unmarshalApplications(body), containsInAnyOrder(app1, app2));
    }

    private static ConfigStore populatedConfigStore(BackendService... apps) {
        ConfigStore configStore = new ConfigStore(immediate());
        List<String> appNames = new ArrayList<>();

        for (BackendService app : apps) {
            appNames.add(app.id().toString());
            configStore.addNewApplication(app.id().toString(), app);
        }
        return configStore;
    }

    private static List<BackendService> unmarshalApplications(String content) throws IOException {
        return MAPPER.readValue(content, new TypeReference<List<BackendService>>() {
        });
    }
}
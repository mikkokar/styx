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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotels.styx.api.FullHttpRequest;
import com.hotels.styx.api.FullHttpResponse;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.HttpRequest;
import com.hotels.styx.api.HttpResponse;
import com.hotels.styx.api.Id;
import com.hotels.styx.api.StyxObservable;
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.api.extension.service.spi.Registry;
import com.hotels.styx.proxy.BackendRegistryShim;
import com.hotels.styx.proxy.ConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import static com.hotels.styx.api.FullHttpResponse.response;
import static com.hotels.styx.api.HttpResponseStatus.CONFLICT;
import static com.hotels.styx.api.HttpResponseStatus.CREATED;
import static com.hotels.styx.api.HttpResponseStatus.NOT_FOUND;
import static com.hotels.styx.api.HttpResponseStatus.OK;
import static com.hotels.styx.api.extension.service.BackendService.newBackendServiceBuilder;
import static com.hotels.styx.infrastructure.configuration.json.ObjectMappers.addStyxMixins;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * An admin interface for backend service application management.
 */
public class AppsHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AppsHandler.class);
    private BackendRegistryShim shim;
    private ConfigStore configStore;

    private static final ObjectMapper MAPPER = addStyxMixins(new ObjectMapper().setSerializationInclusion(NON_NULL));


    public AppsHandler(BackendRegistryShim shim, ConfigStore configStore) {
        this.shim = shim;
        this.configStore = configStore;
    }

    UrlPatternRouter urlRouter = new UrlPatternRouter.Builder()
            .get("/admin/apps", httpHandler((request, context) -> {
                List<String> appsList = configStore.applications().get();
                List<BackendService> apps = appsList.stream()
                        .map(name -> configStore.application().get(name).orElse(null))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                String serialised = serialise(apps);
                return StyxObservable.of(response(OK)
                        .body(serialised, UTF_8)
                        .build());
            }))
            .get("/admin/apps/:appId", httpHandler((request, context) -> {
                Map<String, String> placeholders = UrlPatternRouter.placeholders(context);
                String appId = placeholders.get("appId");
                Optional<BackendService> app = configStore.application().get(appId);
                if (app.isPresent()) {
                    String serialised = serialise(app.get());
                    return StyxObservable.of(response(OK)
                            .body(serialised, UTF_8)
                            .build());
                } else {
                    return StyxObservable.of(response(NOT_FOUND).build());
                }
            }))
            .post("/admin/apps",
                    httpHandler((request, context) -> {
                        String body = request.bodyAs(UTF_8);
                        BackendService app = deserialise(body);
                        Id appId = app.id();

                        return addApp(appId, app);
                    }))
            .post("/admin/apps/:appId",
                    httpHandler((request, context) -> {
                        String body = request.bodyAs(UTF_8);
                        Map<String, String> placeholders = UrlPatternRouter.placeholders(context);
                        Id appId = Id.id(placeholders.get("appId"));

                        BackendService app;
                        if (body.isEmpty()) {
                            app = newBackendServiceBuilder().id(appId).build();
                        } else {
                            app = deserialise(body);
                            app = newBackendServiceBuilder(app).id(appId).build();
                        }

                        return addApp(appId, app);
                    }))
            .put("/admin/apps/:appId", httpHandler((request, context) -> {
                Map<String, String> placeholders = UrlPatternRouter.placeholders(context);
                String appId = placeholders.get("appId");

                Optional<BackendService> existingApp = configStore.application().get(appId);
                BackendService newApp = deserialise(request.bodyAs(UTF_8));

                if (existingApp.isPresent()) {
                    Registry.Changes<BackendService> build = new Registry.Changes.Builder<BackendService>().updated(newApp).build();
                    shim.onChange(build);
                    return StyxObservable.of(response(OK).build());
                } else {
                    return StyxObservable.of(response(NOT_FOUND).build());
                }
            }))
            .delete("/admin/apps/:appId", httpHandler((request, context) -> {
                Map<String, String> placeholders = UrlPatternRouter.placeholders(context);
                String appId = placeholders.get("appId");

                Optional<BackendService> existingApp = configStore.application().get(appId);

                if (existingApp.isPresent()) {
                    Registry.Changes<BackendService> build = new Registry.Changes.Builder<BackendService>().removed(existingApp.get()).build();
                    shim.onChange(build);
                    return StyxObservable.of(response(OK).build());
                } else {
                    return StyxObservable.of(response(NOT_FOUND).build());
                }
            }))

            .build();

    private StyxObservable<FullHttpResponse> addApp(Id appId, BackendService app) {
        boolean existing = configStore.application().get(appId.toString()).isPresent();

        if (existing) {
            return StyxObservable.of(response(CONFLICT).build());
        } else {
            Registry.Changes<BackendService> build = new Registry.Changes.Builder<BackendService>().added(app).build();
            shim.onChange(build);
            return StyxObservable.of(response(CREATED).build());
        }
    }

    private static BackendService deserialise(String body) {
        try {
            return MAPPER.readValue(body, BackendService.class);
        } catch (IOException cause) {
            throw new RuntimeException(cause);
        }
    }

    private static String serialise(Object app) {
        try {
            return MAPPER.writeValueAsString(app);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    interface FullHttpHandler {
        StyxObservable<FullHttpResponse> handle(FullHttpRequest request, HttpInterceptor.Context context);
    }

    private HttpHandler httpHandler(FullHttpHandler delegate) {
        return (request, context) -> request.toFullRequest(1000000)
                .flatMap(fullRequest -> delegate.handle(fullRequest, context))
                .map(FullHttpResponse::toStreamingResponse);
    }

    @Override
    public StyxObservable<HttpResponse> handle(HttpRequest request, HttpInterceptor.Context context) {
        return urlRouter.handle(request, context);
    }
}

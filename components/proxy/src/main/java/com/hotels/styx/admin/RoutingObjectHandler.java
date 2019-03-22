/*
  Copyright (C) 2013-2019 Expedia Inc.

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
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.hotels.styx.admin.handlers.UrlPatternRouter;
import com.hotels.styx.api.Eventual;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.HttpRequest;
import com.hotels.styx.api.HttpResponse;
import com.hotels.styx.api.LiveHttpRequest;
import com.hotels.styx.api.LiveHttpResponse;
import com.hotels.styx.api.configuration.RouteDatabase;
import com.hotels.styx.routing.config.RoutingObjectDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import static com.fasterxml.jackson.core.JsonParser.Feature.AUTO_CLOSE_SOURCE;
import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static com.hotels.styx.api.HttpResponse.response;
import static com.hotels.styx.api.HttpResponseStatus.BAD_REQUEST;
import static com.hotels.styx.api.HttpResponseStatus.NOT_FOUND;
import static com.hotels.styx.api.HttpResponseStatus.OK;
import static com.hotels.styx.infrastructure.configuration.json.ObjectMappers.addStyxMixins;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * An admin interface for backend service application management.
 */
public class RoutingObjectHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(RoutingObjectHandler.class);

    private static final ObjectMapper MAPPER = addStyxMixins(new ObjectMapper().setSerializationInclusion(NON_NULL));
    private final UrlPatternRouter urlRouter;

    private final ObjectMapper YAML_MAPPER = addStyxMixins(new ObjectMapper(new YAMLFactory()))
            .configure(FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(AUTO_CLOSE_SOURCE, true);


    public RoutingObjectHandler(RouteDatabase routeDb) {
        urlRouter = new UrlPatternRouter.Builder()
                .get("/admin/routing/objects", httpHandler((request, context) -> {
                    String output = routeDb.lookupAll()
                            .stream()
                            .map(Object::toString)
                            .collect(Collectors.joining(", \n"));

                    return Eventual.of(response(OK)
                            .body(output, UTF_8)
                            .build());
                }))
                .post("/admin/routing/objects", httpHandler((request, context) -> {
                    String body = request.bodyAs(UTF_8);
                    try {
                        YAML_MAPPER.readValue(body, RoutingObjectDefinition.class);
                        routeDb.insert(body);
                        return Eventual.of(response(OK).build());
                    } catch (IOException | RuntimeException cause) {
                        return Eventual.of(response(BAD_REQUEST).body(cause.toString(), UTF_8).build());
                    }
                }))
                .delete("/admin/routing/objects/:objectId", httpHandler((request, context) -> {
                    Map<String, String> placeholders = UrlPatternRouter.placeholders(context);
                    String appId = placeholders.get("objectId");


                    if (routeDb.handler(appId).isPresent()) {
                        routeDb.delete(appId);
                        return Eventual.of(response(OK).build());
                    } else {
                        return Eventual.of(response(NOT_FOUND).build());
                    }
                }))
                .build();
    }

    private static RoutingObjectDefinition deserialise(String body) {
        try {
            return MAPPER.readValue(body, RoutingObjectDefinition.class);
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
        Eventual<HttpResponse> handle(HttpRequest request, HttpInterceptor.Context context);
    }

    private HttpHandler httpHandler(FullHttpHandler delegate) {
        return (request, context) -> request.aggregate(1000000)
                .flatMap(fullRequest -> delegate.handle(fullRequest, context))
                .map(HttpResponse::stream);
    }

    @Override
    public Eventual<LiveHttpResponse> handle(LiveHttpRequest request, HttpInterceptor.Context context) {
        return urlRouter.handle(request, context);
    }
}

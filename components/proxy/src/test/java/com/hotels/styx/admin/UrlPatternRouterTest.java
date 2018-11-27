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

import ch.qos.logback.classic.Level;
import com.hotels.styx.api.Eventual;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.HttpResponse;
import com.hotels.styx.server.HttpInterceptorContext;
import com.hotels.styx.support.matchers.LoggingTestSupport;
import org.hamcrest.Matchers;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static com.hotels.styx.api.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static com.hotels.styx.api.HttpResponseStatus.OK;
import static com.hotels.styx.api.LiveHttpRequest.post;
import static com.hotels.styx.api.LiveHttpResponse.response;
import static com.hotels.styx.support.matchers.LoggingEventMatcher.loggingEvent;
import static org.hamcrest.MatcherAssert.assertThat;

public class UrlPatternRouterTest {

    private LoggingTestSupport LOGGER;

    @BeforeMethod
    public void setUp() {
        LOGGER = new LoggingTestSupport(UrlPatternRouter.class);
    }

    @Test
    public void exposesPlaceholdersInContext() {
        AtomicReference<HttpInterceptor.Context> contextCapture = new AtomicReference<>();

        UrlPatternRouter router = new UrlPatternRouter.Builder()
                .post("/admin/apps/:appId/:originId", (request, context) -> {
                    contextCapture.set(context);
                    return Eventual.of(response(OK).build());
                })
                .build();

        HttpResponse response = Mono.from(router.handle(post("/admin/apps/appx/appx-01").build(), HttpInterceptorContext.create())
                .flatMap(x -> x.aggregate(10000))).block();

        assertThat(response.status(), Matchers.is(OK));

        Map<String, String> placeholders = UrlPatternRouter.placeholders(contextCapture.get());
        assertThat(placeholders.get("appId"), Matchers.is("appx"));
        assertThat(placeholders.get("originId"), Matchers.is("appx-01"));
    }

    @Test
    public void catchesAndLogsHandlerExceptions() throws ExecutionException, InterruptedException {
        AtomicReference<HttpInterceptor.Context> contextCapture = new AtomicReference<>();

        UrlPatternRouter router = new UrlPatternRouter.Builder()
                .post("/admin/apps/:appId/:originId", (request, context) -> {
                    throw new RuntimeException("Something went wrong");
                })
                .build();

        HttpResponse response = Mono.from(router.handle(post("/admin/apps/appx/appx-01").build(), HttpInterceptorContext.create())
                .flatMap(x -> x.aggregate(10000)))
                .block();

        assertThat(response.status(), Matchers.is(INTERNAL_SERVER_ERROR));
        assertThat(LOGGER.lastMessage(), Matchers.is(
                loggingEvent(Level.ERROR,
                        "ERROR: POST /admin/apps/appx/appx-01",
                        RuntimeException.class,
                        "Something went wrong")));
    }
}
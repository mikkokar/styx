package com.hotels.styx.admin.handlers;

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

import static com.hotels.styx.api.LiveHttpResponse.response;
import static com.hotels.styx.api.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static com.hotels.styx.api.HttpResponseStatus.OK;
import static com.hotels.styx.api.LiveHttpRequest.post;
import static com.hotels.styx.support.matchers.LoggingEventMatcher.loggingEvent;
import static org.hamcrest.MatcherAssert.assertThat;

public class UrlPatternRouterTest {

    private LoggingTestSupport LOGGER;

    @BeforeMethod
    public void setUp() {
        LOGGER = new LoggingTestSupport(UrlPatternRouter.class);
    }

    @Test
    public void exposesPlaceholdersInContext() throws ExecutionException, InterruptedException {
        AtomicReference<HttpInterceptor.Context> contextCapture = new AtomicReference<>();

        UrlPatternRouter router = new UrlPatternRouter.Builder()
                .post("/admin/apps/:appId/:originId", (request, context) -> {
                    contextCapture.set(context);
                    return Eventual.of(response(OK).build());
                })
                .build();

        HttpResponse response = Mono.from(
                router.handle(post("/admin/apps/appx/appx-01").build(), HttpInterceptorContext.create())
                        .flatMap(x -> x.aggregate(10000)))
                .block();

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

        HttpResponse response = Mono.from(
                router.handle(post("/admin/apps/appx/appx-01").build(), HttpInterceptorContext.create())
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
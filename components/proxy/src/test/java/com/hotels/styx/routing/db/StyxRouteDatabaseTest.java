package com.hotels.styx.routing.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableSet;
import com.hotels.styx.Environment;
import com.hotels.styx.api.Eventual;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.LiveHttpResponse;
import com.hotels.styx.api.extension.service.spi.StyxService;
import com.hotels.styx.infrastructure.configuration.yaml.YamlConfig;
import com.hotels.styx.proxy.plugin.NamedPlugin;
import com.hotels.styx.routing.config.RoutingObjectDefinition;
import org.hamcrest.Matchers;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StyxRouteDatabaseTest {
    private final Map<String, StyxService> services = Collections.emptyMap();
    private final List<NamedPlugin> plugins = Collections.emptyList();
    private final Environment env = new Environment.Builder().build();

    private final JsonNode jsonNode = new YamlConfig("config: bar").get("config", JsonNode.class).get();
    private StyxRouteDatabase db;
    private final HttpHandler handler1 = (request, context) -> Eventual.of(LiveHttpResponse.response().build());
    private final HttpHandler handler2 = (request, context) -> Eventual.of(LiveHttpResponse.response().build());
    private final HttpHandler handler3 = (request, context) -> Eventual.of(LiveHttpResponse.response().build());

    @BeforeMethod
    public void setUp() {
        ObjectLoader objectLoader = mock(ObjectLoader.class);
        when(objectLoader.load(any(RouteDatabase.class), any(String.class), any(RoutingObjectDefinition.class)))
                .thenReturn(handler1)
                .thenReturn(handler2)
                .thenReturn(handler3);

        db = new StyxRouteDatabase(objectLoader);
    }

    @Test
    public void queryTags() {
        db.insert("app-a", new RoutingObjectDefinition("appA", "bar", jsonNode), "app=landing");
        assertThat(db.handlers("app=landing"), Matchers.is(ImmutableSet.of(handler1)));

        db.insert("app-b", new RoutingObjectDefinition("appB", "bar", jsonNode), "app=landing");
        assertThat(db.handlers("app=landing"), Matchers.is(ImmutableSet.of(handler1, handler2)));

        db.insert("app-c", new RoutingObjectDefinition("appC", "bar", jsonNode), "app=shopping");
        assertThat(db.handlers("app=landing"), Matchers.is(ImmutableSet.of(handler1, handler2)));
    }

    @Test
    public void modifyTags() {
        db.insert("app-a", new RoutingObjectDefinition("appA", "bar", jsonNode), "app=landing");
        assertThat(db.handlers("app=landing"), Matchers.is(ImmutableSet.of(handler1)));

        db.insert("app-b", new RoutingObjectDefinition("appB", "bar", jsonNode), "app=landing");
        assertThat(db.handlers("app=landing"), Matchers.is(ImmutableSet.of(handler1, handler2)));

        db.insert("app-c", new RoutingObjectDefinition("appC", "bar", jsonNode), "app=shopping");
        assertThat(db.handlers("app=landing"), Matchers.is(ImmutableSet.of(handler1, handler2)));

        db.replaceTag("app-c", "app=shopping", "app=landing");
        assertThat(db.handlers("app=landing"), Matchers.is(ImmutableSet.of(handler1, handler2, handler3)));
    }

    @Test
    public void removesObjects() {
        db.insert("app-a", new RoutingObjectDefinition("appA", "bar", jsonNode), "app=landing");
        assertThat(db.handlers("app=landing"), Matchers.is(ImmutableSet.of(handler1)));

        db.remove("app-a");
        assertThat(db.handlers("app=landing"), Matchers.is(ImmutableSet.of()));

        db.remove("app-a");
        assertThat(db.handlers("app=landing"), Matchers.is(ImmutableSet.of()));
    }

    @Test
    public void listensForEvents() {
        AtomicBoolean notified = new AtomicBoolean();
        RouteDatabase.Listener listener = db -> notified.set(true);

        db.addListener(listener);

        db.insert("app-a", new RoutingObjectDefinition("appA", "bar", jsonNode), "app=landing");
        assertThat(notified.get(), Matchers.is(true));

        notified.set(false);

        db.remove("app-a");
        assertThat(notified.get(), Matchers.is(true));

        notified.set(false);

        db.removeListener(listener);
        db.insert("app-a", new RoutingObjectDefinition("appA", "bar", jsonNode), "app=landing");
        db.remove("app-a");

        assertThat(notified.get(), Matchers.is(false));
    }
}
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
package com.hotels.styx.routing.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableSet;
import com.hotels.styx.api.Eventual;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.LiveHttpResponse;
import com.hotels.styx.infrastructure.configuration.yaml.YamlConfig;
import com.hotels.styx.routing.config.RoutingObjectConfig;
import com.hotels.styx.routing.config.RoutingObjectDefinition;
import com.hotels.styx.routing.config.RoutingObjectFactory;
import org.hamcrest.Matchers;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StyxRouteDatabaseTest {
    private final JsonNode jsonNode = new YamlConfig("config: bar").get("config", JsonNode.class).get();
    private StyxRouteDatabase db;
    private final HttpHandler handler1 = (request, context) -> Eventual.of(LiveHttpResponse.response().build());
    private final HttpHandler handler2 = (request, context) -> Eventual.of(LiveHttpResponse.response().build());
    private final HttpHandler handler3 = (request, context) -> Eventual.of(LiveHttpResponse.response().build());
    private RoutingObjectFactory routingObjectFactory = mock(RoutingObjectFactory.class);

    @BeforeMethod
    public void setUp() {
        when(routingObjectFactory.build(any(List.class), any(RouteDatabase.class), any(RoutingObjectConfig.class)))
                .thenReturn(handler1)
                .thenReturn(handler2)
                .thenReturn(handler3);

        db = new StyxRouteDatabase(routingObjectFactory);
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
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
package com.hotels.styx.configstore;

import com.google.common.collect.ImmutableList;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class MultiValueConfigTopicTest {
    private MultiValueConfigTopic<String> configStore;

    @BeforeMethod
    public void setUp() {
        configStore = new MultiValueConfigTopic<>(Schedulers.immediate());
    }

    @Test
    public void getsWhatWasSet() {
        configStore.set("app-1", "a");
        configStore.set("app-2", "b");
        assertThat(configStore.get("app-1"), is(Optional.of("a")));
        assertThat(configStore.get("app-2"), is(Optional.of("b")));
    }

    @Test
    public void emitsCurrentStateOnSubscribe() {
        configStore.set("foo", "bar");

        AtomicReference<Object> state = new AtomicReference<>();
        CountDownLatch waitingForEvent = new CountDownLatch(1);

        Flux.from(configStore.watch("foo"))
                .subscribe(value -> {
                    state.set(value);
                    waitingForEvent.countDown();
                });

        assertThat(state.get(), is("bar"));
    }

    @Test
    public void watchEmitsOnlyLastValueStored() {
        ArrayList<String> list = new ArrayList<>();

        configStore.set("apps", "sh");
        configStore.set("apps", "ph");

        Flux.from(configStore.<List<String>>watch("apps")).subscribe(list::add);

        assertThat(list.get(0), is("ph"));
        assertThat(list.size(), is(1));
    }

    @Test
    public void clearsAttributeSubscriber() {
        configStore.set("apps.la", "x");
        StepVerifier.create(configStore.<String>watch("apps.la"))
                .expectNext("x")
                .then(() -> configStore.unset("apps.la"))
                .verifyComplete();
    }

    @Test
    public void updatesPreviousValue() {
        MultiValueConfigTopic<List<String>> originNames = new MultiValueConfigTopic<>(Schedulers.immediate());

        originNames.set("apps.x.origins", ImmutableList.of());

        originNames.update("apps.x.origins",
                (maybeValue) -> maybeValue.map(actual ->
                        ImmutableList.<String>builder()
                                .addAll(actual)
                                .add("x-01")
                                .build())
                        .orElse(ImmutableList.of()));

        assertThat(originNames.get("apps.x.origins"), is(Optional.of(ImmutableList.of("x-01"))));
    }

    @Test
    public void updatesNonExistingValue() {
        MultiValueConfigTopic<List<String>> originNames = new MultiValueConfigTopic<>(Schedulers.immediate());

        originNames.update("apps.x.origins",
                (maybeValue) -> maybeValue
                        .map(actual -> ImmutableList.<String>of())
                        .orElse(ImmutableList.of("x-01")));

        assertThat(originNames.get("apps.x.origins"), is(Optional.of(ImmutableList.of("x-01"))));
    }
}
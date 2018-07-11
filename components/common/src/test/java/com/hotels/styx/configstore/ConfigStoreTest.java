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
import org.pcollections.HashTreePSet;
import org.pcollections.PSet;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import rx.observers.TestSubscriber;
import rx.schedulers.Schedulers;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static com.hotels.styx.support.matchers.IsOptional.isValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static rx.Notification.Kind.OnCompleted;

public class ConfigStoreTest {
    private ConfigStore configStore;

    @BeforeMethod
    public void setUp() {
        configStore = new ConfigStore(Schedulers.immediate());
    }

    @Test
    public void getsWhatWasSet() {
        configStore.set("foo", "bar");
        assertThat(configStore.get("foo"), isValue("bar"));
    }

//    @Test
//    public void listenersReceiveUpdatesWhenValuesChange() {
//        CountDownLatch sync = new CountDownLatch(1);
//        AtomicReference<String> update = new AtomicReference<>();
//
//        configStore.watch("foo")
//                .subscribe(value -> {
//                    update.set(value);
//                    sync.countDown();
//                });
//
//        configStore.set("foo", "bar");
//        assertThat(update.get(), is("bar"));
//        assertThat(configStore.get("foo", String.class), isValue("bar"));
//    }

//    // If this test fails it will time out
//    @Test
//    public void listensOnSeparateThread() {
//        CyclicBarrier barrier = new CyclicBarrier(2);
//
//        configStore.watch("foo", String.class)
//                .subscribe(value -> await(barrier, 1, SECONDS));
//
//        configStore.set("foo", "bar");
//        await(barrier, 1, SECONDS);
//    }
//
//    // If this test fails it will time out
//    @Test
//    public void multipleListenersCanSubscribeSimultaneously() {
//        CyclicBarrier barrier = new CyclicBarrier(3);
//
//        // Listener 1
//        configStore.watch("x", String.class)
//                .subscribe(value -> await(barrier, 1, SECONDS));
//
//        // Listener 2
//        configStore.watch("x", String.class)
//                .subscribe(value -> await(barrier, 1, SECONDS));
//
//        System.out.println("hello!");
//        configStore.set("x", "bar");
//        await(barrier, 1, SECONDS);
//    }

    @Test
    public void emitsCurrentStateOnSubscribe() {
        configStore.set("foo", "bar");

        AtomicReference<Object> state = new AtomicReference<>();
        CountDownLatch waitingForEvent = new CountDownLatch(1);

        configStore.watch("foo")
                .subscribe(value -> {
                    state.set(value);
                    waitingForEvent.countDown();
                });

        assertThat(state.get(), is("bar"));
    }

    @Test
    public void getsAListOfStrings() {
        configStore.set("apps", ImmutableList.of("la", "sh"));
        Optional<List<String>> value = configStore.get("apps");

        assertThat(value.get(), contains("la", "sh"));
    }

    @Test
    public void watchEmitsOnlyLastValueStored() {
        CopyOnWriteArrayList<List<String>> list = new CopyOnWriteArrayList<>();

        configStore.set("apps", ImmutableList.of("sh"));
        configStore.set("apps", ImmutableList.of("ph"));

        configStore.<List<String>>watch("apps").subscribe(list::add);

        assertThat(list.get(0), is(ImmutableList.of("ph")));
        assertThat(list.size(), is(1));
    }

    @Test
    public void watchesAListOfStrings() {
        CopyOnWriteArrayList<List<String>> list = new CopyOnWriteArrayList<>();

        configStore.<List<String>>watch("apps").subscribe(list::add);

        configStore.set("apps", ImmutableList.of("la", "sh"));
        configStore.set("apps", ImmutableList.of("sh"));
        configStore.set("apps", ImmutableList.of("la", "ph"));
        configStore.set("apps", ImmutableList.of());

        assertThat(list.get(0), contains("la", "sh"));
        assertThat(list.get(1), contains("sh"));
        assertThat(list.get(2), contains("la", "ph"));
        assertThat(list.get(3), is(ImmutableList.of()));
    }

    @Test
    public void clearsAttributeSubscriber() {
        TestSubscriber<String> subscriber = new TestSubscriber<>();

        configStore.set("apps.la", "x");
        configStore.<String>watch("apps.la").subscribe(subscriber);

        configStore.unset("apps.la");

        subscriber.awaitTerminalEvent();
        assertThat(subscriber.getOnCompletedEvents().get(0).getKind(), is(OnCompleted));
    }

}
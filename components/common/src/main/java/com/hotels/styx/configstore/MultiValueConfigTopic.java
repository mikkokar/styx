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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Scheduler;
import rx.schedulers.Schedulers;
import rx.subjects.BehaviorSubject;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import static java.util.concurrent.Executors.newSingleThreadExecutor;

/**
 * Stores data about the current state of the system.
 * <p>
 * All `watch` notification events are executed sequentially in a sepearate config store worker thread.
 *
 * @param <T> Type of the value stored in the MultiValueConfigTopic.
 */
public class MultiValueConfigTopic<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(MultiValueConfigTopic.class);

    private final ConcurrentMap<String, BehaviorSubject<T>> topics = new ConcurrentHashMap<>();
    private final Scheduler scheduler;

    public MultiValueConfigTopic() {
        this(Schedulers.from(newSingleThreadExecutor(runnable -> new Thread(runnable, "Styx-ConfigStore-Worker"))));
    }

    public MultiValueConfigTopic(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Get the current value of a config entry, if present.
     *
     * @param key  key
     * @return value if present, otherwise empty
     */
    public Optional<T> get(String key) {
        LOGGER.info("get({})", key);
        return Optional.ofNullable(this.topics.getOrDefault(key, null))
                .flatMap(subject -> {
                    LOGGER.info("got {}", subject);
                    try {
                        return Optional.ofNullable((T) subject.getValue());
                    } catch (NullPointerException e) {
                        LOGGER.info("NPE");
                        throw e;
                    }
                });
    }

    /**
     * Sets the value of a config entry. This will also publish the new value to watchers.
     *
     * @param key   key
     * @param value new value
     */
    public void set(String key, T value) {
        LOGGER.info("set({}, {})", key, value);

        this.topics.putIfAbsent(key, BehaviorSubject.create());
        this.topics.get(key).onNext(value);
    }

    public void unset(String key) {
        LOGGER.info("unset({})", key);

        BehaviorSubject<?> subject = this.topics.remove(key);
        if (subject != null) {
            subject.onCompleted();
        }
    }

    public Observable<T> watch(String key) {
        LOGGER.info("watch({})", key);

        this.topics.putIfAbsent(key, BehaviorSubject.create());
        Observable<T> subject = this.topics.get(key);

        return subject.observeOn(scheduler);
    }

    public void update(String key, Function<Optional<T>, T> update) {
        LOGGER.info("update({})", key);

        this.topics.putIfAbsent(key, BehaviorSubject.create());
        BehaviorSubject<T> topic = this.topics.get(key);

        topic.onNext(update.apply(Optional.ofNullable(topic.getValue())));
    }
}

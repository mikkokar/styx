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

import static java.util.concurrent.Executors.newSingleThreadExecutor;

/**
 * Stores data about the current state of the system.
 * <p>
 * All `watch` notification events are executed sequentially in a sepearate config store worker thread.
 *
 * @param <T> Type of the value stored in the MultiValueConfigTopic.
 */
public class ConfigTopic<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigTopic.class);

    private final BehaviorSubject<T> topic;
    private final Scheduler scheduler;
    private final String name;

    public ConfigTopic(T initialValue) {
        this("", Schedulers.from(newSingleThreadExecutor(runnable -> new Thread(runnable, "Styx-ConfigStore-Worker"))), initialValue);
    }

    public ConfigTopic(String name, Scheduler scheduler, T initialValue) {
        this.name = name;
        this.scheduler = scheduler;
        this.topic = BehaviorSubject.create();
        this.topic.onNext(initialValue);
    }

    /**
     * Get the current value of a config entry, if present.
     *
     * @return value if present, otherwise empty
     */
    public T get() {
        LOGGER.info("get({})", name);
        return this.topic.getValue();
    }

    /**
     * Sets the value of a config entry. This will also publish the new value to watchers.
     *
     * @param value new value
     */
    public void set(T value) {
        LOGGER.info("set({}, {})", name, value);
        topic.onNext(value);
    }

    public Observable<T> watch() {
        LOGGER.info("watch({})", name);

        return topic.observeOn(scheduler);
    }
}

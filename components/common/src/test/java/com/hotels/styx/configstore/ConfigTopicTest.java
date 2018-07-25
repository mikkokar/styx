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

import org.testng.annotations.Test;
import rx.schedulers.Schedulers;

import java.util.ArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ConfigTopicTest {
    @Test
    public void setsInitalValue() {
        ConfigTopic<String> topic = new ConfigTopic<>("initial");
        assertThat(topic.get(), is("initial"));
    }

    @Test
    public void setsNewValue() {
        ConfigTopic<String> topic = new ConfigTopic<>("initial");
        topic.set("new value");
        assertThat(topic.get(), is("new value"));
    }

    @Test
    public void watchEmitsOnlyLastValueStored() {
        ConfigTopic<String> topic = new ConfigTopic<>("", Schedulers.immediate(), "initial");
        ArrayList<String> list = new ArrayList<>();

        topic.set("sh");
        topic.set("ph");

        topic.watch().subscribe(list::add);

        assertThat(list.get(0), is("ph"));
        assertThat(list.size(), is(1));
    }
}
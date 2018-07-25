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
package com.hotels.styx.proxy;

import com.hotels.styx.api.extension.RemoteHost;
import com.hotels.styx.common.EventProcessor;
import com.hotels.styx.common.QueueDrainingEventProcessor;
import rx.Subscription;

import java.util.List;

import static java.lang.String.format;

/**
 * Handles config store events via event queue.
 */
public class ConfigStoreAdapter {
    private ConfigStore configStore;
    private QueueDrainingEventProcessor eventQueue;

    public ConfigStoreAdapter(ConfigStore configStore, EventProcessor eventProcessor) {
        this.configStore = configStore;
        this.eventQueue = new QueueDrainingEventProcessor(eventProcessor);
    }

    public Subscription watchAppsTopic() {
        return this.configStore.applications()
                .watch()
                .subscribe(
                        appNames -> eventQueue.submit(new AppNamesEvent(appNames)),
                        cause -> eventQueue.submit(new AppNamesError(cause)),
                        () -> eventQueue.submit(new AppNamesCompleted())
                );
    }

    public Subscription watchOriginNamesTopic(String appId) {
        return this.configStore.origins()
                .watch(appId)
                .subscribe(
                        originNames -> eventQueue.submit(new OriginNamesEvent(appId, originNames)),
                        cause -> eventQueue.submit(new OriginNamesError(appId, cause)),
                        () -> eventQueue.submit(new OriginNamesCompleted(appId))
                );
    }

    public Subscription watchRemoteHostEvents(String appId, String originId) {
        return this.configStore.remoteHost()
                .watch(format("%s.%s", appId, originId))
                .subscribe(
                        remoteHost -> eventQueue.submit(new RemoteHostEvent(appId, originId, remoteHost)),
                        cause -> eventQueue.submit(new RemoteHostError(appId, originId, cause)),
                        () -> eventQueue.submit(new RemoteHostCompleted(appId, originId))
                );
    }


    /**
     * AppNamesEvent - sent when AppNames topic changes.
     */
    public static class AppNamesEvent {
        private List<String> appNames;

        public AppNamesEvent(List<String> appNames) {
            this.appNames = appNames;
        }
    }

    /**
     * AppNamesError - sent when an error occurs on AppNames channel.
     */
    public static class AppNamesError {
        private Throwable cause;

        public AppNamesError(Throwable cause) {
            this.cause = cause;
        }
    }

    /**
     * AppNamesError - sent when an error occurs on AppNames channel.
     */

    public class AppNamesCompleted {
    }

    /**
     * AppNamesError - sent when an error occurs on AppNames channel.
     */
    public class OriginNamesEvent {
        private String appId;
        private List<String> originNames;

        public OriginNamesEvent(String appId, List<String> originNames) {
            this.appId = appId;
            this.originNames = originNames;
        }

        public List<String> originNames() {
            return originNames;
        }
    }

    /**
     * AppNamesError - sent when an error occurs on AppNames channel.
     */
    public class OriginNamesError {
        private String appId;
        private Throwable cause;

        public OriginNamesError(String appId, Throwable cause) {
            this.appId = appId;
            this.cause = cause;
        }
    }

    /**
     * AppNamesError - sent when an error occurs on AppNames channel.
     */
    public class OriginNamesCompleted {
        private String appId;

        public OriginNamesCompleted(String appId) {

            this.appId = appId;
        }
    }

    /**
     * AppNamesError - sent when an error occurs on AppNames channel.
     */
    public class RemoteHostEvent {
        private final String appId;
        private final String originId;
        private RemoteHost remoteHost;

        public RemoteHostEvent(String appId, String originId, RemoteHost remoteHost) {
            this.appId = appId;
            this.originId = originId;
            this.remoteHost = remoteHost;
        }

        public RemoteHost remoteHost() {
            return remoteHost;
        }
    }

    /**
     * AppNamesError - sent when an error occurs on AppNames channel.
     */
    public class RemoteHostError {
        private final String appId;
        private final String originId;
        private Throwable cause;

        public RemoteHostError(String appId, String originId, Throwable cause) {
            this.appId = appId;
            this.originId = originId;
            this.cause = cause;
        }
    }

    /**
     * AppNamesError - sent when an error occurs on AppNames channel.
     */
    public class RemoteHostCompleted {
        private final String appId;
        private final String originId;

        public RemoteHostCompleted(String appId, String originId) {

            this.appId = appId;
            this.originId = originId;
        }
    }
}

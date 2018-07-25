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

import com.google.common.collect.ImmutableList;
import com.hotels.styx.api.Id;
import com.hotels.styx.api.extension.RemoteHost;
import com.hotels.styx.common.EventProcessor;
import com.hotels.styx.proxy.ConfigStoreAdapter.OriginNamesEvent;
import com.hotels.styx.proxy.ConfigStoreAdapter.RemoteHostEvent;
import org.pcollections.HashTreePSet;
import org.pcollections.PSet;

import java.util.ArrayList;
import java.util.List;

import static java.util.stream.Collectors.toList;

/**
 * Provides active origins to the Styx client load balancer.
 */
public class ActiveOrigins implements com.hotels.styx.api.extension.ActiveOrigins, EventProcessor {
    private final String appId;
    private final ConfigStoreAdapter adapter;

    private List<RemoteHost> activeOrigins = new ArrayList<>();

    public ActiveOrigins(String appId, ConfigStore configStore) {
        this.appId = appId;
        this.adapter = new ConfigStoreAdapter(configStore, this);
        this.adapter.watchOriginNamesTopic(this.appId);
    }

    @Override
    public Iterable<RemoteHost> snapshot() {
        return ImmutableList.copyOf(activeOrigins);
    }

    @Override
    public void submit(Object event) {
        if (event instanceof OriginNamesEvent) {
            OriginNamesEvent originEvent = (OriginNamesEvent) event;

            removeOldOrigins(originEvent.originNames());

            originEvent.originNames().forEach(
                    originId -> this.adapter.watchRemoteHostEvents(this.appId, originId)
            );
        } else if (event instanceof RemoteHostEvent) {
            RemoteHostEvent updateEvent = (RemoteHostEvent) event;
            RemoteHost host = updateEvent.remoteHost();

            if (!activeOrigins.contains(host)) {
                this.activeOrigins.add(host);
            }
        }
    }

    private void removeOldOrigins(List<String> originNames) {
        PSet<String> currentOriginIds = HashTreePSet.from(
                activeOrigins.stream()
                        .map(RemoteHost::id)
                        .map(Id::toString)
                        .collect(toList()));

        PSet<String> newOriginIds = HashTreePSet.from(originNames);
        PSet<String> removedOriginIds = currentOriginIds.minusAll(newOriginIds);
        activeOrigins = activeOrigins.stream()
                .filter(remoteHost -> !removedOriginIds.contains(remoteHost.id().toString()))
                .collect(toList());
    }
}

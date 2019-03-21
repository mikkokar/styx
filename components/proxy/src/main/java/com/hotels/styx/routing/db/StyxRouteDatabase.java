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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.configuration.RouteDatabase;
import com.hotels.styx.routing.config.RoutingObjectDefinition;
import com.hotels.styx.routing.config.RoutingObjectFactory;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Styx Route Database.
 */

public class StyxRouteDatabase implements RouteDatabase {
    private final ConcurrentHashMap<String, RouteDatabaseRecord> handlers;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final RoutingObjectFactory routingObjectFactory;

    public StyxRouteDatabase(RoutingObjectFactory routingObjectFactory) {
        this.routingObjectFactory = routingObjectFactory;
        this.handlers = new ConcurrentHashMap<>();
    }

    public void insert(String key, RoutingObjectDefinition routingObjectDef) {
        handlers.put(key, new ConfigRecord(key, routingObjectDef, routingObjectDef.tags()));
        notifyListeners();
    }

    public void insert(String key, RoutingObjectDefinition routingObjectDefinition, String... tags) {
        handlers.put(key, new ConfigRecord(key, routingObjectDefinition, ImmutableList.copyOf(tags)));
        notifyListeners();
    }

    @Override
    public void remove(String key) {
        try {
            handlers.remove(key);
            notifyListeners();
        } catch (NullPointerException npe) {
            // pass
        }
    }

    //
    // Needs to run concurrently
    //

    @Override
    public Optional<Record> lookup(String key) {
        return Optional.ofNullable(handlers.get(key))
                .map(record -> {
                    if (record instanceof HandlerRecord) {
                        return ((HandlerRecord) record);
                    } else {
                        HttpHandler handler = routingObjectFactory.build(ImmutableList.of(key), this, ((ConfigRecord) record).configuration());
                        HandlerRecord newRecord = new HandlerRecord(record.key(), handler, record.tags());
                        handlers.put(key, newRecord);
                        return newRecord;
                    }
                })
                .map(this::toRecord);
    }

    private Record toRecord(HandlerRecord record) {
        return new Record() {
            @Override
            public String name() {
                return record.key();
            }

            @Override
            public Set<String> tags() {
                return ImmutableSet.copyOf(record.tags());
            }

            @Override
            public HttpHandler handler() {
                return record.handler();
            }
        };
    }

    @Override
    public Set<Record> tagLookup(String... tags) {
        return handlers.values()
                .stream()
                .filter(record -> asSet(record.tags()).containsAll(asSet(ImmutableList.copyOf(tags))))
                .map(record -> {
                    if (record instanceof HandlerRecord) {
                        return ((HandlerRecord) record);
                    } else {
                        String key = record.key();
                        HttpHandler handler = routingObjectFactory.build(ImmutableList.of(key), this, ((ConfigRecord) record).configuration());
                        HandlerRecord newRecord = new HandlerRecord(record.key(), handler, record.tags());
                        handlers.put(key, newRecord);
                        return newRecord;
                    }
                })
                .map(this::toRecord)
                .collect(Collectors.toSet());
    }
    @Override
    public Optional<HttpHandler> handler(String key) {
        return lookup(key)
                .map(Record::handler);
    }

    @Override
    public Set<HttpHandler> handlers(String... tags) {
        return tagLookup(tags).stream()
                .map(Record::handler)
                .collect(Collectors.toSet());
    }


    @Override
    public void replaceTag(String key, String oldTag, String newTag) {
        Optional.ofNullable(handlers.get(key))
                .ifPresent(record -> record.replaceTag(oldTag, newTag));
        notifyListeners();
    }

    @Override
    public void addListener(Listener listener) {
        this.listeners.add(listener);
    }

    @Override
    public void removeListener(Listener listener) {
        this.listeners.remove(listener);
    }

    private void notifyListeners() {
        listeners.forEach(listener -> listener.updated(this));
    }

    private Set<String> asSet(List<String> inObject) {
        return ImmutableSet.copyOf(inObject);
    }

    private static class RouteDatabaseRecord {
        private final String key;
        private List<String> tags;

        RouteDatabaseRecord(String key, List<String> tags) {
            this.key = key;
            this.tags = ImmutableList.copyOf(tags);
        }

        public String key() {
            return key;
        }

        public List<String> tags() {
            return tags;
        }

        public void replaceTag(String oldTag, String newTag) {
            this.tags = ImmutableList.copyOf(
                    tags.stream()
                            .map(tag -> tag.equals(oldTag) ? newTag : tag)
                            .collect(Collectors.toList()));
        }
    }

    private static class ConfigRecord extends RouteDatabaseRecord {
        private final RoutingObjectDefinition configuration;

        ConfigRecord(String key, RoutingObjectDefinition configuration, List<String> tags) {
            super(key, tags);
            this.configuration = configuration;
        }

        RoutingObjectDefinition configuration() {
            return configuration;
        }
    }

    private static class HandlerRecord extends RouteDatabaseRecord {
        private final HttpHandler handler;

        HandlerRecord(String key, HttpHandler handler, List<String> tags) {
            super(key, tags);
            this.handler = handler;
        }

        HttpHandler handler() {
            return handler;
        }
    }

}

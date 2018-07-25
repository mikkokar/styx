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

import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.api.extension.service.spi.Registry;
import org.pcollections.HashTreePSet;
import org.pcollections.MapPSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.stream.StreamSupport.stream;

/**
 * Backend registry shim.
 */
public class BackendRegistryShim implements Registry.ChangeListener<BackendService> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackendRegistryShim.class);

    private ConfigStore configStore;

    public BackendRegistryShim(ConfigStore configStore) {
        this.configStore = configStore;
    }

    @Override
    public void onChange(Registry.Changes<BackendService> changes) {
        MapPSet<String> initialBackends = HashTreePSet.from(configStore.applications().get());
        checkConsistency(initialBackends, changes);

        // TODO: Mikko: Changes updated?
        changes.removed().forEach(app -> configStore.removeApplication(app.id().toString()));
        changes.added().forEach(app -> configStore.addNewApplication(app.id().toString(), app));
        changes.updated().forEach(this::update);
    }

    // TODO: Mikko: Populate this:
    private void update(BackendService app) {
        // if origins update only ...
        configStore.application().set(app.id().toString(), app);

        // if other attributes changes ...
    }

    private static void checkConsistency(MapPSet<String> initialBackends, Registry.Changes<BackendService> changes) {
        stream(changes.removed().spliterator(), false)
                .map(bs -> bs.id().toString())
                .forEach(
                        appName -> {
                            if (!initialBackends.contains(appName)) {
                                LOGGER.warn("Backend services update: asked to remove an unknown application: '{}'", appName);
                            }
                        }
                );

        stream(changes.updated().spliterator(), false)
                .map(bs -> bs.id().toString())
                .forEach(
                        appName -> {
                            if (!initialBackends.contains(appName)) {
                                LOGGER.warn("Backend services update: asked to update an unknown application: '{}'", appName);
                            }
                        }
                );

        stream(changes.added().spliterator(), false)
                .map(bs -> bs.id().toString())
                .forEach(
                        appName -> {
                            if (initialBackends.contains(appName)) {
                                LOGGER.warn("Backend services update: asked to add an already existing application: '{}'", appName);
                            }
                        }
                );
    }

}


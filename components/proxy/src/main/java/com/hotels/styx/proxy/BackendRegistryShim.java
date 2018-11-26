package com.hotels.styx.proxy;

import com.google.common.collect.Lists;
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.api.extension.service.spi.Registry;
import com.hotels.styx.configstore.ConfigStore;
import org.pcollections.HashTreePSet;
import org.pcollections.MapPSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static java.util.stream.StreamSupport.stream;

public class BackendRegistryShim implements Registry.ChangeListener<BackendService> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackendRegistryShim.class);

    private ConfigStore configStore;

    BackendRegistryShim(ConfigStore configStore) {
        this.configStore = configStore;
    }

    @Override
    public void onChange(Registry.Changes<BackendService> changes) {
        MapPSet<String> initialBackends = configStore.<List<String>>get("apps")
                .map(HashTreePSet::from)
                .orElse(HashTreePSet.empty());

        checkConsistency(initialBackends, changes);

        MapPSet<String> removed = stream(changes.removed().spliterator(), false).reduce(
                initialBackends,
                (backends, backend) -> backends.minus(backend.id().toString()),
                MapPSet::plusAll);

        MapPSet<String> added = stream(changes.added().spliterator(), false).reduce(
                removed,
                (backends, bakend) -> backends.plus(bakend.id().toString()),
                MapPSet::plusAll);

        configStore.set("apps", Lists.newArrayList(added));
        added.forEach(app -> configStore.set("apps." + app, "x"));
        System.out.println("added: " + added);
    }

    private void checkConsistency(MapPSet<String> initialBackends, Registry.Changes<BackendService> changes) {
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


package com.hotels.styx.proxy;

import com.google.common.collect.ImmutableList;
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.api.extension.service.spi.Registry.Changes;
import com.hotels.styx.configstore.ConfigStore;
import com.hotels.styx.support.matchers.LoggingTestSupport;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

import static ch.qos.logback.classic.Level.WARN;
import static com.hotels.styx.api.extension.service.BackendService.newBackendServiceBuilder;
import static com.hotels.styx.support.matchers.LoggingEventMatcher.loggingEvent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;

public class BackendRegistryShimTest {

    private final BackendService landingApp = newBackendServiceBuilder().id("landing").build();
    private final BackendService shoppingApp = newBackendServiceBuilder().id("shopping").build();

    ConfigStore configStore;
    private BackendRegistryShim shim;
    private LoggingTestSupport logger;

    @BeforeMethod
    public void setUp() {
        configStore = new ConfigStore();
        shim = new BackendRegistryShim(configStore);
        logger = new LoggingTestSupport(BackendRegistryShim.class);
    }

    @AfterMethod
    public void tearDown() {
        logger.stop();
    }

    @Test
    public void warnOnInconsistentInputRemovedNonExistingBackend() {
        configStore.set("apps", ImmutableList.of("shopping"));

        shim.onChange(new Changes.Builder<BackendService>()
                .removed(landingApp)
                .build());

        assertThat(logger.log(), contains(
                loggingEvent(WARN, "Backend services update: asked to remove an unknown application: 'landing'")));
    }

    @Test
    public void warnOnInconsistentInputAddedBackendServiceAlreadyExists() {
        configStore.set("apps", ImmutableList.of("landing", "shopping"));

        shim.onChange(new Changes.Builder<BackendService>()
                .added(shoppingApp)
                .build());

        assertThat(logger.log(), contains(
                loggingEvent(WARN, "Backend services update: asked to add an already existing application: 'shopping'")));
    }

    @Test
    public void warnOnInconsistentInputUpdatedNonExistingBackend() {
        configStore.set("apps", ImmutableList.of("shopping"));

        shim.onChange(new Changes.Builder<BackendService>()
                .updated(landingApp)
                .build());

        assertThat(logger.log(), contains(
                loggingEvent(WARN, "Backend services update: asked to update an unknown application: 'landing'")));
    }

    @Test
    public void addsNewAppsToConfiguration() {

        shim.onChange(new Changes.Builder<BackendService>()
                .added(landingApp, shoppingApp)
                .build());

        Optional<List<String>> result = configStore.get("apps");
        assertThat(result.get(), containsInAnyOrder("shopping", "landing"));
    }

    @Test
    public void removesAppsFromConfiguration() {
        configStore.set("apps", ImmutableList.of("shopping", "landing"));

        shim.onChange(new Changes.Builder<BackendService>()
                .removed(landingApp)
                .build());

        Optional<List<String>> result = configStore.get("apps");
        assertThat(result.get(), containsInAnyOrder("shopping"));
    }


}
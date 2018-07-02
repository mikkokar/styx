package com.hotels.styx.api.service.spi;

import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.api.extension.service.spi.Registry;
import org.testng.annotations.Test;

import static com.hotels.styx.api.extension.service.BackendService.newBackendServiceBuilder;

public class RegistryTest {

    private static final BackendService X1 = newBackendServiceBuilder().id("x").path("/x1").build();
    private static final BackendService X2 = newBackendServiceBuilder().id("x").path("/x2").build();

    private static final BackendService Y1 = newBackendServiceBuilder().id("y").path("/y1").build();
    private static final BackendService Y2 = newBackendServiceBuilder().id("y").path("/y2").build();

    @Test(expectedExceptions = IllegalArgumentException.class,
            expectedExceptionsMessageRegExp = "Duplicate id: 'x'")
    public void requiresUniqueIdentitiesBetweenAddedAndRemoved() {
        new Registry.Changes.Builder<>()
                .added(Y1, X1)
                .removed(X2)
                .build();
    }

    @Test(expectedExceptions = IllegalArgumentException.class,
            expectedExceptionsMessageRegExp = "Duplicate id: 'x'")
    public void requiresUniqueIdentitiesBetweenAddedAndUpdated() {
        new Registry.Changes.Builder<>()
                .added(Y1, X1)
                .updated(X2)
                .build();
    }

    @Test(expectedExceptions = IllegalArgumentException.class,
            expectedExceptionsMessageRegExp = "Duplicate id: 'x'")
    public void requiresUniqueIdentitiesBetweenAddedAndAdded() {
        new Registry.Changes.Builder<>()
                .added(Y1, X1, X2)
                .build();
    }

    @Test(expectedExceptions = IllegalArgumentException.class,
            expectedExceptionsMessageRegExp = "Duplicate id: 'x'")
    public void requiresUniqueIdentitiesBetweenUpdatedAndUpdated() {
        new Registry.Changes.Builder<>()
                .updated(Y1, X1, X2)
                .build();
    }

    @Test(expectedExceptions = IllegalArgumentException.class,
            expectedExceptionsMessageRegExp = "Duplicate id: 'y'")
    public void requiresUniqueIdentitiesBetweenUpdatedAndRemoved() {
        new Registry.Changes.Builder<>()
                .updated(Y1, X1)
                .removed(Y1)
                .build();
    }

    @Test(expectedExceptions = IllegalArgumentException.class,
            expectedExceptionsMessageRegExp = "Duplicate id: 'x'")
    public void requiresUniqueIdentitiesBetweenRemovedAndRemoved() {
        new Registry.Changes.Builder<>()
                .removed(Y1, X1, X2)
                .build();
    }

}
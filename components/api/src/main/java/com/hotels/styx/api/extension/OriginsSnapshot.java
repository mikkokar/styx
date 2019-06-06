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
package com.hotels.styx.api.extension;

import com.hotels.styx.api.Id;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Objects.toStringHelper;
import static com.hotels.styx.api.Id.id;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

/**
 * Holds the state of currently configured origins, i.e. whether the origin is active, inactive or disabled.
 */
public final class OriginsSnapshot {
    private final Id appId;
    private final Set<Id> activeOrigins;
    private final Set<Id> inactiveOrigins;
    private final Set<Id> disabledOrigins;
    private final Map<Id, Id> allOriginsById = new HashMap<>();

    OriginsSnapshot(String appId,
                    Collection<Id> activeOrigins,
                    Collection<Id> inactiveOrigins,
                    Collection<Id> disabledOrigins) {
        this.appId = id(appId);
        this.activeOrigins = withAppId(activeOrigins, appId);
        this.inactiveOrigins = withAppId(inactiveOrigins, appId);
        this.disabledOrigins = withAppId(disabledOrigins, appId);
        mapOriginsById();
    }

    /**
     * Construct a snapshot from application ID, and three sets of connection pools: active, inactive and disabled.
     *
     * @param appId           application ID
     * @param activeOrigins   connection pools for active origins
     * @param inactiveOrigins connection pools for inactive origins
     * @param disabledOrigins connection pools for disabled origins
     */
    public OriginsSnapshot(Id appId,
                           Collection<RemoteHost> activeOrigins,
                           Collection<RemoteHost> inactiveOrigins,
                           Collection<RemoteHost> disabledOrigins) {
        this.appId = requireNonNull(appId);
        this.activeOrigins = mapToOrigins(activeOrigins);
        this.inactiveOrigins = mapToOrigins(inactiveOrigins);
        this.disabledOrigins = mapToOrigins(disabledOrigins);
        mapOriginsById();
    }

    private static Set<Id> mapToOrigins(Collection<RemoteHost> activeOrigins) {
        return activeOrigins.stream()
                .map(RemoteHost::id)
                .collect(toSet());
    }

    private static Set<Id> withAppId(Collection<Id> originIds, String appId) {
        return originIds.stream()
                .map(it -> Id.id(format("%s-%s", appId, it)))
                .collect(toSet());
    }

    private void mapOriginsById() {
        this.activeOrigins.forEach(id -> allOriginsById.put(id, id));
        this.inactiveOrigins.forEach(id -> allOriginsById.put(id, id));
        this.disabledOrigins.forEach(id -> allOriginsById.put(id, id));
    }

    /**
     * ID of the application that all of the origins in the inventory belong to.
     *
     * @return application ID
     */
    public Id appId() {
        return appId;
    }

    String appIdAsString() {
        return appId.toString();
    }

    /**
     * Set of active origins.
     *
     * @return active origins
     */
    public Set<Id> activeOrigins() {
        return activeOrigins;
    }

    /**
     * Set of inactive origins.
     *
     * @return inactive origins
     */
    public Set<Id> inactiveOrigins() {
        return inactiveOrigins;
    }

    /**
     * Set of disabled origins.
     *
     * @return disabled origins
     */
    public Set<Id> disabledOrigins() {
        return disabledOrigins;
    }

    /**
     * Finds out whether an origin belongs to this origin inventory, i.e. if it is present within any of the three sets: active, inactive, disabled.
     *
     * @param originId origin ID
     * @return true if the origin belongs to this origin inventory.
     */
    public boolean containsOrigin(Id originId) {
        return allOriginsById.containsKey(originId);
    }

    @Override
    public String toString() {
        return toStringHelper(this)
                .add("appId", appId)
                .add("activeOrigins", activeOrigins)
                .add("inactiveOrigins", inactiveOrigins)
                .add("disabledOrigins", disabledOrigins)
                .toString();
    }
}

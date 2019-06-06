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
package com.hotels.styx.infrastructure.configuration.json.mixins;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hotels.styx.api.Id;

import java.util.Collection;
import java.util.Set;

/**
 * Jackson annotations for {@link com.hotels.styx.api.extension.OriginsSnapshot}.
 */
public abstract class OriginsSnapshotMixin {

    @JsonCreator
    OriginsSnapshotMixin(
            @JsonProperty("appId") String appId,
            @JsonProperty("activeOrigins") Collection<Id> activeOrigins,
            @JsonProperty("inactiveOrigins") Collection<Id> inactiveOrigins,
            @JsonProperty("disabledOrigins") Collection<Id> disabledOrigins) {
    }

    @JsonProperty("appId")
    public abstract String appIdAsString();

    @JsonProperty("activeOrigins")
    public abstract Set<Id> activeOrigins();

    @JsonProperty("inactiveOrigins")
    public abstract Set<Id> inactiveOrigins();

    @JsonProperty("disabledOrigins")
    public abstract Set<Id> disabledOrigins();
}

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
package com.hotels.styx.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;
import com.hotels.styx.api.HeaderKey;
import io.netty.util.AsciiString;

import static com.hotels.styx.api.HeaderKey.headerKey;

/**
 * Contains the names for the headers that Styx will add to proxied requests/responses.
 */
public class StyxHeaderConfig {
    public static final HeaderKey STYX_INFO_DEFAULT = headerKey("X-Styx-Info");
    public static final HeaderKey ORIGIN_ID_DEFAULT = headerKey("X-Styx-Origin-Id");
    public static final HeaderKey REQUEST_ID_DEFAULT = headerKey("X-Styx-Request-Id");

    public static final String STYX_INFO_FORMAT_DEFAULT = "{INSTANCE};{REQUEST_ID}";

    private final HeaderKey styxInfoHeaderName;
    private final HeaderKey originIdHeaderName;
    private final HeaderKey requestIdHeaderName;
    private final String styxInfoHeaderFormat;

    public StyxHeaderConfig(@JsonProperty("styxInfo") StyxHeader styxInfoHeader,
                            @JsonProperty("originId") StyxHeader originIdHeader,
                            @JsonProperty("requestId") StyxHeader requestIdHeader) {
        this.styxInfoHeaderName = name(styxInfoHeader, STYX_INFO_DEFAULT.toString());
        this.originIdHeaderName = name(originIdHeader, ORIGIN_ID_DEFAULT.toString());
        this.requestIdHeaderName = name(requestIdHeader, REQUEST_ID_DEFAULT.toString());
        this.styxInfoHeaderFormat = valueFormat(styxInfoHeader, STYX_INFO_FORMAT_DEFAULT);
    }

    public StyxHeaderConfig() {
        // uses defaults
        this(null, null, null);
    }

    public HeaderKey styxInfoHeaderName() {
        return styxInfoHeaderName;
    }

    public HeaderKey originIdHeaderName() {
        return originIdHeaderName;
    }

    public HeaderKey requestIdHeaderName() {
        return requestIdHeaderName;
    }

    public String styxInfoHeaderFormat() {
        return styxInfoHeaderFormat;
    }

    private static HeaderKey name(StyxHeader header, String defaultName) {
        if (header == null || header.name == null) {
            return new HeaderKey(defaultName);
        }

        return new HeaderKey(header.name);
    }

    private static String valueFormat(StyxHeader header, String formatDefault) {
        if (header == null || header.valueFormat == null) {
            return formatDefault;
        }

        return header.valueFormat;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("styxInfoHeaderName", styxInfoHeaderName)
                .add("originIdHeaderName", originIdHeaderName)
                .add("requestIdHeaderName", requestIdHeaderName)
                .add("styxInfoHeaderFormat", styxInfoHeaderFormat)
                .toString();
    }

    public static final class StyxHeader {
        private final String name;
        private final String valueFormat;

        @JsonCreator
        public StyxHeader(@JsonProperty("name") String name,
                          @JsonProperty("valueFormat") String valueFormat) {
            this.name = name;
            this.valueFormat = valueFormat;
        }

        @Override
        public String toString() {
            return "[name=" + name + ",vf=" + valueFormat + "]";
        }
    }
}

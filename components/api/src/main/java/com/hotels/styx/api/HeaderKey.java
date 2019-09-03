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
package com.hotels.styx.api;

/**
 * HeaderKey class implements a header key.
 */
public class HeaderKey {
    private final CharSequence canonicalRepresentation;
    private final int hashCode;
    private final String content;

    /**
     * A static factory method for header key objects.
     *
     * @param key
     * @return
     */
    public static HeaderKey headerKey(CharSequence key) {
        return new HeaderKey(key);
    }

    /**
     * A HeaderKey constructor.
     *
     * @param content
     */
    public HeaderKey(CharSequence content) {
        this.content = content.toString();
        this.canonicalRepresentation = content.toString().toLowerCase();
        this.hashCode = this.canonicalRepresentation.hashCode();
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        HeaderKey that = (HeaderKey) o;

        return canonicalRepresentation.equals(that.canonicalRepresentation);
    }

    @Override
    public String toString() {
        return content;
    }
}

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

import com.google.common.collect.ImmutableList;
import io.vavr.collection.HashMap;
import io.vavr.control.Option;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static com.hotels.styx.api.HttpHeader.header;
import static java.time.ZoneOffset.UTC;
import static java.util.Locale.US;
import static java.util.stream.StreamSupport.stream;

/**
 * Represent a collection of {@link HttpHeader}s from a single HTTP message.
 */
public final class HttpHeaders implements Iterable<HttpHeader> {
    private static final DateTimeFormatter RFC1123_DATE_FORMAT = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'")
            .withLocale(US)
            .withZone(UTC);

    // CHECKSTYLE:OFF
    private final io.vavr.collection.HashMap<HeaderKey, Object> persistentHeaders;
    // CHECKSTYLE:ON

    private HttpHeaders(Builder builder) {
        this.persistentHeaders = builder.persistentHeaders;
    }

    /**
     * Returns an immutable set that contains the names of all headers in this object.
     *
     * @return header names
     */
    public Set<String> names() {
        return persistentHeaders.keySet()
                .map(HeaderKey::toString)
                .toJavaSet();
    }

    /**
     * Returns an {@link Optional} containing the value of the header with the specified {@code name},
     * if such an element exists.
     * If there are more than one values for the specified name, the first value is returned.
     *
     * @param name header name
     * @return header value if header exists
     */
    public Optional<String> get(CharSequence name) {
        HeaderKey headerKey = new HeaderKey(name);

        return persistentHeaders.get(headerKey)
                .map(value -> {
                    if (value instanceof CharSequence) {
                        return value.toString();
                    } else {
                        assert value instanceof List;
                        assert ((List<String>) value).size() > 0;
                        return ((List<String>) value).get(0);
                    }
                }).toJavaOptional();
    }

    /**
     * Returns an immutable list of header values with the specified {@code name}.
     *
     * @param name The name of the headers
     * @return a list of header values which will be empty if no values
     * are found
     */
    public List<String> getAll(CharSequence name) {
        HeaderKey headerKey = new HeaderKey(name);

        return persistentHeaders.get(headerKey)
                .map(value -> {
                    if (value instanceof CharSequence) {
                        return ImmutableList.of(value.toString());
                    } else {
                        assert value instanceof List;
                        return (List<String>) value;
                    }
                })
                .getOrElse(ImmutableList.of());
    }

    /**
     * Returns {@code true} if this header contains a header with the specified {@code name}.
     *
     * @param name header name
     * @return {@code true} if this map contains a header with the specified {@code name}
     */
    public boolean contains(CharSequence name) {
        HeaderKey headerKey = new HeaderKey(name);

        return persistentHeaders.containsKey(headerKey);
    }

    @Override
    public Iterator<HttpHeader> iterator() {
        return stream(persistentHeaders.iterator().spliterator(), false)
                .flatMap(tuple -> {
                    if (tuple._2() instanceof CharSequence) {
                        return ImmutableList.of(header(tuple._1().toString(), tuple._2().toString())).stream();
                    } else {
                        assert tuple._2() instanceof List;
                        return ((List<String>) tuple._2()).stream()
                                .map(value -> header(tuple._1().toString(), value));
                    }
                })
                .iterator();
    }

    public void forEach(BiConsumer<String, String> consumer) {
        stream(persistentHeaders.iterator().spliterator(), false)
                .flatMap(tuple -> {
                    if (tuple._2() instanceof CharSequence) {
                        return ImmutableList.of(header(tuple._1().toString(), tuple._2().toString())).stream();
                    } else {
                        assert tuple._2() instanceof List;
                        return ((List<String>) tuple._2()).stream()
                                .map(value -> header(tuple._1().toString(), value));
                    }
                })
                .forEach(header -> consumer.accept(header.name(), header.value()));
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    public Builder newBuilder() {
        return new Builder(this);
    }

    @Override
    public String toString() {
        String result = stream(persistentHeaders.iterator().spliterator(), false)
                .flatMap(tuple -> {
                    if (tuple._2() instanceof CharSequence) {
                        return ImmutableList.of(header(tuple._1().toString(), tuple._2().toString())).stream();
                    } else {
                        assert tuple._2() instanceof List;
                        return ((List<String>) tuple._2()).stream()
                                .map(value -> header(tuple._1().toString(), value));
                    }
                })
                .map(header -> header.name() + "=" + header.value())
                .collect(Collectors.joining(", "));

        return "[" + result + "]";
    }

    @Override
    public int hashCode() {
        return Objects.hash(toString());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        HttpHeaders other = (HttpHeaders) obj;
        return Objects.equals(toString(), other.toString());
    }

    /**
     * Builds headers.
     */
    public static class Builder {
        // CHECKSTYLE:OFF
        private io.vavr.collection.HashMap<HeaderKey, Object> persistentHeaders;
        // CHECKSTYLE:ON

        public Builder() {
            this.persistentHeaders = HashMap.empty();
        }

        public Builder(HttpHeaders headers) {
            this.persistentHeaders = headers.persistentHeaders;
        }


        public List<String> getAll(CharSequence name) {
            HeaderKey headerKey = new HeaderKey(name);

            return this.persistentHeaders.get(headerKey)
                    .map(value -> {
                        if (value instanceof CharSequence) {
                            // TODO: ensure that works with strings too!
                            return ImmutableList.of(value.toString());
                        } else {
                            return (List<String>) value;
                        }
                    })
                    .getOrElse(ImmutableList.of());
        }

        public String get(CharSequence name) {
            HeaderKey headerKey = new HeaderKey(name);

            return this.persistentHeaders.get(headerKey)
                    .map(value -> {
                        if (value instanceof CharSequence) {
                            return value.toString();
                        } else {
                            assert value instanceof List;
                            assert ((List<String>) value).size() > 0;

                            return ((List<String>) value).get(0);
                        }
                    })
                    .getOrElse("");
        }

        /**
         * Adds a new header with the specified {@code name} and {@code value}.
         * <p/>
         * Will not replace any existing values for the header.
         *
         * @param name  The name of the header
         * @param value The value of the header
         * @return this builder
         */
        public Builder add(CharSequence name, String value) {
            return addInternal(name, value);
        }

        private Builder addInternal(CharSequence name, String value) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);

            HeaderKey headerKey = new HeaderKey(name);

            Option<Object> previous = this.persistentHeaders.get(headerKey);

            if (previous.isEmpty()) {
                this.persistentHeaders = this.persistentHeaders.put(headerKey, value);
            } else {
                if (previous.get() instanceof CharSequence) {
                    this.persistentHeaders = this.persistentHeaders.put(headerKey, ImmutableList.of(previous.get(), value));
                } else {
                    List<String> values = (List<String>) previous.get();
                    this.persistentHeaders = this.persistentHeaders.put(headerKey, ImmutableList.builder().addAll(values).add(value).build());
                }
            }

            return this;
        }

        /**
         * Adds a new header with the specified {@code name} and {@code value}.
         * <p/>
         * Will not replace any existing values for the header.
         *
         * @param name  The name of the header
         * @param value The value of the header
         * @return this builder
         */
        public Builder add(CharSequence name, Object value) {
            if (value instanceof Collection) {
                ((Collection<String>) value).forEach(element -> {
                    if (element != null) {
                        addInternal(name, element);
                    }
                });
            } else {
                addInternal(name, value.toString());
            }

            return this;
        }

        /**
         * Removes the header with the specified {@code name}.
         *
         * @param name the name of the header to remove
         * @return this builder
         */
        public Builder remove(CharSequence name) {
            HeaderKey headerKey = new HeaderKey(name);

            this.persistentHeaders = this.persistentHeaders.remove(headerKey);
            return this;
        }

        /**
         * Sets the (only) value for the header with the specified name.
         * <p/>
         * All existing values for the same header will be removed.
         *
         * @param name  The name of the header
         * @param value The value of the header
         * @return this builder
         */
        public Builder set(CharSequence name, String value) {
            HeaderKey headerKey = new HeaderKey(name);

            this.persistentHeaders = this.persistentHeaders.put(headerKey, value);
            return this;
        }

        /**
         * Sets the (only) value for the header with the specified name.
         * <p/>
         * All existing values for the same header will be removed.
         *
         * @param name  The name of the header
         * @param value The value of the header
         * @return this builder
         */
        public Builder set(CharSequence name, Instant value) {
            HeaderKey headerKey = new HeaderKey(name);

            this.persistentHeaders = this.persistentHeaders.put(headerKey, RFC1123_DATE_FORMAT.format(value));
            return this;
        }

        /**
         * Sets the (only) value for the header with the specified name.
         * <p/>
         * All existing values for the same header will be removed.
         *
         * @param name  The name of the header
         * @param value The value of the header
         * @return this builder
         */
        public Builder set(CharSequence name, Object value) {
            HeaderKey headerKey = new HeaderKey(name);

            this.persistentHeaders = this.persistentHeaders.remove(headerKey);

            if (value instanceof Collection) {
                ((Collection<String>) value).forEach(element -> {
                    if (element != null) {
                        this.add(name, element);
                    }
                });
            } else  {
                this.add(name, value.toString());
            }

            return this;
        }

        /**
         * Sets the (only) value for the header with the specified name.
         * <p/>
         * All existing values for the same header will be removed.
         *
         * @param name  The name of the header
         * @param value The value of the header
         * @return this builder
         */
        public Builder set(CharSequence name, int value) {
            HeaderKey headerKey = new HeaderKey(name);

            this.persistentHeaders = this.persistentHeaders.put(headerKey, Integer.toString(value));
            return this;
        }

        public HttpHeaders build() {
            return new HttpHeaders(this);
        }
    }

    static class HeaderKey {
        private final CharSequence canonicalRepresentation;
        private final int hashCode;
        private final String content;

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
            return content.toString();
        }
    }

}

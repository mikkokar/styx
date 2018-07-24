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
package com.hotels.styx.admin;

import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.HttpRequest;
import com.hotels.styx.api.HttpResponse;
import com.hotels.styx.api.StyxObservable;
import com.hotels.styx.api.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.hotels.styx.api.HttpResponse.response;
import static com.hotels.styx.api.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static com.hotels.styx.api.HttpResponseStatus.NOT_FOUND;

class UrlPatternRouter implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(UrlPatternRouter.class);

    private final List<RouteDescriptor> alternatives;
    private static final String PLACEHOLDERS_KEY = "UrlRouter.placeholders";

    private UrlPatternRouter(List<RouteDescriptor> alternatives) {
        this.alternatives = alternatives;
    }

    @Override
    public StyxObservable<HttpResponse> handle(HttpRequest request, HttpInterceptor.Context context) {
        for (RouteDescriptor route : alternatives) {
            if (request.method().equals(route.method())) {
                Matcher match = route.pattern().matcher(request.path());

                if (match.matches()) {
                    Map<String, String> placeholders = route.placeholderNames().stream()
                            .collect(Collectors.toMap(name -> name, match::group));

                    context.add(PLACEHOLDERS_KEY, placeholders);

                    try {
                        return route.handler().handle(request, context);
                    } catch (Exception cause) {
                        LOGGER.error("ERROR: {} {}", new Object[] {request.method(), request.path(), cause});
                        return StyxObservable.of(response(INTERNAL_SERVER_ERROR).build());
                    }
                }
            }
        }

        return StyxObservable.of(response(NOT_FOUND).build());
    }

    public static Map<String, String> placeholders(HttpInterceptor.Context context) {
        return context.getIfAvailable(PLACEHOLDERS_KEY, Map.class).get();
    }

    static class Builder {
        private final List<RouteDescriptor> alternatives = new LinkedList<>();

        public Builder get(String regexp, HttpHandler handler) {
            alternatives.add(new RouteDescriptor(HttpMethod.GET, regexp, handler));
            return this;
        }

        public Builder post(String regexp, HttpHandler handler) {
            alternatives.add(new RouteDescriptor(HttpMethod.POST, regexp, handler));
            return this;
        }

        public Builder put(String regexp, HttpHandler handler) {
            alternatives.add(new RouteDescriptor(HttpMethod.PUT, regexp, handler));
            return this;
        }

        public Builder delete(String regexp, HttpHandler handler) {
            alternatives.add(new RouteDescriptor(HttpMethod.DELETE, regexp, handler));
            return this;
        }

        UrlPatternRouter build() {
            return new UrlPatternRouter(alternatives);
        }
    }

    private static class RouteDescriptor {
        private final HttpMethod method;
        private final Pattern pattern;
        private final HttpHandler handler;
        private final List<String> placeholderNames;

        public RouteDescriptor(HttpMethod method, String pattern, HttpHandler handler) {
            this.method = method;
            this.handler = handler;
            this.placeholderNames = placeholcers(pattern);
            this.pattern = compilePattern(pattern);
        }

        public HttpMethod method() {
            return method;
        }

        public Pattern pattern() {
            return pattern;
        }

        public HttpHandler handler() {
            return handler;
        }

        public List<String> placeholderNames() {
            return placeholderNames;
        }

        private Pattern compilePattern(String pattern) {
            Pattern x = Pattern.compile(":([a-zA-Z0-9-_]+)");
            Matcher matcher = x.matcher(pattern);
            return Pattern.compile(matcher.replaceAll("(?<$1>[a-zA-Z0-9-_]+)"));
        }

        private List<String> placeholcers(String pattern) {
            Pattern x = Pattern.compile(":([a-zA-Z0-9-_]+)");
            Matcher matcher = x.matcher(pattern);

            List<String> outcome = new ArrayList<>();

            while (matcher.find()) {
                outcome.add(matcher.group(1));
            }

            return outcome;
        }

    }
}
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
package com.hotels.styx.proxy.interceptors;

import com.hotels.styx.api.Eventual;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.HttpVersion;
import com.hotels.styx.api.LiveHttpRequest;
import com.hotels.styx.api.LiveHttpResponse;
import com.hotels.styx.client.StyxHeaderConfig;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.Optional;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.hotels.styx.api.HttpHeaderNames.CONNECTION;
import static com.hotels.styx.api.HttpHeaderNames.KEEP_ALIVE;
import static com.hotels.styx.api.HttpHeaderNames.PROXY_AUTHENTICATE;
import static com.hotels.styx.api.HttpHeaderNames.PROXY_AUTHORIZATION;
import static com.hotels.styx.api.HttpHeaderNames.TE;
import static com.hotels.styx.api.HttpHeaderNames.TRAILER;
import static com.hotels.styx.api.HttpHeaderNames.TRANSFER_ENCODING;
import static com.hotels.styx.api.HttpHeaderNames.UPGRADE;
import static com.hotels.styx.api.HttpHeaderNames.VIA;
import static com.hotels.styx.api.HttpHeaderNames.X_FORWARDED_FOR;
import static com.hotels.styx.api.HttpHeaderNames.X_FORWARDED_PROTO;
import static com.hotels.styx.api.HttpVersion.HTTP_1_0;
import static io.netty.handler.codec.http.HttpHeaders.newEntity;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Temporary class for perf testing.
 */
public class CombinedInterceptor implements HttpInterceptor {
    private static final CharSequence VIA_STYX_1_0 = newEntity("1.0 styx");
    private static final CharSequence VIA_STYX_1_1 = newEntity("1.1 styx");

    private static final Logger LOGGER = getLogger(RequestEnrichingInterceptor.class);

    private final CharSequence requestIdHeaderName;

    /**
     * Temporary class constructor for perf testing.
     */
    public CombinedInterceptor(StyxHeaderConfig styxHeaderConfig) {
        this.requestIdHeaderName = styxHeaderConfig.requestIdHeaderName();
    }

    @Override
    public Eventual<LiveHttpResponse> intercept(LiveHttpRequest request, Chain chain) {
        LiveHttpRequest.Transformer newRequest = request.newBuilder();

        // Via header
        newRequest.header(VIA, viaHeader(request));

        // Hop By Hop headers:
        removeHopByHopHeaders(request, newRequest);

        // Enrich request:
        enrich(request, newRequest, chain.context());

        return chain.proceed(newRequest.build())
                .map(response -> {
                    LiveHttpResponse.Transformer newResponse = response.newBuilder();

                    // Via header:
                    newResponse.header(VIA, viaHeader(response));

                    // Hop-by-hop headers:
                    removeHopByHopHeaders(response, newResponse);

                    return newResponse.build();
                });
    }

    private static CharSequence viaHeader(LiveHttpRequest httpMessage) {
        CharSequence styxViaEntry = styxViaEntry(httpMessage.version());

        return httpMessage.headers().get(VIA)
                .map(viaHeader -> !isNullOrEmpty(viaHeader) ? viaHeader + ", " + styxViaEntry : styxViaEntry)
                .orElse(styxViaEntry);
    }

    private static CharSequence viaHeader(LiveHttpResponse httpMessage) {
        CharSequence styxViaEntry = styxViaEntry(httpMessage.version());

        return httpMessage.headers().get(VIA)
                .map(viaHeader -> !isNullOrEmpty(viaHeader) ? viaHeader + ", " + styxViaEntry : styxViaEntry)
                .orElse(styxViaEntry);
    }

    private static CharSequence styxViaEntry(HttpVersion httpVersion) {
        return httpVersion.equals(HTTP_1_0) ? VIA_STYX_1_0 : VIA_STYX_1_1;
    }

    private static void removeHopByHopHeaders(LiveHttpRequest request, LiveHttpRequest.Transformer newRequest) {

        request.header(CONNECTION).ifPresent(connection -> {
            for (String connectToken : connection.split(",")) {
                String header = connectToken.trim();
                newRequest.removeHeader(header);
            }
            newRequest.removeHeader(CONNECTION);
        });

        newRequest
                .removeHeader(KEEP_ALIVE)
                .removeHeader(PROXY_AUTHENTICATE)
                .removeHeader(PROXY_AUTHORIZATION)
                .removeHeader(TE)
                .removeHeader(TRAILER)
                .removeHeader(UPGRADE);
    }

    private static void removeHopByHopHeaders(LiveHttpResponse response, LiveHttpResponse.Transformer newResponse) {
        response.header(CONNECTION).ifPresent(connection -> {
            for (String connectToken : connection.split(",")) {
                String header = connectToken.trim();
                newResponse.removeHeader(header);
            }
            newResponse.removeHeader(CONNECTION);
        });

        newResponse
                .removeHeader(KEEP_ALIVE)
                .removeHeader(PROXY_AUTHENTICATE)
                .removeHeader(PROXY_AUTHORIZATION)
                .removeHeader(TE)
                .removeHeader(TRAILER)
                .removeHeader(TRANSFER_ENCODING)
                .removeHeader(UPGRADE);

    }

    private void enrich(LiveHttpRequest request, LiveHttpRequest.Transformer newRequest, Context context) {

        xForwardedFor(request, context)
                .ifPresent(headerValue -> newRequest.header(X_FORWARDED_FOR, headerValue));

        newRequest
                .header(requestIdHeaderName, request.id())
                .header(X_FORWARDED_PROTO, xForwardedProto(request, context.isSecure()));
    }

    private static Optional<String> xForwardedFor(LiveHttpRequest request, HttpInterceptor.Context context) {
        Optional<String> maybeClientAddress = context.clientAddress()
                .map(InetSocketAddress::getHostString)
                .map(hostName -> request
                        .header(X_FORWARDED_FOR)
                        .map(xForwardedFor -> xForwardedFor + ", " + hostName)
                        .orElse(hostName));

        if (!maybeClientAddress.isPresent()) {
            LOGGER.warn("No clientAddress in context url={}", request.url());
        }

        return maybeClientAddress;
    }

    private static CharSequence xForwardedProto(LiveHttpRequest request, boolean secure) {
        return request
                .header(X_FORWARDED_PROTO)
                .orElse(secure ? "https" : "http");
    }
}

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

import com.hotels.styx.api.HeaderKey;
import com.hotels.styx.api.HttpInterceptor.Chain;
import com.hotels.styx.api.LiveHttpRequest;
import com.hotels.styx.api.LiveHttpResponse;
import org.testng.annotations.Test;
import reactor.core.publisher.Mono;

import static com.hotels.styx.api.HttpHeaderNames.CONNECTION;
import static com.hotels.styx.api.LiveHttpRequest.delete;
import static com.hotels.styx.api.LiveHttpRequest.get;
import static com.hotels.styx.api.LiveHttpRequest.post;
import static com.hotels.styx.api.LiveHttpResponse.response;
import static com.hotels.styx.proxy.interceptors.RequestRecordingChain.requestRecordingChain;
import static com.hotels.styx.proxy.interceptors.ReturnResponseChain.returnsResponse;
import static com.hotels.styx.support.matchers.IsOptional.isAbsent;
import static io.netty.handler.codec.http.HttpHeaders.Names.PROXY_AUTHENTICATE;
import static io.netty.handler.codec.http.HttpHeaders.Names.PROXY_AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaders.Names.TE;
import static org.hamcrest.MatcherAssert.assertThat;

public class HopByHopHeadersRemovingInterceptorTest {
    final Chain ANY_RESPONSE_HANDLER = returnsResponse(response().build());
    final HopByHopHeadersRemovingInterceptor interceptor = new HopByHopHeadersRemovingInterceptor();

    @Test
    public void removesHopByHopHeadersFromRequest() {
        LiveHttpRequest request = get("/foo")
                .header(HeaderKey.headerKey(TE), "Foo")
                .header(HeaderKey.headerKey(PROXY_AUTHENTICATE), "foo")
                .header(HeaderKey.headerKey(PROXY_AUTHORIZATION), "bar")
                .build();

        LiveHttpRequest interceptedRequest = interceptRequest(request);

        assertThat(interceptedRequest.header(HeaderKey.headerKey(TE)), isAbsent());
        assertThat(interceptedRequest.header(HeaderKey.headerKey(PROXY_AUTHENTICATE)), isAbsent());
        assertThat(interceptedRequest.header(HeaderKey.headerKey(PROXY_AUTHORIZATION)), isAbsent());
    }

    private LiveHttpRequest interceptRequest(LiveHttpRequest request) {
        RequestRecordingChain recording = requestRecordingChain(ANY_RESPONSE_HANDLER);
        interceptor.intercept(request, recording);
        return recording.recordedRequest();
    }

    @Test
    public void removesHopByHopHeadersFromResponse() throws Exception {
        LiveHttpResponse response = Mono.from(interceptor.intercept(get("/foo").build(), returnsResponse(response()
                .header(HeaderKey.headerKey(TE), "foo")
                .header(HeaderKey.headerKey(PROXY_AUTHENTICATE), "foo")
                .header(HeaderKey.headerKey(PROXY_AUTHORIZATION), "bar")
                .build()))).block();

        assertThat(response.header(HeaderKey.headerKey(TE)), isAbsent());
        assertThat(response.header(HeaderKey.headerKey(PROXY_AUTHENTICATE)), isAbsent());
        assertThat(response.header(HeaderKey.headerKey(PROXY_AUTHORIZATION)), isAbsent());
    }

    @Test
    public void removesAllFieldsWhenMultipleInstancesArePresent() {
        LiveHttpRequest request = delete("/foo")
                .header(HeaderKey.headerKey(PROXY_AUTHENTICATE), "foo")
                .header(HeaderKey.headerKey(PROXY_AUTHENTICATE), "bar")
                .header(HeaderKey.headerKey(PROXY_AUTHENTICATE), "baz")
                .build();

        LiveHttpRequest interceptedRequest = interceptRequest(request);

        assertThat(interceptedRequest.header(HeaderKey.headerKey(PROXY_AUTHENTICATE)), isAbsent());
    }

    @Test
    public void removesConnectionHeaderContainingOneTokenOnly() {
        LiveHttpRequest request = delete("/foo")
                .header(CONNECTION, "Foo")
                .header(HeaderKey.headerKey("Foo"), "abc")
                .build();

        LiveHttpRequest interceptedRequest = interceptRequest(request);

        assertThat(interceptedRequest.header(CONNECTION), isAbsent());
        assertThat(interceptedRequest.header(HeaderKey.headerKey("Foo")), isAbsent());
    }

    @Test
    public void removesConnectionHeaders() {
        LiveHttpRequest request = post("/foo")
                .header(CONNECTION, "Foo, Bar, Baz")
                .header(HeaderKey.headerKey("Foo"), "abc")
                .header(HeaderKey.headerKey("Foo"), "def")
                .header(HeaderKey.headerKey("Bar"), "one, two, three")
                .build();

        LiveHttpRequest interceptedRequest = interceptRequest(request);

        assertThat(interceptedRequest.header(CONNECTION), isAbsent());
        assertThat(interceptedRequest.header(HeaderKey.headerKey("Foo")), isAbsent());
        assertThat(interceptedRequest.header(HeaderKey.headerKey("Bar")), isAbsent());
        assertThat(interceptedRequest.header(HeaderKey.headerKey("Baz")), isAbsent());
    }

    @Test
    public void removesConnectionHeadersFromResponse() throws Exception {
        LiveHttpResponse response = Mono.from(interceptor.intercept(get("/foo").build(), returnsResponse(response()
                .header(CONNECTION, "Foo, Bar, Baz")
                .header(HeaderKey.headerKey("Foo"), "abc")
                .header(HeaderKey.headerKey("Foo"), "def")
                .header(HeaderKey.headerKey("Bar"), "one, two, three")
                .build()))).block();

        assertThat(response.header(CONNECTION), isAbsent());
        assertThat(response.header(HeaderKey.headerKey("Foo")), isAbsent());
        assertThat(response.header(HeaderKey.headerKey("Bar")), isAbsent());
        assertThat(response.header(HeaderKey.headerKey("Baz")), isAbsent());
    }
}
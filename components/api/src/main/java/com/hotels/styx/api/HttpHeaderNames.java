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

import io.netty.handler.codec.http.HttpHeaders.Names;
import io.netty.handler.codec.http.HttpHeaders.Values;

import static io.netty.handler.codec.http.HttpHeaders.newEntity;

/**
 * Provides optimized constants for the standard HTTP header names.
 */
public final class HttpHeaderNames {
    public static final HeaderKey USER_AGENT = new HeaderKey(Names.USER_AGENT);
    public static final HeaderKey CONTENT_TYPE = new HeaderKey(Names.CONTENT_TYPE);
    public static final HeaderKey CONTENT_LENGTH = new HeaderKey(Names.CONTENT_LENGTH);
    public static final HeaderKey COOKIE = new HeaderKey(Names.COOKIE);
    public static final HeaderKey LOCATION = new HeaderKey(Names.LOCATION);

    public static final HeaderKey CONNECTION = new HeaderKey(Names.CONNECTION);
    public static final HeaderKey HOST = new HeaderKey(Names.HOST);
    public static final HeaderKey DATE = new HeaderKey(Names.DATE);
    public static final HeaderKey EXPECT = new HeaderKey(Names.EXPECT);
    public static final HeaderKey CONTINUE = new HeaderKey(Values.CONTINUE);
    public static final HeaderKey TRANSFER_ENCODING = new HeaderKey(Names.TRANSFER_ENCODING);
    public static final CharSequence CHUNKED = newEntity(Values.CHUNKED);

    public static final HeaderKey SET_COOKIE = new HeaderKey(Names.SET_COOKIE);
    public static final HeaderKey X_FORWARDED_FOR = new HeaderKey("X-Forwarded-For");
    public static final HeaderKey X_FORWARDED_PROTO = new HeaderKey("X-Forwarded-Proto");

    public static final HeaderKey KEEP_ALIVE = new HeaderKey(Values.KEEP_ALIVE);
    public static final HeaderKey PROXY_AUTHENTICATE = new HeaderKey(Names.PROXY_AUTHENTICATE);
    public static final HeaderKey PROXY_AUTHORIZATION = new HeaderKey(Names.PROXY_AUTHORIZATION);

    public static final HeaderKey TE = new HeaderKey(Names.TE);
    public static final HeaderKey TRAILER = new HeaderKey(Names.TRAILER);
    public static final HeaderKey UPGRADE = new HeaderKey(Names.UPGRADE);
    public static final HeaderKey VIA = new HeaderKey(Names.VIA);
    public static final HeaderKey PRAGMA = new HeaderKey("Pragma");
    public static final HeaderKey EXPIRES = new HeaderKey("Expires");
    public static final HeaderKey CACHE_CONTROL = new HeaderKey("Cache-Control");

    private HttpHeaderNames() {
    }
}

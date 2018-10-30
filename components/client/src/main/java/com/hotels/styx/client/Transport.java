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
package com.hotels.styx.client;

import com.hotels.styx.api.LiveHttpRequest;
import com.hotels.styx.api.LiveHttpResponse;
import com.hotels.styx.api.Id;
import com.hotels.styx.api.ResponseEventListener;
import com.hotels.styx.api.exceptions.NoAvailableHostsException;
import com.hotels.styx.client.connectionpool.ConnectionPool;
import rx.Observable;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * Encapsulates a single connection to remote server which we can use to send the messages.
 */
class Transport {
    private final Id appId;
    private final CharSequence originIdHeaderName;

    public Transport(Id appId, CharSequence originIdHeaderName) {
        this.appId = requireNonNull(appId);
        this.originIdHeaderName = requireNonNull(originIdHeaderName);
    }

    public HttpTransaction send(LiveHttpRequest request, Optional<ConnectionPool> origin, Id originId) {
        Observable<Connection> connection = connection(request, origin);

        AtomicReference<Connection> connectionRef = new AtomicReference<>(null);
        Observable<LiveHttpResponse> observableResponse = connection.flatMap(tConnection -> {
            connectionRef.set(tConnection);
            return tConnection.write(request)
                    .map(response -> addOriginId(originId, response));
        });

        return new HttpTransaction() {
            @Override
            public Observable<LiveHttpResponse> response() {
                return ResponseEventListener.from(observableResponse)
                        .whenCancelled(() -> closeIfConnected(origin, connectionRef))
                        .whenResponseError(cause -> closeIfConnected(origin, connectionRef))
                        .whenContentError(cause -> closeIfConnected(origin, connectionRef))
                        .whenCompleted(() -> returnIfConnected(origin, connectionRef))
                        .apply();
            }

            private synchronized void closeIfConnected(Optional<ConnectionPool> connectionPool, AtomicReference<Connection> connectionRef) {
                Connection connection = connectionRef.get();
                if (connection != null && connectionPool.isPresent()) {
                    connectionPool.get().closeConnection(connection);
                    connectionRef.set(null);
                }
            }

            private synchronized void returnIfConnected(Optional<ConnectionPool> connectionPool, AtomicReference<Connection> connectionRef) {
                Connection connection = connectionRef.get();
                if (connection != null && connectionPool.isPresent()) {
                    connectionPool.get().returnConnection(connection);
                    connectionRef.set(null);
                }
            }
        };
    }

    private Observable<Connection> connection(LiveHttpRequest request, Optional<ConnectionPool> origin) {
        return origin
                .map(ConnectionPool::borrowConnection)
                .orElseGet(() -> {
                    // Aggregates an empty body:
                    request.consume();
                    return Observable.error(new NoAvailableHostsException(appId));
                });
    }

    private LiveHttpResponse addOriginId(Id originId, LiveHttpResponse response) {
        return response.newBuilder()
                .header(originIdHeaderName, originId)
                .build();
    }
}

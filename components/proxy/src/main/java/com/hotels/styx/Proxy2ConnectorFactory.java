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
package com.hotels.styx;

import com.codahale.metrics.Histogram;
import com.hotels.styx.api.HttpHandler;
import com.hotels.styx.api.MetricRegistry;
import com.hotels.styx.proxy.HttpCompressor;
import com.hotels.styx.proxy.ServerProtocolDistributionRecorder;
import com.hotels.styx.proxy.encoders.ConfigurableUnwiseCharsEncoder;
import com.hotels.styx.server.HttpErrorStatusListener;
import com.hotels.styx.server.RequestStatsCollector;
import com.hotels.styx.server.netty.NettyServerConfig;
import com.hotels.styx.server.netty.ServerConnector;
import com.hotels.styx.server.netty.ServerConnectorFactory;
import com.hotels.styx.server.netty.codec.NettyToStyxRequestDecoder;
import com.hotels.styx.server.netty.codec.UnwiseCharsEncoder;
import com.hotels.styx.server.netty.connectors.HttpPipelineHandler;
import com.hotels.styx.server.netty.connectors.ResponseEnhancer;
import com.hotels.styx.server.netty.handlers.ChannelActivityEventConstrainer;
import com.hotels.styx.server.netty.handlers.ChannelStatisticsHandler;
import com.hotels.styx.server.netty.handlers.ExcessConnectionRejector;
import com.hotels.styx.server.netty.handlers.RequestTimeoutHandler;
import com.hotels.styx.server.track.CurrentRequestTracker;
import com.hotels.styx.server.track.RequestTracker;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.buffer.Unpooled.unreleasableBuffer;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.logging.LogLevel.INFO;
import static io.netty.handler.ssl.ApplicationProtocolNames.HTTP_1_1;
import static io.netty.handler.ssl.ApplicationProtocolNames.HTTP_2;
import static io.netty.handler.timeout.IdleState.ALL_IDLE;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Factory for proxy connectors.
 */
// TODO: Could we make it non-public?
public class Proxy2ConnectorFactory implements ServerConnectorFactory {
    private final MetricRegistry metrics;
    private final HttpErrorStatusListener errorStatusListener;
    private final NettyServerConfig serverConfig;
    private final String unwiseCharacters;
    private final ResponseEnhancer responseEnhancer;
    private final boolean requestTracking;

    public Proxy2ConnectorFactory(NettyServerConfig serverConfig,
                                  MetricRegistry metrics,
                                  HttpErrorStatusListener errorStatusListener,
                                  String unwiseCharacters,
                                  ResponseEnhancer responseEnhancer,
                                  boolean requestTracking) {
        this.serverConfig = requireNonNull(serverConfig);
        this.metrics = requireNonNull(metrics);
        this.errorStatusListener = requireNonNull(errorStatusListener);
        this.unwiseCharacters = requireNonNull(unwiseCharacters);
        this.responseEnhancer = requireNonNull(responseEnhancer);
        this.requestTracking = requestTracking;
    }

    @Override
    public ServerConnector create(int port, SslContext sslContext) {
        return new ProxyConnector(port, serverConfig, metrics, errorStatusListener, unwiseCharacters, responseEnhancer, requestTracking, sslContext);
    }

    private static final class ProxyConnector implements ServerConnector {
        private final int port;
        private final NettyServerConfig serverConfig;
        private final MetricRegistry metrics;
        private final HttpErrorStatusListener httpErrorStatusListener;
        private final ChannelStatisticsHandler channelStatsHandler;
        private final ExcessConnectionRejector excessConnectionRejector;
        private final RequestStatsCollector requestStatsCollector;
        private final ConfigurableUnwiseCharsEncoder unwiseCharEncoder;
        private final SslContext sslContext;
        private final ResponseEnhancer responseEnhancer;
        private final RequestTracker requestTracker;

        private ProxyConnector(int port,
                               NettyServerConfig serverConfig,
                               MetricRegistry metrics,
                               HttpErrorStatusListener errorStatusListener,
                               String unwiseCharacters,
                               ResponseEnhancer responseEnhancer,
                               boolean requestTracking,
                               SslContext sslContext) {
            this.port = port;
            this.responseEnhancer = requireNonNull(responseEnhancer);
            this.serverConfig = requireNonNull(serverConfig);
            this.metrics = requireNonNull(metrics);
            this.httpErrorStatusListener = requireNonNull(errorStatusListener);
            this.channelStatsHandler = new ChannelStatisticsHandler(metrics);
            this.requestStatsCollector = new RequestStatsCollector(metrics.scope("requests"));
            this.excessConnectionRejector = new ExcessConnectionRejector(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE), serverConfig.maxConnectionsCount());
            this.unwiseCharEncoder = new ConfigurableUnwiseCharsEncoder(unwiseCharacters);

            this.sslContext = requireNonNull(sslContext);

            this.requestTracker = requestTracking ? CurrentRequestTracker.INSTANCE : RequestTracker.NO_OP;
        }

        @Override
        public int port() {
            return this.port;
        }

        @Override
        public void configure(Channel channel, HttpHandler handler) {

                SslHandler sslHandler = sslContext.newHandler(channel.alloc());
                channel.pipeline().addLast(sslHandler, new Http2OrHttpHandler(handler));
        }


        private NettyToStyxRequestDecoder requestTranslator() {
            return new NettyToStyxRequestDecoder.Builder()
                    .flowControlEnabled(true)
                    .unwiseCharEncoder(unwiseCharEncoder)
                    .build();
        }

        private boolean isHttps() {
            return true;
        }

        private class Http2OrHttpHandler extends ApplicationProtocolNegotiationHandler {
            private final HttpHandler handler;

            protected Http2OrHttpHandler(HttpHandler handler) {
                super(HTTP_1_1);
                this.handler = handler;
            }

            @Override
            protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
                if (HTTP_2.equals(protocol)) {
                    ctx.pipeline()
                            .addLast("connection-throttler", excessConnectionRejector)
                            .addLast("channel-activity-event-constrainer", new ChannelActivityEventConstrainer())
                            .addLast("idle-handler", new IdleStateHandler(serverConfig.requestTimeoutMillis(), 0, serverConfig.keepAliveTimeoutMillis(), MILLISECONDS))
                            .addLast("channel-stats", channelStatsHandler)

                            // Http Server Codec
                            .addLast(new Http2PipelineHandlerBuilder(unwiseCharEncoder).build());
                } else if (HTTP_1_1.equals(protocol)) {
                    ctx.pipeline()
                            .addLast("connection-throttler", excessConnectionRejector)
                            .addLast("channel-activity-event-constrainer", new ChannelActivityEventConstrainer())
                            .addLast("idle-handler", new IdleStateHandler(serverConfig.requestTimeoutMillis(), 0, serverConfig.keepAliveTimeoutMillis(), MILLISECONDS))
                            .addLast("channel-stats", channelStatsHandler)

                            // Http Server Codec
                            .addLast("http-server-codec", new HttpServerCodec(serverConfig.maxInitialLength(), serverConfig.maxHeaderSize(), serverConfig.maxChunkSize(), true))

                            // idle-handler and timeout-handler must be before aggregator. Otherwise
                            // timeout handler cannot see the incoming HTTP chunks.
                            .addLast("timeout-handler", new RequestTimeoutHandler())

                            .addLast("keep-alive-handler", new IdleTransactionConnectionCloser(metrics))

                            .addLast("server-protocol-distribution-recorder", new ServerProtocolDistributionRecorder(metrics, true))

                            .addLast("styx-decoder", requestTranslator())

                            .addLast("proxy", new HttpPipelineHandler.Builder(handler)
                                    .responseEnhancer(responseEnhancer)
                                    .errorStatusListener(httpErrorStatusListener)
                                    .progressListener(requestStatsCollector)
                                    .metricRegistry(metrics)
                                    .secure(true)
                                    .requestTracker(requestTracker)
                                    .build());

                    if (serverConfig.compressResponses()) {
                        ctx.pipeline().addBefore("styx-decoder", "compression", new HttpCompressor());
                    }

                } else {
                    throw new IllegalStateException("unknown protocol: " + protocol);
                }
            }
        }

        private static final class Http2PipelineHandlerBuilder extends AbstractHttp2ConnectionHandlerBuilder<Http2PipelineHandler, Http2PipelineHandlerBuilder> {
            private static final Http2FrameLogger logger = new Http2FrameLogger(INFO, Http2PipelineHandler.class);
            private final UnwiseCharsEncoder unwiseCharsEncoder;

            public Http2PipelineHandlerBuilder(UnwiseCharsEncoder unwiseCharsEncoder) {
                this.unwiseCharsEncoder = unwiseCharsEncoder;
                frameLogger(logger);
            }

            @Override
            protected Http2PipelineHandler build() {
                return super.build();
            }

            @Override
            protected Http2PipelineHandler build(Http2ConnectionDecoder decoder,
                                                 Http2ConnectionEncoder encoder,
                                                 Http2Settings initialSettings) {
                Http2PipelineHandler handler = new Http2PipelineHandler(decoder, encoder, initialSettings, unwiseCharsEncoder);
                frameListener(handler);
                return handler;
            }
        }

        private static class Http2PipelineHandler extends Http2ConnectionHandler implements Http2FrameListener {
            private UnwiseCharsEncoder unwiseCharsEncoder;
//
//            /*
//                //    // idle-handler and timeout-handler must be before aggregator. Otherwise
//                //    // timeout handler cannot see the incoming HTTP chunks.
//                //    .addLast("timeout-handler", new RequestTimeoutHandler())
//                //    .addLast("keep-alive-handler", new IdleTransactionConnectionCloser(metrics))
//                //    .addLast("server-protocol-distribution-recorder", new ServerProtocolDistributionRecorder(metrics, sslContext.isPresent()))
//                //    .addLast("styx-decoder", requestTranslator())
//                //
//                //    .addLast("proxy", new HttpPipelineHandler.Builder(handler)
//                //            .responseEnhancer(responseEnhancer)
//                //            .errorStatusListener(httpErrorStatusListener)
//                //            .progressListener(requestStatsCollector)
//                //            .metricRegistry(metrics)
//                //            .secure(sslContext.isPresent())
//                //            .requestTracker(requestTracker)
//                //            .build());
//            */
//
//            LiveHttpRequest.Builder makeAStyxRequestFrom(HttpRequest request, Observable<ByteBuf> content) {
//                Url url = UrlDecoder.decodeUrl(unwiseCharsEncoder, request);
//                LiveHttpRequest.Builder requestBuilder = new LiveHttpRequest.Builder()
//                        .method(toStyxMethod(request.method()))
//                        .url(url)
//                        .version(toStyxVersion(request.protocolVersion()))
//                        .id(UUID_VERSION_ONE_SUPPLIER.get())
//                        .body(new ByteStream(toPublisher(content.map(Buffers::fromByteBuf))));
//
//                stream(request.headers().spliterator(), false)
//                        .forEach(entry -> requestBuilder.addHeader(entry.getKey(), entry.getValue()));
//
//                return requestBuilder;
//            }
//
//            private static com.hotels.styx.api.HttpMethod toStyxMethod(HttpMethod method) {
//                return com.hotels.styx.api.HttpMethod.httpMethod(method.name());
//            }
//
//            private static com.hotels.styx.api.HttpVersion toStyxVersion(io.netty.handler.codec.http.HttpVersion httpVersion) {
//                return com.hotels.styx.api.HttpVersion.httpVersion(httpVersion.toString());
//            }

            static final ByteBuf RESPONSE_BYTES = unreleasableBuffer(copiedBuffer("Hello World", CharsetUtil.UTF_8));

            private static Http2Headers http1HeadersToHttp2Headers(FullHttpRequest request) {
                CharSequence host = request.headers().get(HttpHeaderNames.HOST);
                Http2Headers http2Headers = new DefaultHttp2Headers()
                        .method(HttpMethod.GET.asciiName())
                        .path(request.uri())
                        .scheme(HttpScheme.HTTP.name());
                if (host != null) {
                    http2Headers.authority(host);
                }
                return http2Headers;
            }

            /**
             * Handles the cleartext HTTP upgrade event. If an upgrade occurred, sends a simple response via HTTP/2
             * on stream 1 (the stream specifically reserved for cleartext HTTP upgrade).
             */
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (evt instanceof HttpServerUpgradeHandler.UpgradeEvent) {
                    HttpServerUpgradeHandler.UpgradeEvent upgradeEvent =
                            (HttpServerUpgradeHandler.UpgradeEvent) evt;
                    onHeadersRead(ctx, 1, http1HeadersToHttp2Headers(upgradeEvent.upgradeRequest()), 0, true);
                }
                super.userEventTriggered(ctx, evt);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                super.exceptionCaught(ctx, cause);
                cause.printStackTrace();
                ctx.close();
            }

            Http2PipelineHandler(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder, Http2Settings initialSettings, UnwiseCharsEncoder unwiseCharsEncoder) {
                super(decoder, encoder, initialSettings);
                this.unwiseCharsEncoder = unwiseCharsEncoder;
            }


            /**
             * Sends a "Hello World" DATA frame to the client.
             */
            private void sendResponse(ChannelHandlerContext ctx, int streamId, ByteBuf payload) {
                // Send a frame for the response status
                Http2Headers headers = new DefaultHttp2Headers().status(OK.codeAsText());
                encoder().writeHeaders(ctx, streamId, headers, 0, false, ctx.newPromise());
                encoder().writeData(ctx, streamId, payload, 0, true, ctx.newPromise());

                // no need to call flush as channelReadComplete(...) will take care of it.
            }

            @Override
            public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) throws Http2Exception {
                int processed = data.readableBytes() + padding;
                if (endOfStream) {
                    sendResponse(ctx, streamId, data.retain());
                }
                return processed;
            }

//            @Override
//            public void onHeadersRead(ChannelHandlerContext ctx, int streamId,
//                                      Http2Headers headers, int padding, boolean endOfStream) throws Http2Exception {
//
//                HttpRequest v1Request = toHttpRequest(streamId, headers, true);
//
//                makeAStyxRequestFrom(v1Request, content);
//
//                if (endOfStreaxm) {
//                    ByteBuf content = ctx.alloc().buffer();
//                    content.writeBytes(RESPONSE_BYTES.duplicate());
//                    ByteBufUtil.writeAscii(content, " - via HTTP/2");
//                    sendResponse(ctx, streamId, content);
//                }
//            }

            @Override
            public void onHeadersRead(ChannelHandlerContext ctx, int streamId,
                                      Http2Headers headers, int padding, boolean endOfStream) {
                if (endOfStream) {
                    ByteBuf content = ctx.alloc().buffer();
                    content.writeBytes(RESPONSE_BYTES.duplicate());
                    ByteBufUtil.writeAscii(content, " - via HTTP/2");
                    sendResponse(ctx, streamId, content);
                }
            }

            @Override
            public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency,
                                      short weight, boolean exclusive, int padding, boolean endOfStream) {
                onHeadersRead(ctx, streamId, headers, padding, endOfStream);
            }

            @Override
            public void onPriorityRead(ChannelHandlerContext channelHandlerContext, int i, int i1, short i2, boolean b) throws Http2Exception {

            }

            @Override
            public void onRstStreamRead(ChannelHandlerContext channelHandlerContext, int i, long l) throws Http2Exception {

            }

            @Override
            public void onSettingsAckRead(ChannelHandlerContext channelHandlerContext) throws Http2Exception {

            }

            @Override
            public void onSettingsRead(ChannelHandlerContext channelHandlerContext, Http2Settings http2Settings) throws Http2Exception {

            }

            @Override
            public void onPingRead(ChannelHandlerContext channelHandlerContext, long l) throws Http2Exception {

            }

            @Override
            public void onPingAckRead(ChannelHandlerContext channelHandlerContext, long l) throws Http2Exception {

            }

            @Override
            public void onPushPromiseRead(ChannelHandlerContext channelHandlerContext, int i, int i1, Http2Headers http2Headers, int i2) throws Http2Exception {

            }

            @Override
            public void onGoAwayRead(ChannelHandlerContext channelHandlerContext, int i, long l, ByteBuf byteBuf) throws Http2Exception {

            }

            @Override
            public void onWindowUpdateRead(ChannelHandlerContext channelHandlerContext, int i, int i1) throws Http2Exception {

            }

            @Override
            public void onUnknownFrame(ChannelHandlerContext channelHandlerContext, byte b, int i, Http2Flags http2Flags, ByteBuf byteBuf) throws Http2Exception {

            }
        }

        private static class IdleTransactionConnectionCloser extends ChannelDuplexHandler {
            private static final Logger LOGGER = getLogger(IdleTransactionConnectionCloser.class);
            private final Histogram idleConnectionClosed;
            private final MetricRegistry metricRegistry;

            private volatile boolean httpTransactionOngoing;

            IdleTransactionConnectionCloser(MetricRegistry metricRegistry) {
                this.metricRegistry = metricRegistry.scope("connections");
                this.idleConnectionClosed = this.metricRegistry.histogram("idleClosed");
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof io.netty.handler.codec.http.HttpRequest) {
                    httpTransactionOngoing = true;
                }
                super.channelRead(ctx, msg);
            }

            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                if (msg instanceof LastHttpContent) {
                    httpTransactionOngoing = false;
                }
                super.write(ctx, msg, promise);
            }

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (evt instanceof IdleStateEvent) {
                    IdleStateEvent e = (IdleStateEvent) evt;
                    if (e.state() == ALL_IDLE && !httpTransactionOngoing) {
                        if (ctx.channel().isActive()) {
                            LOGGER.warn("Closing an idle connection={}", ctx.channel().remoteAddress());
                            ctx.close();
                            idleConnectionClosed.update(1);
                        }
                    }
                }
            }
        }
    }
}

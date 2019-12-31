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
package com.hotels.styx.servers

import com.fasterxml.jackson.databind.JsonNode
import com.hotels.styx.IStyxServer
import com.hotels.styx.Proxy2ConnectorFactory
import com.hotels.styx.ResponseInfoFormat
import com.hotels.styx.ServerExecutor
import com.hotels.styx.StyxObjectRecord
import com.hotels.styx.api.HttpHandler
import com.hotels.styx.config.schema.SchemaDsl.`object`
import com.hotels.styx.config.schema.SchemaDsl.field
import com.hotels.styx.config.schema.SchemaDsl.integer
import com.hotels.styx.config.schema.SchemaDsl.list
import com.hotels.styx.config.schema.SchemaDsl.optional
import com.hotels.styx.config.schema.SchemaDsl.string
import com.hotels.styx.infrastructure.configuration.yaml.JsonNodeConfig
import com.hotels.styx.proxy.encoders.ConfigurableUnwiseCharsEncoder.ENCODE_UNWISECHARS
import com.hotels.styx.routing.config.RoutingObjectFactory
import com.hotels.styx.routing.config.StyxObjectReference
import com.hotels.styx.routing.db.StyxObjectStore
import com.hotels.styx.server.netty.NettyServerBuilder
import com.hotels.styx.server.netty.connectors.ResponseEnhancer
import com.hotels.styx.serviceproviders.StyxServerFactory
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol.ALPN
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE
import io.netty.handler.ssl.ApplicationProtocolNames.HTTP_1_1
import io.netty.handler.ssl.ApplicationProtocolNames.HTTP_2
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import io.netty.handler.ssl.SupportedCipherSuiteFilter
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

object StyxHttp2Server {
    @JvmField
    val SCHEMA = `object`(
            field("port", integer()),
            field("handler", string()),

            field("tlsSettings", `object`(
                    optional("sslProvider", string()),
                    optional("certificateFile", string()),
                    optional("certificateKeyFile", string()),
                    optional("sessionTimeoutMillis", integer()),
                    optional("sessionCacheSize", integer()),
                    optional("cipherSuites", list(string())),
                    optional("protocols", list(string()))
            ))

//                optional("maxInitialLength", integer()),
//                optional("maxHeaderSize", integer()),
//                optional("maxChunkSize", integer()),
//
//                optional("requestTimeoutMillis", integer()),
//                optional("keepAliveTimeoutMillis", integer()),
//                optional("maxConnectionsCount", integer()),
//
//                optional("bossThreadsCount", integer()),
//                optional("workerThreadsCount", integer())
    )

    internal val LOGGER = LoggerFactory.getLogger(StyxHttpServer::class.java)

    internal fun deserialise(config: JsonNode): StyxHttp2ServerConfiguration = JsonNodeConfig(config).`as`(StyxHttp2ServerConfiguration::class.java)
}

data class StyxHttpServerTlsSettings(
        val certificateFile: String,
        val certificateKeyFile: String,
        val sessionTimeoutMillis: Long,
        val sessionCacheSize: Long,
        val cipherSuites: List<String> = listOf(),
        val protocols: List<String> = listOf(),
        val sslProvider: String = "JDK"
)

data class StyxHttp2ServerConfiguration(
        val port: Int,
        val handler: String,
        val tlsSettings: StyxHttpServerTlsSettings

//        val maxInitialLength: Int?,
//        val maxHeaderSize: Int?,
//        val maxChunkSize: Int?,
//
//        val requestTimeoutMillis: Int?,
//        val keepAliveTimeoutMillis: Int?,
//        val maxConnectionsCount: Int?,
//
//        val bossThreadsCount: Int?,
//        val workerThreadsCount: Int?
)

private fun toProtocolsOrDefault(elems: List<String>): Array<String>? {
    return if (elems.isNotEmpty()) elems.toTypedArray() else null
}

private fun sslContextFromConfiguration(config: StyxHttpServerTlsSettings): SslContextBuilder {
    val builder = SslContextBuilder
            .forServer(File(config.certificateFile), File(config.certificateKeyFile))
            .sslProvider(SslProvider.valueOf(config.sslProvider))
            .ciphers(if (config.cipherSuites.isNotEmpty()) config.cipherSuites else null, SupportedCipherSuiteFilter.INSTANCE)
//            .sessionTimeout(TimeUnit.MILLISECONDS.toSeconds(config.sessionTimeoutMillis))
//            .sessionCacheSize(config.sessionCacheSize)

//    val protocols = toProtocolsOrDefault(config.protocols)
//    if (protocols == null) {
//        builder.protocols(null)
//    } else {
//        builder.protocols(*protocols)
//    }

    return builder
}


class StyxHttp2ServerFactory : StyxServerFactory {

    override fun create(name: String, context: RoutingObjectFactory.Context, configuration: JsonNode, serverDb: StyxObjectStore<StyxObjectRecord<IStyxServer>>): IStyxServer {
        val config = StyxHttp2Server.deserialise(configuration)

        val handlerName = StyxObjectReference(config.handler)

        val handler = HttpHandler { request, ctx ->
            context.refLookup()
                    .apply(handlerName)
                    .handle(request, ctx)
        }
        val environment = context.environment()

        val styxInfoHeaderName = environment.configuration().styxHeaderConfig().styxInfoHeaderName()
        val responseInfoFormat = ResponseInfoFormat(environment)

        val serviceName = "Http-Server(localhost-${config.port})"

        val sslContext = sslContextFromConfiguration(config.tlsSettings)
                .applicationProtocolConfig(ApplicationProtocolConfig(
                        ALPN,
                        NO_ADVERTISE,
                        ACCEPT,
                        HTTP_2,
                        HTTP_1_1))
                .build()

        val server = NettyServerBuilder()
                .setMetricsRegistry(environment.metricRegistry())
                .setProtocolConnector(
                        Proxy2ConnectorFactory(
                                environment.configuration().proxyServerConfig(),
                                environment.metricRegistry(),
                                environment.errorListener(),
                                environment.configuration().get(ENCODE_UNWISECHARS).orElse(""),
                                ResponseEnhancer { builder, request -> builder.header(styxInfoHeaderName, responseInfoFormat.format(request)) },
                                false)
                                .create(config.port, sslContext))
                .workerExecutor(ServerExecutor.create(serviceName, 0))
                .handler(handler)
                .build();

        return server
    }
}
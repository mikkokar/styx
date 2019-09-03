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
package com.hotels.styx.routing.handlers

import com.hotels.styx.Environment
import com.hotels.styx.api.HeaderKey
import com.hotels.styx.api.HttpResponseStatus.OK
import com.hotels.styx.api.Id.id
import com.hotels.styx.api.LiveHttpRequest
import com.hotels.styx.api.LiveHttpResponse
import com.hotels.styx.client.BackendServiceClient
import com.hotels.styx.proxy.BackendServiceClientFactory
import com.hotels.styx.routing.RoutingObjectFactoryContext
import com.hotels.styx.routing.routeLookup
import com.hotels.styx.routing.routingObjectDef
import com.hotels.styx.server.HttpInterceptorContext
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono

class ProxyToBackendTest : StringSpec({
    val environment = Environment.Builder().build()

    val config = routingObjectDef("""
              name: ProxyToBackend
              type: ProxyToBackend
              config:
                backend:
                  id: "ba"
                  connectionPool:
                    maxConnectionsPerHost: 45
                    maxPendingConnectionsPerHost: 15
                  responseTimeoutMillis: 60000
                  origins:
                  - { id: "ba1", host: "localhost:9094" }

          """.trimIndent())

    val context = RoutingObjectFactoryContext(
            environment = environment,
            routeRefLookup = routeLookup { }
    ).get()

    "builds ProxyToBackend handler" {
        val handler = ProxyToBackend.Factory.build(listOf(), context, config, clientFactory());

        handler.handle(LiveHttpRequest.get("/foo").build(), HttpInterceptorContext.create())
                .toMono()
                .block()!!
                .status() shouldBe (OK)
    }

    "throws for missing mandatory 'backend' attribute" {
        val config = routingObjectDef("""
                    name: myProxy
                    type: ProxyToBackend
                    config:
                      na: na
                """.trimIndent())

        val e = shouldThrow<IllegalArgumentException> {
            ProxyToBackend.Factory.build(listOf("config", "config"), context, config, clientFactory())
        }

        e.message shouldBe ("Routing object definition of type 'ProxyToBackend', attribute='config.config', is missing a mandatory 'backend' attribute.")
    }

    "throws for a missing mandatory backend.origins attribute" {
        val config = routingObjectDef("""
                    name: ProxyToBackend
                    type: ProxyToBackend
                    config:
                      backend:
                        id: "ba"
                        connectionPool:
                          maxConnectionsPerHost: 45
                          maxPendingConnectionsPerHost: 15
                        responseTimeoutMillis: 60000
                """.trimIndent())

        val e = shouldThrow<IllegalArgumentException> {
            ProxyToBackend.Factory.build(listOf("config", "config"), context, config, clientFactory())
        }

        e.message shouldBe ("Routing object definition of type 'ProxyToBackend', attribute='config.config.backend', is missing a mandatory 'origins' attribute.")
    }
})


private fun clientFactory() = BackendServiceClientFactory { backendService, originsInventory, originStatsFactory ->
    BackendServiceClient { request ->
        backendService.id() shouldBe (id("ba"))
        backendService.connectionPoolConfig().maxConnectionsPerHost() shouldBe (45)
        backendService.connectionPoolConfig().maxPendingConnectionsPerHost() shouldBe (15)
        backendService.responseTimeoutMillis() shouldBe (60000)
        backendService.origins().first()!!.id() shouldBe (id("ba1"))
        backendService.origins().first()!!.port() shouldBe (9094)
        Mono.just(LiveHttpResponse.response(OK)
                .addHeader(HeaderKey.headerKey("X-Backend-Service"), "y")
                .build())

    }
}

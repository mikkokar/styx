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

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.{MappingBuilder, WireMock}
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.hotels.styx.Environment
import com.hotels.styx.api.{HttpResponse, LiveHttpRequest}
import com.hotels.styx.infrastructure.configuration.yaml.YamlConfig
import com.hotels.styx.routing.config.RoutingObjectDefinition
import com.hotels.styx.routing.handlers.HostProxy.Factory
import com.hotels.styx.server.HttpInterceptorContext
import com.hotels.styx.support.server.UrlMatchingStrategies.urlStartingWith
import org.scalatest._
import org.scalatest.mock.MockitoSugar
import reactor.core.publisher.Mono

import scala.collection.JavaConverters._

class HostProxySpec extends FunSpec with Matchers with BeforeAndAfterAll with MockitoSugar {
  val hwaRequest = LiveHttpRequest.get("/x").build()
  val laRequest = LiveHttpRequest.get("/lp/x").build()
  val baRequest = LiveHttpRequest.get("/ba/x").build()

  val environment = new Environment.Builder().build()

  val server = new WireMockServer(wireMockConfig.dynamicPort.dynamicHttpsPort)
  server.start()
  server.stubFor(WireMock.get(urlStartingWith("/")).willReturn(aResponse.withStatus(201)))

  it("builds a host proxy from the configuration ") {
    val config = configBlock(
      s"""
        |config:
        |  type: HostProxy
        |  config:
        |    host: localhost:${server.port()}
      """.stripMargin)

    val handler = new Factory().build(List("bar").asJava, null, null, config)
    val response: HttpResponse = Mono.from(handler.handle(LiveHttpRequest.get("/").build(), HttpInterceptorContext.create())
        .flatMap(response => response.aggregate(10000)))
        .block()

    println("response: " + response)
    response.status.code should be (201)
  }

  override protected def afterAll(): Unit = {
    server.stop()
    super.afterAll()
  }

  private def configBlock(text: String) = new YamlConfig(text).get("config", classOf[RoutingObjectDefinition]).get()

}

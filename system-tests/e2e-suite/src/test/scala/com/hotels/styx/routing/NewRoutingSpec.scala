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
package com.hotels.styx.routing

import java.nio.charset.StandardCharsets.UTF_8

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.client.{ValueMatchingStrategy, WireMock}
import com.hotels.styx.api.HttpRequest.get
import com.hotels.styx.api.HttpResponseStatus._
import com.hotels.styx.api.extension
import com.hotels.styx.infrastructure.{MemoryBackedRegistry}
import com.hotels.styx.support.ResourcePaths.fixturesHome
import com.hotels.styx.support.backends.FakeHttpServer
import com.hotels.styx.support.configuration._
import com.hotels.styx.utils.StubOriginHeader.STUB_ORIGIN_INFO
import com.hotels.styx.{BackendServicesRegistrySupplier, StyxClientSupplier, StyxProxySpec}
import org.scalatest.{FunSpec, SequentialNestedSuiteExecution}

import scala.concurrent.duration._

class NewRoutingSpec extends FunSpec
  with StyxProxySpec
  with StyxClientSupplier
  with SequentialNestedSuiteExecution
  with BackendServicesRegistrySupplier {

  val logback = fixturesHome(this.getClass, "/conf/logback/logback-debug-stdout.xml")
  val httpBackendRegistry = new MemoryBackedRegistry[extension.service.BackendService]()
  val httpsBackendRegistry = new MemoryBackedRegistry[extension.service.BackendService]()
  val crtFile = fixturesHome(this.getClass, "/ssl/testCredentials.crt").toString
  val keyFile = fixturesHome(this.getClass, "/ssl/testCredentials.key").toString

  val httpServer01 = FakeHttpServer.HttpStartupConfig(
    appId = "app",
    originId = "app-01")
    .start()
    .stub(WireMock.get(urlMatching("/.*")), originResponse("http-app-01"))

  val httpServer02 = FakeHttpServer.HttpStartupConfig(
    appId = "app",
    originId = "app-02")
    .start()
    .stub(WireMock.get(urlMatching("/.*")), originResponse("http-app-02"))

  override val styxConfig = StyxConfig(
    ProxyConfig(),
    logbackXmlLocation = logback,
    yamlText =
      s"""
        |httpHandlers:
        |  landing-01:
        |    type: HostProxy
        |    config:
        |      tag: "landing-app"
        |      host: "localhost:${httpServer01.port()}"
        |
        |  landing-02:
        |    type: HostProxy
        |    config:
        |      tag: "landing-app"
        |      host: "localhost:${httpServer02.port()}"
        |
        |httpPipeline:
        |  type: BackendApplication
        |  config:
        |    origins: "landing-app"
        |    id: "MyLandingApp"
      """.stripMargin
  )

  println("httpOrigin-01: " + httpServer01.port())
  println("httpOrigin-02: " + httpServer02.port())

  override protected def afterAll(): Unit = {
    httpServer01.stop()
    httpServer02.stop()
    super.afterAll()
  }

  def httpRequest(path: String) = get(styxServer.routerURL(path)).build()

  def httpsRequest(path: String) = get(styxServer.secureRouterURL(path)).build()

  def valueMatchingStrategy(matches: String) = {
    val matchingStrategy = new ValueMatchingStrategy()
    matchingStrategy.setMatches(matches)
    matchingStrategy
  }

  describe("Styx routing of HTTP requests") {
    it("Routes HTTP protocol to HTTP origins") {
      val response = decodedRequest(httpRequest("/app.1"))

      assert(response.status() == OK)
      assert(response.bodyAs(UTF_8) == "Hello, World!")

      httpServer02.verify(
        getRequestedFor(urlEqualTo("/app.1"))
          .withHeader("X-Forwarded-Proto", valueMatchingStrategy("http")))
    }
  }

  def originResponse(appId: String) = aResponse
    .withStatus(OK.code())
    .withHeader(STUB_ORIGIN_INFO.toString, appId)
    .withBody("Hello, World!")

}

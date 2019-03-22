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
package com.hotels.styx.routing

import java.nio.charset.StandardCharsets.UTF_8

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.client.{ValueMatchingStrategy, WireMock}
import com.hotels.styx.api.HttpRequest.get
import com.hotels.styx.api.HttpResponseStatus._
import com.hotels.styx.api.{HttpRequest, HttpResponse, extension}
import com.hotels.styx.infrastructure.MemoryBackedRegistry
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

  val httpBackendRegistry = new MemoryBackedRegistry[extension.service.BackendService]()
  val httpsBackendRegistry = new MemoryBackedRegistry[extension.service.BackendService]()

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

  val httpServer03 = FakeHttpServer.HttpStartupConfig(
    appId = "app",
    originId = "app-03")
    .start()
    .stub(WireMock.get(urlMatching("/.*")), originResponse("http-app-03"))

  override val styxConfig = StyxConfig(
    ProxyConfig(),
    yamlText =
      s"""
        |services:
        |  factories:
        |    landingAppMonitor:
        |      class: "com.hotels.styx.proxy.healthchecks.HealthCheckMonitoringServiceFactory"
        |      config:
        |        application: landing-app
        |        monitor:
        |          url: "/version.txt"
        |
        |httpHandlers:
        |  landing-01:
        |    type: HostProxy
        |    tags:
        |      - landing-app
        |      - status=inactive
        |    config:
        |      host: "localhost:${httpServer01.port()}"
        |
        |  landing-02:
        |    type: HostProxy
        |    tags:
        |      - landing-app
        |      - status=active
        |    config:
        |      host: "localhost:${httpServer02.port()}"
        |
        |  landingApp:
        |    type: BackendApplication
        |    config:
        |      origins: "landing-app"
        |      id: "MyLandingApp"
        |
        |  shoppingApp:
        |    type: BackendApplication
        |    config:
        |      origins: "shopping-app"
        |      id: "MyShoppingApp"
        |
        |httpPipeline:
        |  type: PathPrefixRouter
        |  config:
        |    routes:
        |      - {prefix: /, destination: landingApp }
        |      - {prefix: /hopping, destination: shoppingApp }
      """.stripMargin
  )

  println("httpOrigin-01: " + httpServer01.port())
  println("httpOrigin-02: " + httpServer02.port())
  println("httpOrigin-03: " + httpServer03.port())

  override protected def afterAll(): Unit = {
    httpServer01.stop()
    httpServer02.stop()
    httpServer03.stop()
    super.afterAll()
  }

  def adminRequest(path: String) = get(styxServer.adminURL(path)).build()

  def adminPost(path: String, body: String) = HttpRequest.post(styxServer.adminURL(path)).body(body, UTF_8).build()

  def httpRequest(path: String) = get(styxServer.routerURL(path)).build()

  def httpsRequest(path: String) = get(styxServer.secureRouterURL(path)).build()

  def valueMatchingStrategy(matches: String) = {
    val matchingStrategy = new ValueMatchingStrategy()
    matchingStrategy.setMatches(matches)
    matchingStrategy
  }

  def displayResponse(info: String, response: HttpResponse): String = s"$info: - ${response.status()}: ${response.bodyAs(UTF_8)}"

  describe("Styx routing of HTTP requests") {
    it("Exposes routing configuration via admin interface") {
      val response = decodedRequest(adminRequest("/admin/routing/objects"))
      println("Admin interface response: ")
      println(response.bodyAs(UTF_8))
    }

    it("Adds new objects via admin interface") {
      println(displayResponse("Response from HTTP port", decodedRequest(httpRequest("/app.1"))))

      val addLanding3Json =
        s"""
        |{ "name": "landing-03",
        |  "type": "HostProxy",
        |  "tags": ["landing-app", "status=active"],
        |  "config": {
        |     "host": "localhost:${httpServer03.port()}"
        |  }
        |}""".stripMargin


      val inactivateLanding2Json =
        s"""
           |{ "name": "landing-02",
           |  "type": "HostProxy",
           |  "tags": ["landing-app", "status=inactive"],
           |  "config": {
           |     "host": "localhost:${httpServer02.port()}"
           |  }
           |}""".stripMargin

      val addLanding3 =
        s"""
           |---
           |name: landing-03
           |type: HostProxy
           |tags:
           | - landing-app
           | - status=active
           |config:
           |  host: "localhost:${httpServer03.port()}"
           |""".stripMargin

      val inactivateLanding2 =
        s"""
           |---
           |name: landing-02
           |type: HostProxy
           |tags:
           | - landing-app
           | - status=inactive
           |config:
           |  host: "localhost:${httpServer02.port()}"
           |""".stripMargin


      println("landing app")
      println(addLanding3)
      println(displayResponse("New object", decodedRequest(adminPost("/admin/routing/objects", addLanding3))))

      println(displayResponse("Query objects", decodedRequest(adminPost("/admin/routing/objects", inactivateLanding2))))

      println(displayResponse("Check status", decodedRequest(adminRequest("/admin/routing/objects"))))

      println(displayResponse("Response from HTTP port", decodedRequest(httpRequest("/app.1"))))
    }

    it("Routes HTTP protocol to HTTP origins") {
      val response = decodedRequest(httpRequest("/app.1"))

      assert(response.status() == OK)
      assert(response.bodyAs(UTF_8) == "Hello, World!")
      println("From origin: " + response.header(STUB_ORIGIN_INFO))

//      httpServer02.verify(
//        getRequestedFor(urlEqualTo("/app.1"))
//          .withHeader("X-Forwarded-Proto", valueMatchingStrategy("http")))
    }
  }

  def originResponse(appId: String) = aResponse
    .withStatus(OK.code())
    .withHeader(STUB_ORIGIN_INFO.toString, appId)
    .withBody(s"Hello from $appId")

}

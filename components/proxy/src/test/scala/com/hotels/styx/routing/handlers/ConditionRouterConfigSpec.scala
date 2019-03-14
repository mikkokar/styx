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

import com.hotels.styx.api.HttpResponseStatus.{BAD_GATEWAY, OK}
import com.hotels.styx.api.LiveHttpResponse.response
import com.hotels.styx.api.{Eventual, HttpHandler, LiveHttpRequest}
import com.hotels.styx.infrastructure.configuration.yaml.YamlConfig
import com.hotels.styx.routing.HttpHandlerAdapter
import com.hotels.styx.routing.config._
import com.hotels.styx.routing.db.RouteDatabase
import com.hotels.styx.routing.handlers.StaticResponseHandler.Factory
import com.hotels.styx.server.HttpInterceptorContext
import org.mockito.Matchers.{any, eq => meq}
import org.mockito.Mockito.{verify, when}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSpec, Matchers}
import reactor.core.publisher.Mono

import scala.collection.JavaConversions._

class ConditionRouterConfigSpec extends FunSpec with Matchers with MockitoSugar {

  private val request = LiveHttpRequest.get("/foo").build
  private val routeHandlerFactory = new RoutingObjectFactory(Map("StaticResponseHandler" -> new Factory), new RouteDatabase())

  private val config = configBlock(
    """
      |config:
      |    name: main-router
      |    type: ConditionRouter
      |    config:
      |      routes:
      |        - condition: protocol() == "https"
      |          destination:
      |            name: proxy-and-log-to-https
      |            type: StaticResponseHandler
      |            config:
      |              status: 200
      |              content: "secure"
      |      fallback:
      |        name: proxy-to-http
      |        type: StaticResponseHandler
      |        config:
      |          status: 301
      |          content: "insecure"
      |""".stripMargin)

  val configWithReferences = configBlock(
    """
      |config:
      |    name: main-router
      |    type: ConditionRouter
      |    config:
      |      routes:
      |        - condition: protocol() == "https"
      |          destination: secureHandler
      |      fallback: fallbackHandler
      |""".stripMargin)


  it("Builds an instance with fallback handler") {
    val router = new ConditionRouter.Factory().build(List(), routeHandlerFactory, config)
    val response = Mono.from(router.handle(request, new HttpInterceptorContext(true))).block()

    response.status() should be(OK)
  }

  it("Builds condition router instance routes") {
    val router = new ConditionRouter.Factory().build(List(), routeHandlerFactory, config)
    val response = Mono.from(router.handle(request, new HttpInterceptorContext())).block()

    response.status().code() should be(301)
  }

  it("Fallback handler can be specified as a handler reference") {
    val routeDatabase = new RouteDatabase()
    routeDatabase.setHandler("secureHandler", new HttpHandlerAdapter((_, _) => Eventual.of(response(OK).header("source", "secure").build())))
    routeDatabase.setHandler("fallbackHandler", new HttpHandlerAdapter((_, _) => Eventual.of(response(OK).header("source", "fallback").build())))

    val routeHandlerFactory = new RoutingObjectFactory(Map[String, HttpHandlerFactory](), routeDatabase)

    val router = new ConditionRouter.Factory().build(List(), routeHandlerFactory, configWithReferences)

    val resp = Mono.from(router.handle(request, new HttpInterceptorContext())).block()

    resp.header("source").get() should be("fallback")
  }

  it("Route destination can be specified as a handler reference") {
    val routeDatabase = new RouteDatabase()

    val routeHandlerFactory = new RoutingObjectFactory(
      Map[String, HttpHandlerFactory](),
      new RouteDatabase(Map(
        "secureHandler" -> new HttpHandlerAdapter((_, _) => Eventual.of(response(OK).header("source", "secure").build())),
        "fallbackHandler" -> new HttpHandlerAdapter((_, _) => Eventual.of(response(OK).header("source", "fallback").build()))
      )))

    val router = new ConditionRouter.Factory().build(
      List(),
      routeHandlerFactory,
      configWithReferences
    )

    val resp = Mono.from(router.handle(request, new HttpInterceptorContext(true))).block()

    resp.header("source").get() should be("secure")
  }


  it("Throws exception when routes attribute is missing") {
    val config = configBlock(
      """
        |config:
        |    name: main-router
        |    type: ConditionRouter
        |    config:
        |      fallback:
        |        name: proxy-to-http
        |        type: StaticResponseHandler
        |        config:
        |          status: 301
        |          content: "insecure"
        |""".stripMargin)

    val e = intercept[IllegalArgumentException] {
      val router = new ConditionRouter.Factory().build(List("config", "config"), routeHandlerFactory, config)
    }
    e.getMessage should be("Routing object definition of type 'ConditionRouter', attribute='config.config', is missing a mandatory 'routes' attribute.")
  }

  it("Responds with 502 Bad Gateway when fallback attribute is not specified.") {
    val config = configBlock(
      """
        |config:
        |    name: main-router
        |    type: ConditionRouter
        |    config:
        |      routes:
        |        - condition: protocol() == "https"
        |          destination:
        |            name: proxy-and-log-to-https
        |            type: StaticResponseHandler
        |            config:
        |              status: 200
        |              content: "secure"
        |""".stripMargin)

    val router = new ConditionRouter.Factory().build(List(), routeHandlerFactory, config)

    val resp = Mono.from(router.handle(request, new HttpInterceptorContext())).block()

    resp.status() should be(BAD_GATEWAY)
  }

  it("Indicates the condition when fails to compile an DSL expression due to Syntax Error") {
    val config = configBlock(
      """
        |config:
        |    name: main-router
        |    type: ConditionRouter
        |    config:
        |      routes:
        |        - condition: )() == "https"
        |          destination:
        |            name: proxy-and-log-to-https
        |            type: StaticResponseHandler
        |            config:
        |              status: 200
        |              content: "secure"
        |""".stripMargin)

    val e = intercept[IllegalArgumentException] {
      val router = new ConditionRouter.Factory().build(List("config", "config"), routeHandlerFactory, config)
    }
    e.getMessage should be("Routing object definition of type 'ConditionRouter', attribute='config.config.routes.condition[0]', failed to compile routing expression condition=')() == \"https\"'")
  }

  it("Indicates the condition when fails to compile an DSL expression due to unrecognised DSL function name") {
    val config = configBlock(
      """
        |config:
        |    name: main-router
        |    type: ConditionRouter
        |    config:
        |      routes:
        |        - condition: nonexistant() == "https"
        |          destination:
        |            name: proxy-and-log-to-https
        |            type: StaticResponseHandler
        |            config:
        |              status: 200
        |              content: "secure"
        |""".stripMargin)

    val e = intercept[IllegalArgumentException] {
      val router = new ConditionRouter.Factory().build(List("config", "config"), routeHandlerFactory, config)
    }
    e.getMessage should be("Routing object definition of type 'ConditionRouter', attribute='config.config.routes.condition[0]', failed to compile routing expression condition='nonexistant() == \"https\"'")
  }

  it("Passes parentage attribute path to the builtins factory") {
    val config = configBlock(
      """
        |config:
        |    name: main-router
        |    type: ConditionRouter
        |    config:
        |      routes:
        |        - condition: protocol() == "https"
        |          destination:
        |            name: proxy-and-log-to-https
        |            type: StaticResponseHandler
        |            config:
        |              status: 200
        |              content: "secure"
        |        - condition: path() == "bar"
        |          destination:
        |            name: proxy-and-log-to-https
        |            type: StaticResponseHandler
        |            config:
        |              status: 200
        |              content: "secure"
        |      fallback:
        |        name: proxy-and-log-to-https
        |        type: StaticResponseHandler
        |        config:
        |          status: 200
        |          content: "secure"
        |""".stripMargin)

    val builtinsFactory = mock[RoutingObjectFactory]
    when(builtinsFactory.build(any[java.util.List[String]], any[RoutingObjectConfig]))
      .thenReturn(new HttpHandlerAdapter((_, _) => Eventual.of(response(OK).build())))

    val router = new ConditionRouter.Factory().build(List("config", "config"), builtinsFactory, config)

    verify(builtinsFactory).build(meq(List("config", "config", "routes", "destination[0]")), any[RoutingObjectConfig])
    verify(builtinsFactory).build(meq(List("config", "config", "routes", "destination[1]")), any[RoutingObjectConfig])
    verify(builtinsFactory).build(meq(List("config", "config", "fallback")), any[RoutingObjectConfig])
  }

  private def configBlock(text: String) = new YamlConfig(text).get("config", classOf[RoutingObjectDefinition]).get()

}

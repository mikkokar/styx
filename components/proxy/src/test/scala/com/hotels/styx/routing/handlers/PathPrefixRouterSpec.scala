package com.hotels.styx.routing.handlers

import java.nio.charset.StandardCharsets.UTF_8

import com.hotels.styx.api.HttpInterceptor.Context
import com.hotels.styx.api.HttpResponse.response
import com.hotels.styx.api.HttpResponseStatus.{ACCEPTED, BAD_GATEWAY, CONTINUE, OK}
import com.hotels.styx.api._
import com.hotels.styx.infrastructure.configuration.yaml.YamlConfig
import com.hotels.styx.routing.MapBackedRouteDatabase
import com.hotels.styx.routing.config.{HttpHandlerFactory, RoutingObjectDefinition, RoutingObjectFactory}
import com.hotels.styx.routing.handlers.StaticResponseHandler.Factory
import com.hotels.styx.server.HttpInterceptorContext
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSpec, Matchers}
import reactor.core.publisher.Mono

import scala.collection.JavaConverters._
import scala.compat.java8.FunctionConverters._



class PathPrefixRouterSpec  extends FunSpec with Matchers with MockitoSugar {
  private val request = LiveHttpRequest.get("/foo").build
  private val routeHandlerFactory = new RoutingObjectFactory(Map("StaticResponseHandler" -> (new Factory).asInstanceOf[HttpHandlerFactory]).asJava)

  private val routeDb = new MapBackedRouteDatabase(Map[String, HttpHandler](
    "root-app" -> new HttpHandlerAdapter((request, context) => Eventual.of(response(ACCEPTED).body("root", UTF_8).build().stream())),
    "shopping-app" -> new HttpHandlerAdapter((request, context) => Eventual.of(response(BAD_GATEWAY).body("shopping", UTF_8).build().stream())),
    "blah-app" -> new HttpHandlerAdapter((request, context) => Eventual.of(response(CONTINUE).body("blah", UTF_8).build().stream()))
  ))

  private val config = configBlock(
    """
      |config:
      |    name: main-router
      |    type: PathPrefixRouter
      |    config:
      |      routes:
      |        - { prefix: /, destination: root-app}
      |        - { prefix: /shopping, destination: shopping-app }
      |        - { prefix: /blah, destination: blah-app }
      |""".stripMargin)


  it("Builds an instance with fallback handler") {
    val router = new PathPrefixRouter.Factory().build(List.empty.asJava, routeDb, routeHandlerFactory, config)

    val response = Mono.from(router.handle(request, new HttpInterceptorContext(true))).block()
    response.status() should be(ACCEPTED)

    val response2 = Mono.from(router.handle(request.newBuilder().uri("/shopping").build(), new HttpInterceptorContext(true))).block()
    response2.status() should be(BAD_GATEWAY)
  }

  private def configBlock(text: String) = new YamlConfig(text).get("config", classOf[RoutingObjectDefinition]).get()


  class HttpHandlerAdapter(handler: (LiveHttpRequest, Context) => Eventual[LiveHttpResponse]) extends HttpHandler {
    override def handle(request: LiveHttpRequest, context: Context): Eventual[LiveHttpResponse] = handler(request, context)
  }


}

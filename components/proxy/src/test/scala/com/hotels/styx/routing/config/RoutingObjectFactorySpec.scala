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
package com.hotels.styx.routing.config

import com.fasterxml.jackson.databind.JsonNode
import com.hotels.styx.api.HttpHandler
import com.hotels.styx.api.configuration.RouteDatabase
import com.hotels.styx.infrastructure.configuration.yaml.YamlConfig
import com.hotels.styx.routing.MapBackedRouteDatabase
import org.mockito.Matchers.any
import org.mockito.Mockito.{verify, when}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSpec, Matchers}

import scala.collection.JavaConverters._

class RoutingObjectFactorySpec extends FunSpec with Matchers with MockitoSugar {

  private val mockHandler = mock[HttpHandler]
  private val aHandlerInstance = mock[HttpHandler]

  val routeDb: RouteDatabase = new MapBackedRouteDatabase(Map("aHandler" -> aHandlerInstance))

  it ("Builds a new handler as per RoutingObjectDefinition") {
    val routeDef = new RoutingObjectDefinition("handler-def", "DelegateHandler", mock[JsonNode])
    val handlerFactory = httpHandlerFactory()

    val routeFactory = new RoutingObjectFactory(Map("DelegateHandler" -> handlerFactory).asJava)

    val delegateHandler = routeFactory.build(List("parents").asJava, routeDb, routeDef)

    (delegateHandler != null) should be (true)
    verify(handlerFactory).build(List("parents").asJava, routeDb, routeFactory, routeDef)
  }

  it ("Doesn't accept unregistered types") {
    val config = new RoutingObjectDefinition("foo", "ConfigType", mock[JsonNode])
    val routeFactory = new RoutingObjectFactory(Map.empty[String, HttpHandlerFactory].asJava)

    val e = intercept[IllegalArgumentException] {
      routeFactory.build(List().asJava, routeDb, config)
    }

    e.getMessage should be ("Unknown handler type 'ConfigType'")
  }

  it ("Returns handler from a configuration reference") {
    val routeFactory = new RoutingObjectFactory(Map.empty[String, HttpHandlerFactory].asJava)

    val handler = routeFactory.build(List().asJava, routeDb, new RoutingObjectReference("aHandler"))

    handler should be (aHandlerInstance)
  }

  it ("Throws exception when it refers a non-existent object") {
    val routeFactory = new RoutingObjectFactory(Map.empty[String, HttpHandlerFactory].asJava)

    val e = intercept[IllegalArgumentException] {
      routeFactory.build(List().asJava, routeDb, new RoutingObjectReference("non-existent"))
    }

    e.getMessage should be("Non-existent handler instance: 'non-existent'")
  }

  private def httpHandlerFactory(): HttpHandlerFactory = {
    val mockFactory: HttpHandlerFactory = mock[HttpHandlerFactory]
    when(mockFactory.build(any[java.util.List[String]], any[RouteDatabase], any[RoutingObjectFactory], any[RoutingObjectDefinition])).thenReturn(mockHandler)
    mockFactory
  }

  private def yamlConfig(text: String) = new YamlConfig(text)

}

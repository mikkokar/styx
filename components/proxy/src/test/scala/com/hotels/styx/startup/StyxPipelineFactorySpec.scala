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
package com.hotels.styx.startup

import com.fasterxml.jackson.databind.JsonNode
import com.hotels.styx.Environment
import com.hotels.styx.api.extension.service.spi.StyxService
import com.hotels.styx.infrastructure.configuration.yaml.YamlConfig
import org.scalatest.{FunSpec, Matchers}

import scala.collection.JavaConverters._

class StyxPipelineFactorySpec extends FunSpec with Matchers {

  val jsonNode = jsonNodeFromConfig(
    """
      |httpHandlers:
      |
      |  firstResponse:
      |    type: StaticResponseHandler
      |    config:
      |      status: 200
      |      content: "Hello, world!"
      |
      |  secondResponse:
      |    type: StaticResponseHandler
      |    config:
      |      status: 200
      |      content: "Hello, again!"
      |
        """.stripMargin)

//  it ("Builds a new handler as per RouteHandlerDefinition") {
//    val map = StyxPipelineFactory.readHttpHandlers(jsonNode, new Environment.Builder().build(), Map[String, StyxService]().asJava, List.empty.asJava)
//    println("map: " + map)
//  }

  private def jsonNodeFromConfig(text: String) = new YamlConfig(text).get("httpHandlers", classOf[JsonNode]).get()

}

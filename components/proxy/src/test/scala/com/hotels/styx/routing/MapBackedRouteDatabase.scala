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

import java.util
import java.util.Optional

import com.hotels.styx.api.HttpHandler
import com.hotels.styx.api.configuration.RouteDatabase

import scala.compat.java8.OptionConverters.toJava

class MapBackedRouteDatabase(map: Map[String, HttpHandler]) extends RouteDatabase {
  /**
    * Styx route database lookup.
    *
    * @param key
    * @return
    */
  override def handler(key: String): Optional[HttpHandler] = toJava(map.get(key))

  override def remove(key: String): Unit = ???

  override def handlers(tags: String*): util.Set[HttpHandler] = ???

  override def addListener(listener: RouteDatabase.Listener): Unit = ???

  override def removeListener(listener: RouteDatabase.Listener): Unit = ???

  override def lookup(key: String): Optional[RouteDatabase.Record] = ???

  override def tagLookup(tags: String*): util.Set[RouteDatabase.Record] = ???

  override def replaceTag(key: String, oldTag: String, newTag: String): Unit = ???
}

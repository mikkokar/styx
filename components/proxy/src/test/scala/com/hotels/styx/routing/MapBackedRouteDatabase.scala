package com.hotels.styx.routing

import java.util.Optional

import com.hotels.styx.api.HttpHandler
import com.hotels.styx.routing.db.RouteDatabase

import scala.compat.java8.OptionConverters.toJava

class MapBackedRouteDatabase(map: Map[String, HttpHandler]) extends RouteDatabase {
  /**
    * Styx route database lookup.
    *
    * @param key
    * @return
    */
  override def handler(key: String): Optional[HttpHandler] = toJava(map.get(key))
}

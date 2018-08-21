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
package com.hotels.styx.support

import com.hotels.styx.api.extension.loadbalancing.spi.{LoadBalancingMetric, LoadBalancingMetricSupplier}
import com.hotels.styx.api._
import com.hotels.styx.api.extension.{ActiveOrigins, Origin, RemoteHost}
import com.hotels.styx.api.extension.service.BackendService
import com.hotels.styx.api.metrics.codahale.CodaHaleMetricRegistry
import com.hotels.styx.client.StyxHostHttpClient
import com.hotels.styx.client.connectionpool.ConnectionPool
import com.hotels.styx.api.StyxInternalObservables.fromRxObservable
import com.hotels.styx.client.connectionpool.ConnectionPools.simplePoolFactory
import java.lang

import scala.collection.JavaConverters._

object ActiveOriginsProvider {

  def activeOrigins(backendService: BackendService) = new ActiveOrigins {
    private def clientHandler(client: StyxHostHttpClient) = new HttpHandler {
      override def handle(request: HttpRequest, context: HttpInterceptor.Context): StyxObservable[HttpResponse] = {
        fromRxObservable(client.sendRequest(request))
      }
    }

    private def remoteHostClient(backendService: BackendService, origin: Origin, pool: ConnectionPool) =
      StyxHostHttpClient.create(origin.applicationId(), origin.id(), "hey ho", pool)

    private def newRemoteHost(backendService: BackendService, origin: Origin) = {
      val pool = simplePoolFactory(backendService, new CodaHaleMetricRegistry).create(origin)

      val lbMetricSupplier = new LoadBalancingMetricSupplier {
        override def loadBalancingMetric(): LoadBalancingMetric = new LoadBalancingMetric(pool.stats().busyConnectionCount())
      }

      RemoteHost.remoteHost(origin, clientHandler(remoteHostClient(backendService, origin, pool)), lbMetricSupplier)
    }

    import collection.JavaConverters._

    override def snapshot(): lang.Iterable[RemoteHost] =
      backendService.origins().asScala
        .map(origin => newRemoteHost(backendService, origin))
        .toList
        .asJava
  }

}

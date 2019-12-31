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
package com.hotels.styx.servers

import com.hotels.styx.IStyxServer
import com.hotels.styx.StyxObjectRecord
import com.hotels.styx.routing.db.StyxObjectStore
import com.hotels.styx.routing.routingObjectDef
import io.kotlintest.specs.FeatureSpec

class StyxHttp2ServerTest : FeatureSpec({

    val serverDb = StyxObjectStore<StyxObjectRecord<IStyxServer>>()
    val config = routingObjectDef("""
                  type: Http2Server
                  config:
                    port: 8443
                    handler: foo
                    tlsSettings:
                      sslProvider:           OPENSSL
                      certificateFile:       /conf/tls/testCredentials.crt
                      certificateKeyFile:    /conf/tls/testCredentials.key
                      sessionTimeoutMillis:  300000
                      sessionCacheSize:      20000
              """.trimIndent())

    println("here: ${StyxHttp2Server.deserialise(config.config())}")

})
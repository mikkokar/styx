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
package com.hotels.styx.startup;

import com.google.common.collect.ImmutableMap;
import com.hotels.styx.Environment;
import com.hotels.styx.api.extension.service.BackendService;
import com.hotels.styx.api.extension.service.spi.Registry;
import com.hotels.styx.api.extension.service.spi.StyxService;
import com.hotels.styx.proxy.StyxBackendServiceClientFactory;
import com.hotels.styx.proxy.plugin.NamedPlugin;
import com.hotels.styx.routing.config.BuiltinInterceptorsFactory;
import com.hotels.styx.routing.config.HttpHandlerFactory;
import com.hotels.styx.routing.handlers.BackendServiceProxy;
import com.hotels.styx.routing.handlers.ConditionRouter;
import com.hotels.styx.routing.handlers.HttpInterceptorPipeline;
import com.hotels.styx.routing.handlers.ProxyToBackend;
import com.hotels.styx.routing.handlers.StaticResponseHandler;

import java.util.Map;

import static java.util.stream.Collectors.toMap;

class BuiltInRoutingObjects {
    static ImmutableMap<String, HttpHandlerFactory> createBuiltinRoutingObjectFactories(
            Environment environment,
            Map<String, StyxService> services,
            Iterable<NamedPlugin> plugins,
            BuiltinInterceptorsFactory builtinInterceptorsFactory,
            boolean requestTracking) {
        return ImmutableMap.of(
                "StaticResponseHandler", new StaticResponseHandler.Factory(),
                "ConditionRouter", new ConditionRouter.Factory(),
                "BackendServiceProxy", new BackendServiceProxy.Factory(environment, backendRegistries(services)),
                "InterceptorPipeline", new HttpInterceptorPipeline.Factory(plugins, builtinInterceptorsFactory, requestTracking),
                "ProxyToBackend", new ProxyToBackend.Factory(environment, new StyxBackendServiceClientFactory(environment))
        );
    }

    private static Map<String, Registry<BackendService>> backendRegistries(Map<String, StyxService> servicesFromConfig) {
        return servicesFromConfig.entrySet()
                .stream()
                .filter(entry -> entry.getValue() instanceof Registry)
                .collect(toMap(Map.Entry::getKey, entry -> (Registry<BackendService>) entry.getValue()));
    }

}

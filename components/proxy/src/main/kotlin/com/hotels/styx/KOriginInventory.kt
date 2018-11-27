import com.hotels.styx.api.Id
import com.hotels.styx.api.extension.Origin
import com.hotels.styx.client.StyxHostHttpClient
import com.hotels.styx.client.connectionpool.SimpleConnectionPoolFactory
import com.hotels.styx.common.EventProcessor
import com.hotels.styx.proxy.ConfigStore

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

class KOriginInventory(
        val appId: Id,
        val configStore: ConfigStore,
        val hostConnectionPoolFactory: SimpleConnectionPoolFactory,
        val hostClientFactory: StyxHostHttpClient.Factory) : EventProcessor {

    // Todo:
    /*
    this.appId = requireNonNull(appId);
        this.configStore = requireNonNull(configStore);
        this.hostConnectionPoolFactory = requireNonNull(hostConnectionPoolFactory);
        this.hostClientFactory = requireNonNull(hostClientFactory);

        eventQueue = new QueueDrainingEventProcessor(this, true);

        configStore.origins().watch(appId.toString())
                .subscribe(origins -> origins.forEach(originId -> {
                    eventQueue.submit(new NewOriginEvent(originId));
                }));

     */

    override fun submit(event: Any?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

}

sealed class Event()

data class NewOriginEvent(val originId: Id): Event()

data class OriginUpdatedEvent(val origin: Origin): Event()

data class OriginTopicErrorEvent(val originId: Id, val cause: Throwable): Event()

data class OriginRemovedEvent(val originId: Id): Event()

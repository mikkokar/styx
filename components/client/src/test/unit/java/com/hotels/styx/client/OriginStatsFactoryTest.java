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
package com.hotels.styx.client;

import com.hotels.styx.api.extension.Origin;
import com.hotels.styx.api.metrics.codahale.CodaHaleMetricRegistry;
import com.hotels.styx.client.OriginStatsFactory.CachingOriginStatsFactory;
import com.hotels.styx.client.applications.OriginStats;
import org.testng.annotations.Test;

import static com.hotels.styx.api.extension.Origin.newOriginBuilder;
import static java.lang.String.format;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;

public class OriginStatsFactoryTest {

    final OriginStatsFactory originStatsFactory = new CachingOriginStatsFactory(new CodaHaleMetricRegistry());
    private Origin origin9090 = newOrigin(9090);
    private Origin origin9091 = newOrigin(9091);

    private static Origin newOrigin(int port) {
        return newOriginBuilder("localhost", port)
                .id(format("%s:%d", "localhost", port))
                .build();
    }

    @Test
    public void returnTheSameStatsForSameOrigin() {
        OriginStats originStatsOne = originStatsFactory.originStats(origin9090.applicationId(), origin9090.id());
        OriginStats originStatsTwo = originStatsFactory.originStats(origin9090.applicationId(), origin9090.id());
        assertThat(originStatsOne, sameInstance(originStatsTwo));
    }

    @Test
    public void createsANewOriginStatsForNewOrigins() {
        OriginStats originStatsOne = originStatsFactory.originStats(origin9090.applicationId(), origin9090.id());
        OriginStats originStatsTwo = originStatsFactory.originStats(origin9091.applicationId(), origin9091.id());
        assertThat(originStatsOne, not(sameInstance(originStatsTwo)));
    }
}
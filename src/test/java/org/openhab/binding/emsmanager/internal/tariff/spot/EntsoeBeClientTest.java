/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.emsmanager.internal.tariff.spot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Belgian day-ahead has been published in 15-minute units since October 2025.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class EntsoeBeClientTest {

    private static EntsoeBeClient client() {
        return new EntsoeBeClient(new org.eclipse.jetty.client.HttpClient(), "token", 0.0);
    }

    private static String document(String resolution, String... prices) {
        StringBuilder sb = new StringBuilder("<Publication_MarketDocument><TimeSeries><Period>"
                + "<timeInterval><start>2026-08-31T22:00Z</start><end>2026-09-01T22:00Z</end></timeInterval>"
                + "<resolution>" + resolution + "</resolution>");
        for (int i = 0; i < prices.length; i++) {
            sb.append("<Point><position>").append(i + 1).append("</position><price.amount>").append(prices[i])
                    .append("</price.amount></Point>");
        }
        return sb.append("</Period></TimeSeries></Publication_MarketDocument>").toString();
    }

    @Test
    void quarterHourPositionsAreAveragedIntoTheirHour() {
        HourlyPrices p = client().parse(
                document("PT15M", "100", "200", "300", "400", "50", "50", "50", "50").getBytes(StandardCharsets.UTF_8));

        assertNull(p.lastError());
        assertEquals(2, p.prices().size(), "eight quarters are two hours, not eight");
        Instant start = Instant.parse("2026-08-31T22:00:00Z");
        assertEquals(0.25, p.prices().get(start), 1e-9);
        assertEquals(0.05, p.prices().get(start.plusSeconds(3600)), 1e-9);
    }

    @Test
    void hourlyPositionsStillMapOneToOne() {
        HourlyPrices p = client().parse(document("PT60M", "100", "200").getBytes(StandardCharsets.UTF_8));

        Instant start = Instant.parse("2026-08-31T22:00:00Z");
        assertEquals(0.10, p.prices().get(start), 1e-9);
        assertEquals(0.20, p.prices().get(start.plusSeconds(3600)), 1e-9);
    }

    @Test
    void anUnreadableResolutionIsTakenAsHourly() {
        assertEquals(3600L, EntsoeBeClient.slotSeconds(null));
        assertEquals(3600L, EntsoeBeClient.slotSeconds("banana"));
        assertEquals(900L, EntsoeBeClient.slotSeconds("PT15M"));
    }
}

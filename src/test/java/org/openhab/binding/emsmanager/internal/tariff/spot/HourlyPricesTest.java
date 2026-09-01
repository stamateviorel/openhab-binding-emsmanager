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

import java.time.Instant;
import java.util.TreeMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * An hourly price is good for its hour and not a second longer.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class HourlyPricesTest {

    private static final Instant H0 = Instant.ofEpochSecond(1788249600L); // a whole hour

    private static HourlyPrices twoHours() {
        TreeMap<Instant, Double> m = new TreeMap<>();
        m.put(H0, 0.10);
        m.put(H0.plusSeconds(3600), 0.20);
        return new HourlyPrices(m, H0, null);
    }

    @Test
    void anInstantInsideAPublishedHourGetsThatHour() {
        assertEquals(0.10, twoHours().priceAt(H0.plusSeconds(1799)), 1e-9);
        assertEquals(0.20, twoHours().priceAt(H0.plusSeconds(3600)), 1e-9);
        assertEquals(0.20, twoHours().priceAt(H0.plusSeconds(7199)), 1e-9);
    }

    @Test
    void theLastPublishedHourDoesNotPriceTheRestOfTime() {
        // Before this, the 23:00 price silently priced every hour of the next day, and every day of
        // a feed outage, so the flat fallback could never take over.
        assertTrue(Double.isNaN(twoHours().priceAt(H0.plusSeconds(7200))));
        assertTrue(Double.isNaN(twoHours().priceAt(H0.plusSeconds(86400))));
        assertFalse(twoHours().covers(H0.plusSeconds(7200)));
        assertTrue(twoHours().covers(H0.plusSeconds(7199)));
    }

    @Test
    void beforeTheFirstHourThereIsNoPrice() {
        assertTrue(Double.isNaN(twoHours().priceAt(H0.minusSeconds(1))));
    }

    @Test
    void aScheduleShowsGapsAsNaNRatherThanRepeats() {
        double[] s = twoHours().schedule48h(H0);
        assertEquals(0.10, s[0], 1e-9);
        assertEquals(0.20, s[1], 1e-9);
        assertTrue(Double.isNaN(s[2]));
        assertTrue(Double.isNaN(s[47]));
    }
}

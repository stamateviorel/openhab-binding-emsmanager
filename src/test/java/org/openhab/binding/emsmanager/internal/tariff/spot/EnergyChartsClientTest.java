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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Parsing is checked against the shape the live API actually returns: quarter-hourly slots, which
 * have to become the hourly prices the rest of the binding reasons in.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class EnergyChartsClientTest {

    /** 08:00 UTC on 2026-08-31, the start of a whole hour. */
    private static final long H8 = 1788249600L;

    private static EnergyChartsClient client(double markup) {
        return new EnergyChartsClient(new org.eclipse.jetty.client.HttpClient(), "BE", markup);
    }

    private static String payload(String seconds, String prices) {
        return "{\"unix_seconds\":[" + seconds + "],\"price\":[" + prices + "],\"unit\":\"EUR / MWh\"}";
    }

    @Test
    void quarterHoursBecomeTheHourlyMean() {
        // 100, 200, 300, 400 EUR/MWh across one hour -> mean 250 -> 0.25 EUR/kWh
        HourlyPrices p = client(0.0)
                .parse(payload(H8 + "," + (H8 + 900) + "," + (H8 + 1800) + "," + (H8 + 2700), "100,200,300,400"));

        assertNull(p.lastError());
        assertEquals(1, p.prices().size(), "four quarters are one hour, not four");
        assertEquals(0.25, p.prices().get(Instant.ofEpochSecond(H8)), 1e-9);
    }

    @Test
    void theMarkupIsAddedPerKWhNotPerMWh() {
        HourlyPrices p = client(0.12).parse(payload(String.valueOf(H8), "180"));

        assertEquals(0.30, p.prices().get(Instant.ofEpochSecond(H8)), 1e-9,
                "180 EUR/MWh is 0.18 EUR/kWh, plus 0.12 markup");
    }

    @Test
    void unpublishedSlotsAreSkippedRatherThanCountedAsZero() {
        HourlyPrices p = client(0.0)
                .parse(payload(H8 + "," + (H8 + 900) + "," + (H8 + 1800) + "," + (H8 + 2700), "100,null,null,300"));

        assertEquals(0.20, p.prices().get(Instant.ofEpochSecond(H8)), 1e-9,
                "a null slot must not drag the hourly mean toward zero");
    }

    @Test
    void aRealZeroPriceIsKept() {
        HourlyPrices p = client(0.0).parse(payload(String.valueOf(H8), "0"));

        assertEquals(0.0, p.prices().get(Instant.ofEpochSecond(H8)), 1e-9,
                "zero and negative prices are real market outcomes, not missing data");
    }

    @Test
    void negativePricesSurvive() {
        HourlyPrices p = client(0.0).parse(payload(String.valueOf(H8), "-50"));

        assertEquals(-0.05, p.prices().get(Instant.ofEpochSecond(H8)), 1e-9);
    }

    @Test
    void separateHoursStaySeparate() {
        HourlyPrices p = client(0.0).parse(payload(H8 + "," + (H8 + 3600), "100,200"));

        assertEquals(2, p.prices().size());
        assertEquals(0.10, p.prices().get(Instant.ofEpochSecond(H8)), 1e-9);
        assertEquals(0.20, p.prices().get(Instant.ofEpochSecond(H8 + 3600)), 1e-9);
    }

    @Test
    void anEmptyOrUnexpectedBodyIsAnErrorNotAnEmptyPriceList() {
        assertNotNull(client(0.0).parse("{}").lastError(), "a shape we do not recognise must not look like success");
        assertNotNull(client(0.0).parse("not json").lastError());
        assertNotNull(client(0.0).parse(payload("", "")).lastError(), "no slots at all is a failed fetch");
    }

    @Test
    void theSubProviderKeyMatchesTheConfigOption() {
        assertEquals("energy-charts", client(0.0).subProvider());
    }

    @Test
    void theRequestAsksForTodayAndTomorrow() {
        // Without a range the API answers with today only, and tomorrow's prices never arrive.
        String url = client(0.0).url(java.time.LocalDate.of(2026, 9, 1));
        assertTrue(url.contains("start=2026-09-01"), url);
        assertTrue(url.contains("end=2026-09-03"), url);
        assertTrue(url.contains("bzn=BE"), url);
    }
}

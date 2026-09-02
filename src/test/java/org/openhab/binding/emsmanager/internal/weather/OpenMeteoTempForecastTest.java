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
package org.openhab.binding.emsmanager.internal.weather;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OpenMeteoTempForecast}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class OpenMeteoTempForecastTest {

    // Two hours starting 2026-01-01T00:00:00Z (1767225600) and +1h (1767229200).
    private static final String SAMPLE = "{\"hourly\":{\"time\":[1767225600,1767229200,1767232800],"
            + "\"temperature_2m\":[3.5,2.8,2.1]}}";

    @Test
    void parsesUnixtimeAndTemps() {
        TreeMap<Instant, Double> m = OpenMeteoTempForecast.parse(SAMPLE);
        assertEquals(3, m.size());
        assertEquals(3.5, m.get(Instant.ofEpochSecond(1767225600)), 1e-9);
        assertEquals(2.8, m.get(Instant.ofEpochSecond(1767229200)), 1e-9);
        assertEquals(2.1, m.get(Instant.ofEpochSecond(1767232800)), 1e-9);
    }

    @Test
    void ascendingOrder() {
        TreeMap<Instant, Double> m = OpenMeteoTempForecast.parse(SAMPLE);
        assertEquals(Instant.ofEpochSecond(1767225600), m.firstKey());
        assertEquals(Instant.ofEpochSecond(1767232800), m.lastKey());
    }

    @Test
    void malformedReturnsEmpty() {
        assertTrue(OpenMeteoTempForecast.parse("not json").isEmpty());
        assertTrue(OpenMeteoTempForecast.parse("{}").isEmpty());
        assertTrue(OpenMeteoTempForecast.parse("{\"hourly\":{}}").isEmpty());
    }

    @Test
    void skipsNullTemps() {
        String s = "{\"hourly\":{\"time\":[1767225600,1767229200],\"temperature_2m\":[null,2.8]}}";
        TreeMap<Instant, Double> m = OpenMeteoTempForecast.parse(s);
        assertEquals(1, m.size());
        assertEquals(2.8, m.get(Instant.ofEpochSecond(1767229200)), 1e-9);
    }

    /** A client whose API is down: every request times out. */
    private static HttpClient downClient() throws Exception {
        Request request = mock(Request.class);
        when(request.timeout(anyLong(), any())).thenReturn(request);
        when(request.send()).thenThrow(new TimeoutException("simulated outage"));
        HttpClient client = mock(HttpClient.class);
        when(client.newRequest(anyString())).thenReturn(request);
        return client;
    }

    /**
     * hourlyFrom is asked on every 5 s tick under the tick lock; with the API down each call used
     * to be a fresh 10 s attempt, so an Open-Meteo outage stalled the whole EMS.
     */
    @Test
    void anOutageIsNotRetriedOnEveryTick() throws Exception {
        HttpClient client = downClient();
        AtomicLong clock = new AtomicLong(1_000_000L);
        OpenMeteoTempForecast f = new OpenMeteoTempForecast(client, 50.8, 4.3, clock::get);

        for (int tick = 0; tick < 48; tick++) {
            assertEquals(0, f.hourlyFrom(Instant.ofEpochMilli(clock.get()), 24).length);
            clock.addAndGet(5_000L);
        }

        verify(client, times(1)).newRequest(anyString());
    }

    @Test
    void itDoesRetryOnceTheSpacingHasPassed() throws Exception {
        HttpClient client = downClient();
        AtomicLong clock = new AtomicLong(1_000_000L);
        OpenMeteoTempForecast f = new OpenMeteoTempForecast(client, 50.8, 4.3, clock::get);

        f.hourlyFrom(Instant.ofEpochMilli(clock.get()), 24);
        clock.addAndGet(6 * 60_000L);
        f.hourlyFrom(Instant.ofEpochMilli(clock.get()), 24);

        verify(client, times(2)).newRequest(anyString());
    }
}

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
package org.openhab.binding.emsmanager.internal.emissions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.jupiter.api.Test;

/**
 * The provider is asked for a value on every 5 s tick, under the tick lock. How often that may turn
 * into an HTTP call is a separate question: the free tier is 50 calls a day and the timeout is 10 s.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class ElectricityMapsRetryTest {

    private static final long MINUTE = 60_000L;

    /** A client whose API is down: every request times out. */
    private static HttpClient downClient() throws Exception {
        Request request = mock(Request.class);
        when(request.header(anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong(), any())).thenReturn(request);
        when(request.send()).thenThrow(new TimeoutException("simulated outage"));
        HttpClient client = mock(HttpClient.class);
        when(client.newRequest(anyString())).thenReturn(request);
        return client;
    }

    /** A client that answers 142 gCO2/kWh. */
    private static HttpClient healthyClient() throws Exception {
        ContentResponse response = mock(ContentResponse.class);
        when(response.getStatus()).thenReturn(200);
        when(response.getContentAsString()).thenReturn("{\"zone\":\"BE\",\"carbonIntensity\":142}");
        Request request = mock(Request.class);
        when(request.header(anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong(), any())).thenReturn(request);
        when(request.send()).thenReturn(response);
        HttpClient client = mock(HttpClient.class);
        when(client.newRequest(anyString())).thenReturn(request);
        return client;
    }

    @Test
    void anOutageIsNotRetriedOnEveryTick() throws Exception {
        HttpClient client = downClient();
        AtomicLong clock = new AtomicLong(1_000_000L);
        ElectricityMapsProvider p = new ElectricityMapsProvider(client, "key", "BE", 350.0, clock::get);

        for (int tick = 0; tick < 48; tick++) {
            assertTrue(Double.isNaN(p.currentGridGramsPerKWh()), "nothing was ever fetched");
            clock.addAndGet(5_000L);
        }

        verify(client, times(1)).newRequest(anyString());
    }

    @Test
    void itDoesRetryOnceTheSpacingHasPassed() throws Exception {
        HttpClient client = downClient();
        AtomicLong clock = new AtomicLong(1_000_000L);
        ElectricityMapsProvider p = new ElectricityMapsProvider(client, "key", "BE", 350.0, clock::get);

        p.currentGridGramsPerKWh();
        clock.addAndGet(6 * MINUTE);
        p.currentGridGramsPerKWh();

        verify(client, times(2)).newRequest(anyString());
    }

    @Test
    void aGoodValueIsHeldForTheRefreshIntervalWithoutCalling() throws Exception {
        HttpClient client = healthyClient();
        AtomicLong clock = new AtomicLong(1_000_000L);
        ElectricityMapsProvider p = new ElectricityMapsProvider(client, "key", "BE", 350.0, clock::get);

        assertEquals(142.0, p.currentGridGramsPerKWh(), 1e-9);
        clock.addAndGet(29 * MINUTE);
        assertEquals(142.0, p.currentGridGramsPerKWh(), 1e-9);

        verify(client, times(1)).newRequest(anyString());
    }
}

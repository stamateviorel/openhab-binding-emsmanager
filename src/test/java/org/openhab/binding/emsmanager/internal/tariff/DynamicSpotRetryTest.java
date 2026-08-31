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
package org.openhab.binding.emsmanager.internal.tariff;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.tariff.spot.HourlyPrices;
import org.openhab.binding.emsmanager.internal.tariff.spot.SpotPriceClient;

/**
 * The handler snapshots once a minute; these pin down how often that is allowed to reach the
 * remote price feed, which is not the same thing.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class DynamicSpotRetryTest {

    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
    private static final long MINUTE = 60_000L;

    /**
     * The provider persists successful fetches, so without this the suite would write synthetic
     * prices into the running installation's cache and the live system would plan against them.
     */
    @BeforeAll
    static void redirectCacheAwayFromTheLiveInstall() throws java.io.IOException {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("ems-tariff-test");
        java.nio.file.Files.createDirectories(tmp.resolve("cache"));
        tmp.toFile().deleteOnExit();
        System.setProperty("openhab.userdata", tmp.toString());
    }

    @AfterAll
    static void restoreCacheLocation() {
        System.clearProperty("openhab.userdata");
    }

    /** Counts calls, and fails until told otherwise - like a platform in maintenance. */
    private static final class CountingClient implements SpotPriceClient {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean healthy = false;

        @Override
        public String subProvider() {
            return "test";
        }

        @Override
        public HourlyPrices fetch() {
            calls.incrementAndGet();
            if (!healthy) {
                return HourlyPrices.empty("ENTSO-E HTTP 503");
            }
            TreeMap<Instant, Double> m = new TreeMap<>();
            Instant midnight = NOW.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
            for (int h = 0; h < 48; h++) {
                m.put(midnight.plusSeconds(h * 3600L), 0.10 + h * 0.001);
            }
            return new HourlyPrices(m, NOW, null);
        }
    }

    @Test
    void anOutageIsNotRetriedOnEveryPoll() {
        CountingClient client = new CountingClient();
        AtomicLong clock = new AtomicLong(1_000_000L);
        DynamicSpotTariff t = new DynamicSpotTariff(client, 60, clock::get);

        for (int minute = 0; minute < 4; minute++) {
            t.snapshot(NOW);
            clock.addAndGet(MINUTE);
        }

        assertEquals(1, client.calls.get(), "four polls inside the retry window must be one API call");
    }

    @Test
    void itDoesRetryOnceTheSpacingHasPassed() {
        CountingClient client = new CountingClient();
        AtomicLong clock = new AtomicLong(1_000_000L);
        DynamicSpotTariff t = new DynamicSpotTariff(client, 60, clock::get);

        t.snapshot(NOW);
        clock.addAndGet(6 * MINUTE);
        t.snapshot(NOW);

        assertEquals(2, client.calls.get(), "a throttle that never lets go would never recover");
    }

    @Test
    void theOutageStaysReportedWhileThrottled() {
        CountingClient client = new CountingClient();
        AtomicLong clock = new AtomicLong(1_000_000L);
        DynamicSpotTariff t = new DynamicSpotTariff(client, 60, clock::get);

        t.snapshot(NOW);
        clock.addAndGet(MINUTE);

        assertEquals("ENTSO-E HTTP 503", t.snapshot(NOW).lastError(),
                "a skipped attempt must not clear the error and look healthy");
    }

    @Test
    void recoveryPublishesRealPrices() {
        CountingClient client = new CountingClient();
        AtomicLong clock = new AtomicLong(1_000_000L);
        DynamicSpotTariff t = new DynamicSpotTariff(client, 60, clock::get);

        t.snapshot(NOW);
        client.healthy = true;
        clock.addAndGet(6 * MINUTE);
        TariffSnapshot snap = t.snapshot(NOW);

        assertNull(snap.lastError(), "the error must clear once the feed answers again");
        assertFalse(Double.isNaN(snap.nowPriceEurPerKWh()), "real prices must take over from the fallback");
    }
}

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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Covers the cold-start outage path: a spot feed that has never cached a day must not take the
 * whole tariff plane down with it.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class FallbackTariffTest {

    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");

    /** A provider that never has a price, like ENTSO-E during platform maintenance. */
    private static TariffProvider failing(String error) {
        return new TariffProvider() {
            @Override
            public String kind() {
                return DynamicSpotTariff.KIND;
            }

            @Override
            public TariffSnapshot snapshot(Instant now) {
                return new TariffSnapshot(now, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, null, null,
                        new double[0], new double[0], error);
            }
        };
    }

    @Test
    void servesTheFlatPriceWhenTheFeedHasNothing() {
        TariffProvider t = new FallbackTariff(failing("ENTSO-E HTTP 503"), new FlatTariff(0.30));

        TariffSnapshot snap = t.snapshot(NOW);

        assertEquals(0.30, snap.nowPriceEurPerKWh(), 1e-9, "a planner must still get a price to plan against");
        assertEquals(24, snap.schedule24h().length, "the 24h schedule must be usable, not empty");
    }

    @Test
    void keepsTheOutageVisibleInsteadOfMaskingIt() {
        TariffProvider t = new FallbackTariff(failing("ENTSO-E HTTP 503"), new FlatTariff(0.30));

        assertEquals("ENTSO-E HTTP 503", t.snapshot(NOW).lastError(),
                "a fallback price must not look like a healthy feed");
    }

    @Test
    void realPricesWinOverTheFallback() {
        TariffProvider t = new FallbackTariff(new FlatTariff(0.11), new FlatTariff(0.30));

        TariffSnapshot snap = t.snapshot(NOW);

        assertEquals(0.11, snap.nowPriceEurPerKWh(), 1e-9, "the primary must be preferred while it works");
        assertNull(snap.lastError());
    }

    @Test
    void reportsThePrimaryKindSoTheConfiguredModeIsStillIdentifiable() {
        assertEquals(DynamicSpotTariff.KIND, new FallbackTariff(failing("x"), new FlatTariff(0.30)).kind());
    }

    @Test
    void isFallbackDistinguishesDegradedFromDown() {
        TariffSnapshot degraded = new FallbackTariff(failing("HTTP 503"), new FlatTariff(0.30)).snapshot(NOW);
        TariffSnapshot down = failing("HTTP 503").snapshot(NOW);
        TariffSnapshot healthy = new FlatTariff(0.30).snapshot(NOW);

        assertTrue(FallbackTariff.isFallback(degraded), "priced but erroring == degraded, keep the thing ONLINE");
        assertFalse(FallbackTariff.isFallback(down), "no price at all == genuinely offline");
        assertFalse(FallbackTariff.isFallback(healthy), "no error == not a fallback");
    }
}

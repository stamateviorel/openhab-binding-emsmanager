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
package org.openhab.binding.emsmanager.internal.forecast;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OpenMeteoForecast#acWatts} — the GTI→AC conversion and the
 * inverter-clipping cap that stops a 25 kWp array forecast over-predicting on a
 * ~10 kW inverter (forecast read ~160 kWh vs ~93 kWh actual before the cap).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class OpenMeteoForecastTest {

    /** Without a cap, AC power is GTI × kWp × PR. */
    @Test
    void uncappedIsGtiTimesKwpTimesPr() {
        // 800 W/m² × 25 kWp × 0.85 = 17000 W (well above a 10 kW inverter).
        assertEquals(17_000.0, OpenMeteoForecast.acWatts(800.0, 25.0, 0.85, 0.0), 1e-6);
    }

    /** A bright hour that out-produces the inverter is clipped to the cap. */
    @Test
    void brightHourClipsToInverterCap() {
        double cap = 10_300.0; // 10.3 kW
        // 800 W/m² would model 17 kW DC — must clip to the 10.3 kW AC ceiling.
        assertEquals(cap, OpenMeteoForecast.acWatts(800.0, 25.0, 0.85, cap), 1e-6);
    }

    /** Unclipped morning/evening/cloudy hours below the cap pass through untouched. */
    @Test
    void lowIrradianceHourPassesThroughUnclipped() {
        double cap = 10_300.0;
        // 300 W/m² × 25 × 0.85 = 6375 W < cap → unchanged (so shoulders stay accurate).
        assertEquals(6_375.0, OpenMeteoForecast.acWatts(300.0, 25.0, 0.85, cap), 1e-6);
    }

    /** Negative/zero irradiance never produces negative power. */
    @Test
    void negativeIrradianceClampsToZero() {
        assertEquals(0.0, OpenMeteoForecast.acWatts(-50.0, 25.0, 0.85, 10_300.0), 1e-9);
        assertEquals(0.0, OpenMeteoForecast.acWatts(0.0, 25.0, 0.85, 0.0), 1e-9);
    }
}

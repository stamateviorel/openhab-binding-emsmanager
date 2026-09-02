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
package org.openhab.binding.emsmanager.internal.controller.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * The legacy dispatcher over the shared schedule: the night charge is gated and a window is released.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class BatteryTouDispatcherTest {

    private EnergyContext ctx(int hour, double soc, double forecastTomorrowKwh) {
        ZonedDateTime t = ZonedDateTime.of(2026, 3, 10, hour, 0, 0, 0, ZoneId.systemDefault());
        return new EnergyContext(t.toInstant(), 0, 0, 0, 0, 0, soc, 30, false, 0, EnergyContext.Mode.BALANCED, Map.of(),
                0, 0, 0, true, false, false, true, 0, 0, false, 0, 0, 60_000L, 0.30, new double[0], Double.NaN,
                forecastTomorrowKwh, false);
    }

    private double value(List<SetpointRequest> out) {
        assertEquals(1, out.size());
        assertEquals("battery", out.get(0).assetId());
        assertEquals(SetpointRequest.Kind.WATTS_BATTERY, out.get(0).kind());
        return out.get(0).value();
    }

    @Test
    void nightChargeThenAnExplicitZeroAtSix() {
        BatteryTouDispatcher d = new BatteryTouDispatcher(false);

        assertEquals(-2000.0, value(d.evaluate(ctx(3, 40, Double.NaN))), 1e-9);
        assertEquals(0.0, value(d.evaluate(ctx(6, 60, Double.NaN))), 1e-9, "the −2000 W must be released at 06:00");
        assertTrue(d.evaluate(ctx(7, 60, Double.NaN)).isEmpty());
    }

    @Test
    void noNightChargeWhenFullOrTomorrowIsSunny() {
        assertTrue(new BatteryTouDispatcher(false).evaluate(ctx(3, 95, Double.NaN)).isEmpty());
        assertTrue(new BatteryTouDispatcher(false).evaluate(ctx(3, 40, 30.0)).isEmpty());
    }
}

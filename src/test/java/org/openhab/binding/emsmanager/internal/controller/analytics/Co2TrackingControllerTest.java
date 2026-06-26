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
package org.openhab.binding.emsmanager.internal.controller.analytics;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;

/**
 * Tests for {@link Co2TrackingController} — pins the avoided-emissions accounting.
 *
 * <p>
 * The original code only credited grid <em>exports</em> (× injection-offset
 * factor) and silently ignored self-consumed solar, which on a battery+PV site
 * is the larger term — leaving CO₂-saved ~10× below what the € savings metric
 * (which counts the same self-consumption) implied. These tests lock in that
 * self-consumed solar is now credited at the grid factor, with correct units.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class Co2TrackingControllerTest {

    private static final double GRID = 140.0; // g CO₂ / kWh avoided per self-consumed kWh
    private static final double OFFSET = 350.0; // g CO₂ / kWh avoided per exported kWh

    /** Build a context with the only fields this controller reads: tick, grid, solar. */
    private EnergyContext ctx(Instant t, double gridW, double solarW) {
        Map<String, CarSnapshot> noCars = Map.of();
        return new EnergyContext(t, gridW, 0, solarW, 0, 0, 50, 30, false, 0, EnergyContext.Mode.GRID_IMPORT, noCars, 0,
                0, 0, true, false, false, false, 0, 0, false, 0, 0, 60_000L, 0.30, new double[0], Double.NaN,
                Double.NaN, false);
    }

    /** Self-consumed solar (grid ≈ 0) must credit CO₂-saved at the grid factor, in kg. */
    @Test
    void selfConsumedSolarCreditsSavedAtGridFactor() {
        Co2TrackingController c = new Co2TrackingController(null, null, GRID, OFFSET, null);
        Instant t0 = Instant.parse("2026-06-03T12:00:00Z");
        c.evaluate(ctx(t0, 0.0, 10_000.0)); // first tick — establishes the clock, no accrual
        c.evaluate(ctx(t0.plusSeconds(36), 0.0, 10_000.0)); // +36 s (0.01 h) at 10 kW self-consumed
        // 10 kW × 0.01 h = 0.1 kWh; 0.1 kWh × 140 g/kWh = 14 g = 0.014 kg.
        assertEquals(0.014, c.todaySavedKg(), 1e-9, "self-consumed solar must credit saved at the grid factor");
        assertEquals(0.0, c.todayEmittedKg(), 1e-9, "no grid import → no emissions");
    }

    /** Export + self-consumption accrue independently (export at offset, self-use at grid). */
    @Test
    void exportAndSelfConsumptionBothCredited() {
        Co2TrackingController c = new Co2TrackingController(null, null, GRID, OFFSET, null);
        Instant t0 = Instant.parse("2026-06-03T12:00:00Z");
        c.evaluate(ctx(t0, 5_000.0, 10_000.0)); // first tick
        c.evaluate(ctx(t0.plusSeconds(36), 5_000.0, 10_000.0)); // +0.01 h: 5 kW export, 5 kW self-used
        // export: 5 kW × 0.01 h × 350 = 17.5 g; self-use: 5 kW × 0.01 h × 140 = 7 g → 24.5 g = 0.0245 kg.
        assertEquals(0.0245, c.todaySavedKg(), 1e-9, "export (offset) + self-consumption (grid) both credited");
    }

    /** Pure grid import emits, saves nothing. */
    @Test
    void gridImportEmitsAndSavesNothing() {
        Co2TrackingController c = new Co2TrackingController(null, null, GRID, OFFSET, null);
        Instant t0 = Instant.parse("2026-06-03T12:00:00Z");
        c.evaluate(ctx(t0, -2_000.0, 0.0)); // first tick
        c.evaluate(ctx(t0.plusSeconds(36), -2_000.0, 0.0)); // +0.01 h importing 2 kW, no solar
        // 2 kW × 0.01 h × 140 = 2.8 g = 0.0028 kg emitted; nothing saved.
        assertEquals(0.0028, c.todayEmittedKg(), 1e-9);
        assertEquals(0.0, c.todaySavedKg(), 1e-9);
    }
}

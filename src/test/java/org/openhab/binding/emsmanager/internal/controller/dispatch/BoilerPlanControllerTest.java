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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.controller.peak.HardPeakShavingController;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * Tests for {@link BoilerPlanController}: the deadline-aware DHW planner heats
 * only overnight, only at the cheapest hours needed to fill the energy gap,
 * defers to peak-shaving + the user override, and stops once the target is met.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class BoilerPlanControllerTest {

    private static final double[] FLAT = new double[0];

    private HardPeakShavingController idleHard() {
        return new HardPeakShavingController(false, false);
    }

    /** Build a context at a given local hour (timezone-robust). */
    private EnergyContext ctxAt(int hour, boolean boilerOn, boolean userOverride, double[] schedule,
            boolean peakEnabled) {
        Instant t = ZonedDateTime.of(2026, 6, 3, hour, 0, 0, 0, ZoneId.systemDefault()).toInstant();
        Map<String, CarSnapshot> noCars = Map.of();
        return new EnergyContext(t, 0, 0, 0, 0, 0, 50, 30, false, 0, EnergyContext.Mode.GRID_IMPORT, noCars, 0, 0, 0,
                true, boilerOn, false, peakEnabled, 0, 0, userOverride, 0, 0, 60_000L, 0.30, schedule, Double.NaN,
                Double.NaN, false);
    }

    private boolean emitsBoilerOn(List<SetpointRequest> out) {
        return out.stream().anyMatch(
                r -> "boiler".equals(r.assetId()) && r.kind() == SetpointRequest.Kind.ONOFF && r.value() >= 0.5);
    }

    @Test
    void disabledWhenTargetZero() {
        BoilerPlanController c = new BoilerPlanController(false, 0.0, 7, 3.0, idleHard());
        assertFalse(c.enabled(), "target 0 disables the planner");
        List<SetpointRequest> out = c.evaluate(ctxAt(3, false, false, FLAT, false));
        assertTrue(out.isEmpty());
        assertFalse(c.wantsBoilerOn());
    }

    @Test
    void overnightBelowTargetHeatsAtCheapestHour() {
        BoilerPlanController c = new BoilerPlanController(false, 4.5, 7, 3.0, idleHard());
        double[] sched = new double[24];
        java.util.Arrays.fill(sched, 0.30);
        sched[3] = 0.10; // hour 3 cheapest
        sched[4] = 0.15; // hour 4 second-cheapest
        // gap 4.5 kWh / 3 kW = 2 h needed → cheapest 2 of [3..6] = {3,4}; now=3 is in it.
        List<SetpointRequest> out = c.evaluate(ctxAt(3, false, false, sched, false));
        assertTrue(emitsBoilerOn(out), "should heat now — hour 3 is among the cheapest needed hours");
        assertTrue(c.wantsBoilerOn());
    }

    @Test
    void overnightWaitsForACheaperHour() {
        BoilerPlanController c = new BoilerPlanController(false, 1.0, 7, 3.0, idleHard());
        double[] sched = new double[24];
        java.util.Arrays.fill(sched, 0.30);
        sched[6] = 0.05; // cheapest is later (hour 6)
        // gap 1 kWh / 3 kW → 1 h needed → cheapest 1 of [3..6] = {6}; now=3 not in it → wait.
        List<SetpointRequest> out = c.evaluate(ctxAt(3, false, false, sched, false));
        assertTrue(out.isEmpty(), "should wait — a cheaper hour is still ahead before the deadline");
        assertFalse(c.wantsBoilerOn());
    }

    @Test
    void daytimeDefersToSolar() {
        BoilerPlanController c = new BoilerPlanController(false, 4.5, 7, 3.0, idleHard());
        // 14:00 is past the ready-by hour — grid top-up is off; solar (SolarSurplus) owns the day.
        List<SetpointRequest> out = c.evaluate(ctxAt(14, false, false, FLAT, false));
        assertTrue(out.isEmpty());
        assertFalse(c.wantsBoilerOn());
    }

    @Test
    void defersToPeakShaving() {
        HardPeakShavingController hard = new HardPeakShavingController(false, false);
        hard.requestManualEngage();
        hard.evaluate(ctxAt(3, false, false, FLAT, true)); // engage tier 1 → level > 0
        assertTrue(hard.level() > 0, "precondition: hard peak-shaving engaged");

        BoilerPlanController c = new BoilerPlanController(false, 4.5, 7, 3.0, hard);
        List<SetpointRequest> out = c.evaluate(ctxAt(3, false, false, FLAT, true));
        assertTrue(out.isEmpty(), "must not add boiler load during a hard peak event");
        assertFalse(c.wantsBoilerOn());
    }

    @Test
    void respectsUserOverride() {
        BoilerPlanController c = new BoilerPlanController(false, 4.5, 7, 3.0, idleHard());
        List<SetpointRequest> out = c.evaluate(ctxAt(3, false, true, FLAT, false));
        assertTrue(out.isEmpty(), "user override owns the boiler");
        assertFalse(c.wantsBoilerOn());
    }

    @Test
    void stopsOnceTargetMet() {
        BoilerPlanController c = new BoilerPlanController(false, 4.5, 7, 3.0, idleHard());
        c.evaluate(ctxAt(2, true, false, FLAT, false)); // first tick: no integration yet
        c.evaluate(ctxAt(3, true, false, FLAT, false)); // +1 h on at 3 kW → 3 kWh
        List<SetpointRequest> out = c.evaluate(ctxAt(4, true, false, FLAT, false)); // +1 h → 6 kWh ≥ 4.5
        assertTrue(out.isEmpty(), "target met → stop heating");
        assertFalse(c.wantsBoilerOn());
    }
}

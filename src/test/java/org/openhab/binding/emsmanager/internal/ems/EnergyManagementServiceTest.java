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
package org.openhab.binding.emsmanager.internal.ems;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EnergyManagementService#planSurplusDispatch} — the baseline surplus-soak
 * strategy realising Kai Kreuzer's "simple default" energy-management algorithm (#3478).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class EnergyManagementServiceTest {

    private EnergyConsumer controllable(String item, double min, double max) {
        return new EnergyConsumer(item, new PowerProfile.Controllable(item, min, max), 0, -1);
    }

    private EnergyConsumer simple(String item) {
        return new EnergyConsumer(item, new PowerProfile.Simple(item), 0, -1);
    }

    @Test
    void controllableSoaksSurplusUpToMax() {
        List<EmsAction> a = EnergyManagementService.planSurplusDispatch(5000,
                List.of(controllable("Wallbox", 1400, 11000)), 1000);
        assertEquals(1, a.size());
        assertEquals("Wallbox", a.get(0).itemName());
        assertEquals(EmsAction.Kind.SET_WATTS, a.get(0).kind());
        assertEquals(5000.0, a.get(0).value(), 1e-9);
    }

    @Test
    void surplusCappedAtMax() {
        List<EmsAction> a = EnergyManagementService.planSurplusDispatch(20000,
                List.of(controllable("Wallbox", 1400, 11000)), 1000);
        assertEquals(11000.0, a.get(0).value(), 1e-9);
    }

    @Test
    void splitsAcrossConsumersInPriorityOrder() {
        List<EmsAction> a = EnergyManagementService.planSurplusDispatch(4000,
                List.of(controllable("A", 0, 3000), controllable("B", 0, 3000)), 1000);
        assertEquals(3000.0, a.get(0).value(), 1e-9, "first consumer gets its full max");
        assertEquals(1000.0, a.get(1).value(), 1e-9, "second gets the remainder");
    }

    @Test
    void belowLoadMinimumTurnsOff() {
        List<EmsAction> a = EnergyManagementService.planSurplusDispatch(800,
                List.of(controllable("Wallbox", 1400, 11000)), 1000);
        assertEquals(0.0, a.get(0).value(), 1e-9, "800 W < 1400 W min → off");
    }

    @Test
    void simpleLoadOnAboveThresholdOffBelow() {
        List<EmsAction> on = EnergyManagementService.planSurplusDispatch(1500, List.of(simple("Boiler")), 1000);
        assertEquals(EmsAction.Kind.ONOFF, on.get(0).kind());
        assertEquals(1.0, on.get(0).value(), 1e-9);

        List<EmsAction> off = EnergyManagementService.planSurplusDispatch(500, List.of(simple("Boiler")), 1000);
        assertEquals(0.0, off.get(0).value(), 1e-9);
    }

    @Test
    void noSurplusReleasesEverything() {
        List<EmsAction> a = EnergyManagementService.planSurplusDispatch(0,
                List.of(simple("Boiler"), controllable("Wallbox", 1400, 11000)), 1000);
        assertEquals(0.0, a.get(0).value(), 1e-9);
        assertEquals(0.0, a.get(1).value(), 1e-9);
    }

    @Test
    void runsInTheCheapestHoursBeforeDeadline() {
        // Deadline 7, a 2 kW load needing 2 kWh = 1 h; it should pick the single cheapest hour left.
        double[] sched = new double[24];
        for (int h = 0; h < 24; h++) {
            sched[h] = 0.30;
        }
        sched[2] = 0.10;
        sched[3] = 0.12;
        sched[5] = 0.20;
        assertTrue(EnergyManagementService.runNowForDeadline(2, 7, 2.0, 2.0, sched), "hour 2 is cheapest → run");
        assertTrue(EnergyManagementService.runNowForDeadline(3, 7, 2.0, 2.0, sched), "hour 3 cheapest left → run");
        assertFalse(EnergyManagementService.runNowForDeadline(4, 7, 2.0, 2.0, sched),
                "hour 4 dearer than hour 5 still ahead → wait");
    }

    @Test
    void deadlineWrapsPastMidnight() {
        double[] flat = new double[0]; // no prices → latest hours before the 07:00 deadline
        // 23:00 now, ready by 07:00, need 2 h: cheapest-fallback = hours 05,06 → wait at 23, run at 05.
        assertFalse(EnergyManagementService.runNowForDeadline(23, 7, 4.0, 2.0, flat), "evening → wait for overnight");
        assertTrue(EnergyManagementService.runNowForDeadline(5, 7, 4.0, 2.0, flat),
                "05:00 is in the late window → run");
    }

    @Test
    void deadlineDoneAndAtDeadlineCases() {
        double[] flat = new double[0];
        assertFalse(EnergyManagementService.runNowForDeadline(2, 7, 0.0, 2.0, flat), "demand met → off");
        assertTrue(EnergyManagementService.runNowForDeadline(7, 7, 2.0, 2.0, flat), "at the deadline hour → run now");
    }

    @Test
    void parsesScheduleCsv() {
        double[] s = EnergyManagementService.parseSchedule("0.30,0.28,0.10");
        assertEquals(3, s.length);
        assertEquals(0.10, s[2], 1e-9);
        assertEquals(0, EnergyManagementService.parseSchedule("").length);
        assertEquals(0, EnergyManagementService.parseSchedule("0.3,bad").length);
    }

    private EnergyProvider battery(double min, double max) {
        return new EnergyProvider("Bat_Setpoint", ProviderRole.BATTERY, true, "Bat_Setpoint", min, max, null, null,
                null);
    }

    @Test
    void batteryChargesFromSurplusClampedToLimit() {
        EmsAction a = EnergyManagementService.planBatteryCharge(5000, 50, battery(-3000, 3000));
        assertNotNull(a);
        assertEquals("Bat_Setpoint", a.itemName());
        assertEquals(-3000.0, a.value(), 1e-9, "charge clamped to the -3000 W limit");
        EmsAction b = EnergyManagementService.planBatteryCharge(2000, 50, battery(-3000, 3000));
        assertEquals(-2000.0, b.value(), 1e-9, "charge at the surplus when below the limit");
    }

    @Test
    void batteryIdleWhenFullOrNoSurplus() {
        assertEquals(0.0, EnergyManagementService.planBatteryCharge(5000, 100, battery(-3000, 3000)).value(), 1e-9);
        assertEquals(0.0, EnergyManagementService.planBatteryCharge(0, 50, battery(-3000, 3000)).value(), 1e-9);
    }

    @Test
    void nonControllableBatteryYieldsNoAction() {
        EnergyProvider pv = new EnergyProvider("Solar", ProviderRole.PV, false, null, Double.NaN, Double.NaN, null,
                null, null);
        assertNull(EnergyManagementService.planBatteryCharge(5000, 50, pv));
    }

    @Test
    void breakerHeadroomIsLimitMinusWorstPhase() {
        assertEquals(8.0, EnergyManagementService.minBreakerHeadroomA(20, 45, 30, 53), 1e-9, "53 − worst phase 45");
        assertEquals(53.0, EnergyManagementService.minBreakerHeadroomA(Double.NaN, Double.NaN, Double.NaN, 53), 1e-9,
                "unknown amps → full headroom");
        assertEquals(Double.POSITIVE_INFINITY, EnergyManagementService.minBreakerHeadroomA(10, 10, 10, 0), 1e-9,
                "limit 0 disables the guard");
    }

    @Test
    void surplusFromGridNet() {
        assertEquals(4200.0, EnergyManagementService.surplusFromGridNet(4200), 1e-9, "exporting → that much spare");
        assertEquals(0.0, EnergyManagementService.surplusFromGridNet(-1500), 1e-9, "importing → no surplus");
        assertEquals(0.0, EnergyManagementService.surplusFromGridNet(Double.NaN), 1e-9, "unknown → no surplus");
    }

    @Test
    void nanSurplusTreatedAsZero() {
        List<EmsAction> a = EnergyManagementService.planSurplusDispatch(Double.NaN,
                List.of(controllable("Wallbox", 0, 11000)), 1000);
        assertEquals(0.0, a.get(0).value(), 1e-9);
    }
}

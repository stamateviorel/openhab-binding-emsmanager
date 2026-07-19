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
    void unifiedDeadlineDrivenLoadRunsWithoutSurplus() {
        EnergyConsumer boiler = new EnergyConsumer("Boiler", new PowerProfile.Simple("Boiler"), 4.0, 10);
        List<EmsAction> a = EnergyManagementService.planConsumers(List.of(boiler), 0.0, 1000, 10, new double[0]);
        assertEquals(1.0, a.get(0).value(), 1e-9, "deadline hour now → on even with no surplus");
        assertTrue(a.get(0).reason().contains("deadline"));
    }

    @Test
    void unifiedSurplusDriveWhenNoDeadlineDue() {
        EnergyConsumer boiler = new EnergyConsumer("Boiler", new PowerProfile.Simple("Boiler"), 0, -1);
        assertEquals(1.0,
                EnergyManagementService.planConsumers(List.of(boiler), 1500, 1000, 12, new double[0]).get(0).value(),
                1e-9);
        assertEquals(0.0,
                EnergyManagementService.planConsumers(List.of(boiler), 500, 1000, 12, new double[0]).get(0).value(),
                1e-9);
    }

    @Test
    void unifiedDeadlineLoadDoesNotConsumeSurplusBudget() {
        EnergyConsumer boiler = new EnergyConsumer("Boiler", new PowerProfile.Simple("Boiler"), 4.0, 10);
        EnergyConsumer wallbox = new EnergyConsumer("Wallbox", new PowerProfile.Controllable("Wallbox", 0, 3000), 0,
                -1);
        List<EmsAction> a = EnergyManagementService.planConsumers(List.of(boiler, wallbox), 3000, 1000, 10,
                new double[0]);
        assertEquals(1.0, a.get(0).value(), 1e-9, "boiler on (deadline-driven)");
        assertEquals(3000.0, a.get(1).value(), 1e-9,
                "wallbox still gets the full surplus — deadline load didn't eat it");
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
    void ecoBudgetPerCarAndAmps() {
        assertEquals(3250.0, EnergyManagementService.ecoBudgetPerCarW(3000, 4000, 2, 500), 1e-9,
                "(draw 4000 + export 3000 - margin 500) / 2 cars");
        assertEquals(0.0, EnergyManagementService.ecoBudgetPerCarW(-2000, 1000, 1, 500), 1e-9,
                "importing → clamped to 0");
        assertTrue(Double.isNaN(EnergyManagementService.ecoBudgetPerCarW(Double.NaN, 4000, 2, 500)), "NaN grid");
        assertTrue(Double.isNaN(EnergyManagementService.ecoBudgetPerCarW(3000, 4000, 0, 500)), "no active cars");
        assertEquals(5, EnergyManagementService.budgetToAmps(3450, 3, 230), "3450 W / 3 / 230 = 5 A");
        assertEquals(0, EnergyManagementService.budgetToAmps(Double.NaN, 3, 230));
    }

    @Test
    void rampLimitAndHysteresis() {
        assertEquals(11, EnergyManagementService.rampLimitAmps(20, 6, 5), "ramp up capped at +5/tick");
        assertEquals(6, EnergyManagementService.rampLimitAmps(6, 20, 5), "drops are immediate");
        assertEquals(15, EnergyManagementService.applyHysteresisAmps(16, 15, 3), "change < 3 A → keep");
        assertEquals(20, EnergyManagementService.applyHysteresisAmps(20, 15, 3), "change >= 3 A → apply");
    }

    @Test
    void evTargetAmpsPerMode() {
        org.openhab.binding.emsmanager.internal.core.CarSnapshot.Mode OFF = org.openhab.binding.emsmanager.internal.core.CarSnapshot.Mode.OFF;
        org.openhab.binding.emsmanager.internal.core.CarSnapshot.Mode SNEL = org.openhab.binding.emsmanager.internal.core.CarSnapshot.Mode.SNEL;
        org.openhab.binding.emsmanager.internal.core.CarSnapshot.Mode ECO = org.openhab.binding.emsmanager.internal.core.CarSnapshot.Mode.ECO;
        assertEquals(0, EnergyManagementService.evTargetAmps(OFF, 32, 16, 6, 32), "off → 0");
        assertEquals(0, EnergyManagementService.evTargetAmps(ECO, 4, 16, 6, 32), "headroom < min → 0");
        assertEquals(20, EnergyManagementService.evTargetAmps(SNEL, 20, 0, 6, 32), "snel → breaker-limited max");
        assertEquals(32, EnergyManagementService.evTargetAmps(SNEL, 40, 0, 6, 32), "snel capped at 32");
        assertEquals(10, EnergyManagementService.evTargetAmps(ECO, 16, 10, 6, 32), "eco → solar budget");
        assertEquals(16, EnergyManagementService.evTargetAmps(ECO, 16, 40, 6, 32), "eco clamped to headroom");
        assertEquals(0, EnergyManagementService.evTargetAmps(ECO, 16, 4, 6, 32), "eco budget below min → 0");
    }

    @Test
    void evChargeTargetAmpsMatchesLegacyCoordinator() {
        org.openhab.binding.emsmanager.internal.core.CarSnapshot.Mode SNEL = org.openhab.binding.emsmanager.internal.core.CarSnapshot.Mode.SNEL;
        org.openhab.binding.emsmanager.internal.core.CarSnapshot.Mode ECO = org.openhab.binding.emsmanager.internal.core.CarSnapshot.Mode.ECO;
        // SNEL → breaker-limited max, capped at 32.
        assertEquals(20, EnergyManagementService.evChargeTargetAmps(SNEL, 20, Double.NaN, 32, 3, 230, 6, 32),
                "snel → headroom");
        assertEquals(32, EnergyManagementService.evChargeTargetAmps(SNEL, 40, Double.NaN, 32, 3, 230, 6, 32),
                "snel capped at 32");
        // ECO floors at MIN even when the budget affords nothing (the "never auto-pauses" invariant).
        assertEquals(6, EnergyManagementService.evChargeTargetAmps(ECO, 30, 0.0, 32, 3, 230, 6, 32),
                "eco floors at MIN, not 0");
        assertEquals(6, EnergyManagementService.evChargeTargetAmps(ECO, 30, Double.NaN, 32, 3, 230, 6, 32),
                "eco NaN budget falls back to MIN");
        // 11 A * 3 * 230 = 7590 W budget → 11 A.
        assertEquals(11, EnergyManagementService.evChargeTargetAmps(ECO, 30, 7590, 32, 3, 230, 6, 32),
                "eco → budget amps");
        // Clamped by headroom, then by the soft ECO cap.
        assertEquals(8, EnergyManagementService.evChargeTargetAmps(ECO, 8, 20000, 32, 3, 230, 6, 32),
                "eco clamped to headroom");
        assertEquals(16, EnergyManagementService.evChargeTargetAmps(ECO, 30, 20000, 16, 3, 230, 6, 32),
                "eco clamped to soft cap");
    }

    @Test
    void remoteStartBackoffThenWedged() {
        // Charging clears the counter.
        EnergyManagementService.RemoteStartDecision c = EnergyManagementService.remoteStartDecision("Charging", 3, 5,
                20);
        assertEquals(0, c.newAttempts(), "charging clears counter");
        assertFalse(c.emitStart(), "no start while charging");
        assertFalse(c.suppressAmps(), "amps flow while charging");
        // A start-capable status below the cap emits a start each tick and does not suppress amps.
        EnergyManagementService.RemoteStartDecision s = EnergyManagementService.remoteStartDecision("Preparing", 0, 5,
                20);
        assertTrue(s.emitStart(), "start emitted under cap");
        assertEquals(1, s.newAttempts());
        assertFalse(s.suppressAmps());
        // Past the cap → wedged: amps suppressed; a start only every slowRetryTicks.
        EnergyManagementService.RemoteStartDecision w = EnergyManagementService.remoteStartDecision("Preparing", 5, 5,
                20);
        assertTrue(w.wedged(), "wedged past the cap");
        assertTrue(w.suppressAmps(), "wedged suppresses amps");
        assertFalse(w.emitStart(), "attempt 6 is not a slow-retry tick");
        // attempt 25 = cap(5) + 20 → a slow-retry start.
        EnergyManagementService.RemoteStartDecision slow = EnergyManagementService.remoteStartDecision("Preparing", 24,
                5, 20);
        assertTrue(slow.emitStart(), "slow-retry tick emits a start");
        assertTrue(slow.suppressAmps(), "but still suppresses amps");
        // A non-start, non-charging status (Faulted) leaves the counter intact and lets amps proceed.
        EnergyManagementService.RemoteStartDecision f = EnergyManagementService.remoteStartDecision("Faulted", 3, 5,
                20);
        assertEquals(3, f.newAttempts(), "counter intact on other status");
        assertFalse(f.emitStart());
        assertFalse(f.suppressAmps());
    }

    @Test
    void softEcoCapStickyBand() {
        // Heavy import (< -5000) → drop to low cap.
        assertEquals(8, EnergyManagementService.softEcoCapA(-6000, 32, -5000, -3000, 8, 32), "heavy import → 8 A");
        // Recovered (> -3000) → normal cap.
        assertEquals(32, EnergyManagementService.softEcoCapA(-1000, 8, -5000, -3000, 8, 32), "recovered → 32 A");
        // Sticky band (-5000..-3000) → hold whatever we had.
        assertEquals(8, EnergyManagementService.softEcoCapA(-4000, 8, -5000, -3000, 8, 32), "band holds prev (8)");
        assertEquals(32, EnergyManagementService.softEcoCapA(-4000, 32, -5000, -3000, 8, 32), "band holds prev (32)");
        // NaN grid → hold prev.
        assertEquals(8, EnergyManagementService.softEcoCapA(Double.NaN, 8, -5000, -3000, 8, 32), "NaN holds prev");
    }

    @Test
    void batteryTouSchedule() {
        // Night band [2,6): charge at -2000 W.
        assertEquals(-2000.0, EnergyManagementService.batteryTouSetpointW(3, false, 2, 6, 17, 21, -2000, 2000), 1e-9,
                "night → charge");
        // Evening peak [17,21) above reserve: discharge.
        assertEquals(2000.0, EnergyManagementService.batteryTouSetpointW(18, false, 2, 6, 17, 21, -2000, 2000), 1e-9,
                "evening → discharge");
        // Evening peak but below reserve: hold (null).
        assertNull(EnergyManagementService.batteryTouSetpointW(18, true, 2, 6, 17, 21, -2000, 2000),
                "below reserve → hold");
        // Outside both windows: passive.
        assertNull(EnergyManagementService.batteryTouSetpointW(12, false, 2, 6, 17, 21, -2000, 2000),
                "midday → passive");
        // Boundaries: end hours are exclusive.
        assertNull(EnergyManagementService.batteryTouSetpointW(6, false, 2, 6, 17, 21, -2000, 2000),
                "06:00 → night band ended");
        assertNull(EnergyManagementService.batteryTouSetpointW(21, false, 2, 6, 17, 21, -2000, 2000),
                "21:00 → evening band ended");
    }

    @Test
    void capacityTariffWouldExceedMonthlyPeak() {
        // month peak -7000 W import, floor 2500, margin 300.
        assertTrue(EnergyManagementService.wouldExceedCapacityPeak(-7500, -7000, 2500, 300), "projected new peak");
        assertFalse(EnergyManagementService.wouldExceedCapacityPeak(-6800, -7000, 2500, 300), "within current peak");
        assertFalse(EnergyManagementService.wouldExceedCapacityPeak(1500, -7000, 2500, 300), "exporting → no concern");
        // below the billable floor with no month peak yet → only the floor matters
        assertTrue(EnergyManagementService.wouldExceedCapacityPeak(-3000, Double.NaN, 2500, 300), "exceeds 2500 floor");
        assertFalse(EnergyManagementService.wouldExceedCapacityPeak(-2000, Double.NaN, 2500, 300), "under floor");
    }

    @Test
    void capacityGateShedsWhenExceeding() {
        List<EmsAction> plan = List.of(new EmsAction("Boiler", EmsAction.Kind.ONOFF, 1.0, "on"));
        assertEquals(0.0, EnergyManagementService.applyCapacityGate(plan, true).get(0).value(), 1e-9);
        assertSame(plan, EnergyManagementService.applyCapacityGate(plan, false));
    }

    @Test
    void peakShaveEngagesAndRecoversWithHysteresis() {
        // engage only below -15kW; once active, stay active until grid climbs above -10kW
        assertFalse(EnergyManagementService.peakShaveActive(-12000, -15000, -10000, false), "-12kW < 15kW → not yet");
        assertTrue(EnergyManagementService.peakShaveActive(-16000, -15000, -10000, false), "-16kW import → engage");
        assertTrue(EnergyManagementService.peakShaveActive(-12000, -15000, -10000, true), "active, -12kW → hold");
        assertFalse(EnergyManagementService.peakShaveActive(-9000, -15000, -10000, true), "recovered above -10kW");
    }

    @Test
    void peakShaveGateShedsAllLoadWhenActive() {
        List<EmsAction> plan = List.of(new EmsAction("Boiler", EmsAction.Kind.ONOFF, 1.0, "on"),
                new EmsAction("Wallbox", EmsAction.Kind.SET_WATTS, 4000, "on"));
        List<EmsAction> shed = EnergyManagementService.applyPeakShaveGate(plan, true);
        assertEquals(0.0, shed.get(0).value(), 1e-9);
        assertEquals(0.0, shed.get(1).value(), 1e-9);
        assertTrue(shed.get(0).reason().contains("peak-shaving"));
        assertSame(plan, EnergyManagementService.applyPeakShaveGate(plan, false), "inactive → untouched");
    }

    @Test
    void cloudinessAdaptiveThreshold() {
        assertEquals(500.0, EnergyManagementService.cloudinessAdaptiveThresholdW(80, false), 1e-9, "gloomy → grab");
        assertEquals(2500.0, EnergyManagementService.cloudinessAdaptiveThresholdW(10, false), 1e-9, "sunny → wait");
        assertEquals(2000.0, EnergyManagementService.cloudinessAdaptiveThresholdW(50, false), 1e-9, "default");
        assertEquals(3500.0, EnergyManagementService.cloudinessAdaptiveThresholdW(50, true), 1e-9,
                "+1500 below reserve");
        assertEquals(2000.0, EnergyManagementService.cloudinessAdaptiveThresholdW(Double.NaN, false), 1e-9, "unknown");
    }

    @Test
    void solarBoilerHysteresisTransitionsOnly() {
        // on only when above on-threshold AND currently off
        assertEquals(EnergyManagementService.SurplusDecision.ON,
                EnergyManagementService.planSolarBoiler(2600, 2500, -1000, false));
        assertEquals(EnergyManagementService.SurplusDecision.HOLD,
                EnergyManagementService.planSolarBoiler(2600, 2500, -1000, true), "already on → hold");
        // off only when below off-threshold AND currently on
        assertEquals(EnergyManagementService.SurplusDecision.OFF,
                EnergyManagementService.planSolarBoiler(-1200, 2500, -1000, true));
        assertEquals(EnergyManagementService.SurplusDecision.HOLD,
                EnergyManagementService.planSolarBoiler(-1200, 2500, -1000, false), "already off → hold");
        // mid-band and unknown → hold
        assertEquals(EnergyManagementService.SurplusDecision.HOLD,
                EnergyManagementService.planSolarBoiler(500, 2500, -1000, false));
        assertEquals(EnergyManagementService.SurplusDecision.HOLD,
                EnergyManagementService.planSolarBoiler(Double.NaN, 2500, -1000, true));
    }

    @Test
    void breakerGateHoldsLoadWhenHeadroomLow() {
        List<EmsAction> plan = List.of(new EmsAction("Boiler", EmsAction.Kind.ONOFF, 1.0, "on"),
                new EmsAction("Wallbox", EmsAction.Kind.SET_WATTS, 4000, "on"));
        List<EmsAction> gated = EnergyManagementService.applyBreakerGate(plan, 3.0, 6.0);
        assertEquals(0.0, gated.get(0).value(), 1e-9, "boiler forced off — fuse outranks economics");
        assertEquals(0.0, gated.get(1).value(), 1e-9, "wallbox forced to 0 W");
        assertTrue(gated.get(0).reason().contains("breaker safety"));
    }

    @Test
    void breakerGatePassesWhenHeadroomOk() {
        List<EmsAction> plan = List.of(new EmsAction("Boiler", EmsAction.Kind.ONOFF, 1.0, "on"));
        assertSame(plan, EnergyManagementService.applyBreakerGate(plan, 20.0, 6.0), "ample headroom → untouched");
        assertSame(plan, EnergyManagementService.applyBreakerGate(plan, Double.POSITIVE_INFINITY, 6.0));
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

    @Test
    void modeIndexGradesAvailabilityOntoTheModeList() {
        // 4 modes (blocked/normal/encouraged/forced), threshold 1000 W.
        assertEquals(3, EnergyManagementService.modeIndex(4, 2500, 1000, false, false), "strong surplus -> max");
        assertEquals(2, EnergyManagementService.modeIndex(4, 1200, 1000, false, false), "some surplus -> encouraged");
        assertEquals(2, EnergyManagementService.modeIndex(4, 0, 1000, true, false), "deadline pressure -> encouraged");
        assertEquals(1, EnergyManagementService.modeIndex(4, 0, 1000, false, false), "quiet -> normal");
        assertEquals(0, EnergyManagementService.modeIndex(4, 0, 1000, false, true), "expensive + nothing -> blocked");
        // 3 modes: both surplus grades land on the top ("encouraged") mode.
        assertEquals(2, EnergyManagementService.modeIndex(3, 2500, 1000, false, false));
        assertEquals(2, EnergyManagementService.modeIndex(3, 1200, 1000, false, false));
        assertEquals(1, EnergyManagementService.modeIndex(3, 0, 1000, false, false));
        // 2 modes degrade to allow/deny.
        assertEquals(1, EnergyManagementService.modeIndex(2, 0, 1000, false, false));
        assertEquals(0, EnergyManagementService.modeIndex(2, 0, 1000, false, true));
    }

    @Test
    void expensiveHourIsTopOfTheDayAndFlatIsNever() {
        double[] sched = new double[24];
        for (int h = 0; h < 24; h++) {
            sched[h] = 0.20;
        }
        sched[18] = 0.45;
        sched[19] = 0.50;
        sched[20] = 0.40;
        assertTrue(EnergyManagementService.isExpensiveHour(19, sched), "peak hour is expensive");
        assertTrue(EnergyManagementService.isExpensiveHour(18, sched), "top-4 hour is expensive");
        assertFalse(EnergyManagementService.isExpensiveHour(12, sched), "cheap hour is not");
        double[] flat = new double[24];
        for (int h = 0; h < 24; h++) {
            flat[h] = 0.30;
        }
        assertFalse(EnergyManagementService.isExpensiveHour(19, flat), "flat tariff has no expensive hours");
        assertFalse(EnergyManagementService.isExpensiveHour(19, new double[0]), "no schedule -> disabled");
    }

    @Test
    void batchStartsInCheapestContiguousWindowOrForcedAtLatestStart() {
        double[] sched = new double[24];
        for (int h = 0; h < 24; h++) {
            sched[h] = 0.30;
        }
        sched[13] = 0.10;
        sched[14] = 0.10; // cheapest contiguous 2h block: 13-15
        // 2h program, deadline 17: at 10:00 wait, at 13:00 start (cheapest window), at 15:00 forced.
        assertFalse(EnergyManagementService.batchStartNow(10, 17, 2.0, sched), "10:00 -> wait for cheap block");
        assertTrue(EnergyManagementService.batchStartNow(13, 17, 2.0, sched), "13:00 -> cheapest window, start");
        assertTrue(EnergyManagementService.batchStartNow(15, 17, 2.0, sched), "15:00 -> latest start, forced");
        // No prices: wait until the latest start.
        double[] none = new double[0];
        assertFalse(EnergyManagementService.batchStartNow(10, 17, 2.0, none), "no prices -> wait");
        assertTrue(EnergyManagementService.batchStartNow(15, 17, 2.0, none), "no prices -> forced at latest start");
        // Deadline hour itself: too late to fit a run.
        assertFalse(EnergyManagementService.batchStartNow(17, 17, 2.0, sched), "deadline hour -> next cycle");
        // No deadline: never price/deadline-started (only surplus can, in planConsumers).
        assertFalse(EnergyManagementService.batchStartNow(10, -1, 2.0, sched));
    }

    @Test
    void planConsumersHandlesModeAndBatchClasses() {
        EnergyConsumer wp = new EnergyConsumer("WP",
                new PowerProfile.ModeControllable("WP", java.util.List.of("blocked", "normal", "encouraged", "forced")),
                0, -1);
        EnergyConsumer dish = new EnergyConsumer("DW", new PowerProfile.Batch("DW", 2000, 2.0), 0, 17);
        // Strong surplus: heat pump forced, dishwasher surplus-started (and it consumed budget).
        List<EmsAction> a = EnergyManagementService.planConsumers(List.of(wp, dish), 4500, 1000, 10, new double[0]);
        assertEquals(EmsAction.Kind.SET_MODE, a.get(0).kind());
        assertEquals("forced", a.get(0).stringValue());
        assertEquals(3.0, a.get(0).value(), 1e-9, "value carries the mode index");
        assertEquals(EmsAction.Kind.ONOFF, a.get(1).kind());
        assertEquals(1.0, a.get(1).value(), 1e-9, "batch surplus start");
        // No surplus, window not due: heat pump normal, dishwasher HOLD (never OFF).
        List<EmsAction> b = EnergyManagementService.planConsumers(List.of(wp, dish), 0, 1000, 10, new double[0]);
        assertEquals("normal", b.get(0).stringValue());
        assertEquals(EmsAction.Kind.HOLD, b.get(1).kind(), "waiting batch holds, never turns OFF");
        // Latest start reached: dishwasher started even without surplus.
        List<EmsAction> c = EnergyManagementService.planConsumers(List.of(dish), 0, 1000, 15, new double[0]);
        assertEquals(1.0, c.get(0).value(), 1e-9, "forced start at latest start");
        assertTrue(c.get(0).reason().contains("batch"));
    }

    @Test
    void batchShapeAlignsHeavyHoursWithCheapOnes() {
        // 2h program, heavy in its FIRST hour (shape 1.0, 0.1). Cheap hours at 10:00 and 12:00.
        double[] sched = new double[24];
        for (int h = 0; h < 24; h++) {
            sched[h] = 0.30;
        }
        sched[10] = 0.05;
        sched[12] = 0.05;
        java.util.List<Double> shape = java.util.List.of(1.0, 0.1);
        // Rectangular: every 2h window costs the same (0.35) -> tie -> earliest -> start at 9.
        assertTrue(EnergyManagementService.batchStartNow(9, 14, 2.0, sched), "rectangular: tie -> earliest, start");
        // Shaped: waiting one hour puts the HEAVY hour on cheap 10:00 -> do NOT start at 9...
        assertFalse(EnergyManagementService.batchStartNow(9, 14, 2.0, sched, shape),
                "shaped: wait so the heavy hour lands on the cheap hour");
        // ...and start at 10 (heavy hour on 0.05).
        assertTrue(EnergyManagementService.batchStartNow(10, 14, 2.0, sched, shape), "shaped: start at 10");
        // A non-empty shape defines the runtime (runtimeHours ignored): span<=r forces the start.
        assertTrue(EnergyManagementService.batchStartNow(12, 14, 99.0, sched, shape),
                "shape length 2 -> latest start at 12 for deadline 14");
    }

    @Test
    void singlePhaseBudgetMathIsCorrectWhenParamsAreThreaded() {
        // 1×230 V: 1600 W → 6 A, 3680 W → 16 A (the old hardcoded 3-phase math would say 2 A / 5 A).
        assertEquals(6, EnergyManagementService.budgetToAmps(1600, 1, 230));
        assertEquals(16, EnergyManagementService.budgetToAmps(3680, 1, 230));
        org.openhab.binding.emsmanager.internal.core.CarSnapshot.Mode ECO = org.openhab.binding.emsmanager.internal.core.CarSnapshot.Mode.ECO;
        assertEquals(16, EnergyManagementService.evChargeTargetAmps(ECO, 30, 3680, 32, 1, 230, 6, 32),
                "single-phase eco budget lands on 16 A");
    }

    @Test
    void energyLevelGradesAvailability() {
        assertEquals(3, EnergyManagementService.energyLevel(2500, 1000, false, false), "strong surplus -> maximum");
        assertEquals(2, EnergyManagementService.energyLevel(1200, 1000, false, false), "surplus -> encouraged");
        assertEquals(2, EnergyManagementService.energyLevel(0, 1000, true, false), "deadline pressure -> encouraged");
        assertEquals(1, EnergyManagementService.energyLevel(0, 1000, false, false), "quiet -> normal");
        assertEquals(0, EnergyManagementService.energyLevel(0, 1000, false, true), "expensive + nothing -> restricted");
    }

    @Test
    void switchProtectionConstraintsFullSymmetry() {
        // min-runtime holds ON against a planner OFF.
        var sc = EnergyManagementService.constrainOnOff(false, true, 5, 10, 0, 0, 0);
        assertTrue(sc.on() && sc.overridden(), "min-runtime holds ON");
        // max-runtime forces OFF despite a planner ON.
        sc = EnergyManagementService.constrainOnOff(true, true, 120, 0, 60, 0, 0);
        assertTrue(!sc.on() && sc.overridden(), "max-runtime forces OFF");
        // cooldown holds OFF against a planner ON.
        sc = EnergyManagementService.constrainOnOff(true, false, 3, 0, 0, 15, 0);
        assertTrue(!sc.on() && sc.overridden(), "cooldown holds OFF");
        // the fridge: max-off forces ON even though the planner wants OFF (duty-cycle guarantee).
        sc = EnergyManagementService.constrainOnOff(false, false, 46, 15, 0, 0, 45);
        assertTrue(sc.on() && sc.overridden(), "max-off forces ON (fridge)");
        // unknown state age -> untouched.
        sc = EnergyManagementService.constrainOnOff(true, false, Double.NaN, 10, 10, 10, 10);
        assertTrue(sc.on() && !sc.overridden(), "NaN age -> planner decision stands");
        // within constraints -> untouched.
        sc = EnergyManagementService.constrainOnOff(true, true, 30, 10, 60, 0, 0);
        assertTrue(sc.on() && !sc.overridden());
    }

    @Test
    void cheapestHourLiftsLevelAndPriorityOrdersDeterministically() {
        double[] sched = new double[24];
        for (int h = 0; h < 24; h++) {
            sched[h] = 0.30;
        }
        sched[3] = 0.05;
        assertTrue(EnergyManagementService.isCheapestHour(3, sched));
        assertFalse(EnergyManagementService.isCheapestHour(12, sched));
        assertEquals(2, EnergyManagementService.energyLevel(0, 1000, false, false, true),
                "cheapest hour lifts quiet to encouraged");
        assertEquals(3, EnergyManagementService.energyLevel(2500, 1000, false, false, true),
                "strong surplus stays maximum");
        EnergyConsumer a = new EnergyConsumer("B_second", new PowerProfile.Simple("B_second"), 0, -1, null, null, 50);
        EnergyConsumer b = new EnergyConsumer("A_first", new PowerProfile.Simple("A_first"), 0, -1, null, null, 10);
        EnergyConsumer c = new EnergyConsumer("C_tie", new PowerProfile.Simple("C_tie"), 0, -1, null, null, 50);
        List<EnergyConsumer> sorted = EnergyManagementService.sortByPriority(List.of(a, b, c));
        assertEquals("A_first", sorted.get(0).id());
        assertEquals("B_second", sorted.get(1).id());
        assertEquals("C_tie", sorted.get(2).id());
    }

    @Test
    void runAtLevelAndPerConsumerThreshold() {
        EnergyConsumer pool = new EnergyConsumer("Pool", new PowerProfile.Simple("Pool", Double.NaN, 2, 0, 0, 0, 0), 0,
                -1);
        EnergyConsumer boiler = new EnergyConsumer("Boiler", new PowerProfile.Simple("Boiler", 2000, 0, 0, 0, 0, 0), 0,
                -1);
        List<EmsAction> a = EnergyManagementService.planConsumers(List.of(pool, boiler), 2500, 1000, 12, new double[0]);
        assertEquals(1.0, a.get(0).value(), 1e-9, "level 3 >= 2 -> pool on");
        assertEquals(1.0, a.get(1).value(), 1e-9, "2500 >= per-consumer 2000 -> boiler on");
        List<EmsAction> b = EnergyManagementService.planConsumers(List.of(pool, boiler), 1500, 1000, 12, new double[0]);
        assertEquals(1.0, b.get(0).value(), 1e-9, "level 2 -> pool on");
        assertEquals(0.0, b.get(1).value(), 1e-9, "per-consumer threshold 2000 beats global 1000");
        List<EmsAction> c = EnergyManagementService.planConsumers(List.of(pool), 0, 1000, 12, new double[0]);
        assertEquals(0.0, c.get(0).value(), 1e-9, "level 1 < 2 -> pool off");
        assertTrue(c.get(0).reason().contains("level gate"));
        EnergyConsumer never = new EnergyConsumer("X", new PowerProfile.Simple("X", Double.NaN, 4, 0, 0, 0, 0), 0, -1);
        assertEquals(0.0,
                EnergyManagementService.planConsumers(List.of(never), 9000, 1000, 12, new double[0]).get(0).value(),
                1e-9, "runAtLevel 4 -> never on");
    }

    @Test
    void percentileWindowsAndSeasonalPresets() {
        double[] ramp = new double[24];
        for (int h = 0; h < 24; h++) {
            ramp[h] = 0.10 + h * 0.01; // strictly increasing: rank == hour
        }
        LevelWindows w = LevelWindows.DEFAULT;
        assertEquals(3, EnergyManagementService.pricePercentileLevel(0, ramp, w), "cheapest-4 -> maximum");
        assertEquals(3, EnergyManagementService.pricePercentileLevel(3, ramp, w));
        assertEquals(2, EnergyManagementService.pricePercentileLevel(5, ramp, w), "rank 5 -> encouraged");
        assertEquals(1, EnergyManagementService.pricePercentileLevel(12, ramp, w), "mid-day -> normal");
        assertEquals(0, EnergyManagementService.pricePercentileLevel(22, ramp, w), "top-4 expensive -> restricted");
        double[] flat = new double[24];
        java.util.Arrays.fill(flat, 0.30);
        assertEquals(1, EnergyManagementService.pricePercentileLevel(7, flat, w), "flat schedule -> no signal");
        assertEquals(1, EnergyManagementService.pricePercentileLevel(7, new double[0], w), "no schedule -> no signal");
        // price alone can now grade maximum (storm.house semantics); surplus still dominates.
        assertEquals(3, EnergyManagementService.energyLevel(0, 1000, false, 3), "percentile-cheapest -> maximum");
        assertEquals(0, EnergyManagementService.energyLevel(0, 1000, false, 0), "restricted + nothing spare");
        assertEquals(2, EnergyManagementService.energyLevel(1200, 1000, false, 0), "surplus beats restricted");
        // seasonal presets: documented anchors — winter block max 4 h, transition 8 h.
        assertEquals(new LevelWindows(8, 12, 4), LevelWindows.seasonal(12), "winter");
        assertEquals(new LevelWindows(8, 12, 4), LevelWindows.seasonal(2), "winter incl feb");
        assertEquals(new LevelWindows(6, 10, 8), LevelWindows.seasonal(4), "transition");
        assertEquals(new LevelWindows(4, 8, 8), LevelWindows.seasonal(7), "summer");
        // sanitize: cheap window can never be narrower than the cheapest window.
        assertEquals(new LevelWindows(6, 6, 4), new LevelWindows(6, 2, 4).sanitized());
    }

    @Test
    void deadlineWinsOverLevelGateAndControlItemNaming() {
        double[] sched = new double[24];
        java.util.Arrays.fill(sched, 0.30);
        sched[2] = 0.05; // hour 2 is the cheapest
        // A demand+deadline consumer (2 kWh by 07:00) that is ALSO level-gated at 3: the deadline
        // must still fire it (a guarantee beats the interactive level preference).
        EnergyConsumer boiler = new EnergyConsumer("Boiler", new PowerProfile.Simple("Boiler", 2000, 3, 0, 0, 0, 0),
                2.0, 7, null, null, 100);
        List<EmsAction> onDeadline = EnergyManagementService.planConsumers(List.of(boiler), 0, 1000, 2, sched);
        assertEquals(1.0, onDeadline.get(0).value(), 1e-9, "deadline forces on despite the level gate");
        assertTrue(onDeadline.get(0).reason().contains("deadline"));
        // Away from the deadline window (hour 12, no surplus) the level gate still holds it off.
        List<EmsAction> gated = EnergyManagementService.planConsumers(List.of(boiler), 0, 1000, 12, sched);
        assertEquals(0.0, gated.get(0).value(), 1e-9, "no deadline pressure -> level gate holds off");
        assertEquals("Boiler_EmsLevel", EnergyManagementService.levelControlItem("Boiler"));
    }
}

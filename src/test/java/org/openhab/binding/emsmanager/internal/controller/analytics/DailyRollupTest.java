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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DailyRollup} — the per-day increment logic behind the
 * long-term statistics tier. Guards against the original midnight-rollover
 * bug (appended the post-reset value) and the restart re-rollover bug.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class DailyRollupTest {

    /** A daily-reset kWh counter: 0 -> 24 through the day, resets to 0 at midnight. */
    @Test
    void dailyResetCounterCapturesFullDayTotal() {
        DailyRollup r = new DailyRollup(365, true);
        // Day 1: counter climbs to 24 kWh.
        r.observe(0.0);
        r.observe(10.0);
        r.observe(24.0);
        assertEquals(24.0, r.dayAmount(), 1e-9);
        // Midnight: counter has just reset to 0 -> rollover must record 24, not 0.
        double yesterday = r.rollover(0.0);
        assertEquals(24.0, yesterday, 1e-9, "rollover must use the pre-reset value");
        assertEquals(24.0, r.yesterday(), 1e-9);
        // Day 2 starts fresh.
        assertEquals(0.0, r.dayAmount(), 1e-9);
        r.observe(5.0);
        assertEquals(5.0, r.dayAmount(), 1e-9);
    }

    /** A monthly counter: accumulates across days, rollover records the day's delta. */
    @Test
    void monthlyCounterRecordsDailyDelta() {
        DailyRollup r = new DailyRollup(365, false);
        r.observe(0.0); // month start, day 1
        r.observe(3.0); // €3 spent day 1
        assertEquals(3.0, r.dayAmount(), 1e-9);
        r.rollover(3.0); // midnight: counter is still 3 (monthly, no reset)
        assertEquals(3.0, r.yesterday(), 1e-9);
        // Day 2: counter climbs 3 -> 8 (€5 that day).
        r.observe(3.0);
        r.observe(8.0);
        assertEquals(5.0, r.dayAmount(), 1e-9, "day amount is the delta, not the month total");
        r.rollover(8.0);
        assertEquals(5.0, r.yesterday(), 1e-9);
        assertEquals(8.0, r.sumLast(2), 1e-9, "sum of daily amounts = 3 + 5");
    }

    /** Last day of a month records its delta; month reset can't produce a negative amount. */
    @Test
    void monthBoundaryRecordsDeltaAndClamps() {
        DailyRollup r = new DailyRollup(365, false);
        // Last day of the month: month-to-date starts at 25, grows to 28 (€3 that day).
        r.observe(25.0);
        r.observe(28.0);
        assertEquals(3.0, r.dayAmount(), 1e-9);
        double yend = r.rollover(0.0); // midnight = month boundary, counter reset to 0
        assertEquals(3.0, yend, 1e-9, "last day of month recorded its own delta");
        // New month, day 1.
        r.observe(0.0);
        r.observe(2.0);
        assertEquals(2.0, r.dayAmount(), 1e-9);
    }

    /** A mid-day counter reset (period boundary) clamps the running amount to 0, never negative. */
    @Test
    void counterResetClampsToZero() {
        DailyRollup r = new DailyRollup(365, false);
        r.observe(25.0); // baseline anchors at 25
        r.observe(0.0); // counter reset back to 0
        assertEquals(0.0, r.dayAmount(), 1e-9); // max(0, 0 - 25)
    }

    /** sumLast respects the requested window and ring contents. */
    @Test
    void sumLastWindow() {
        DailyRollup r = new DailyRollup(365, true);
        for (double v : new double[] { 1, 2, 3, 4, 5 }) {
            r.observe(0.0);
            r.observe(v);
            r.rollover(0.0);
        }
        assertEquals(5.0 + 4.0 + 3.0, r.sumLast(3), 1e-9);
        assertEquals(15.0, r.sumLast(99), 1e-9);
        assertEquals(0.0, r.sumLast(0), 1e-9);
    }

    /** Ring is trimmed to the configured horizon. */
    @Test
    void ringTrimsToHorizon() {
        DailyRollup r = new DailyRollup(3, true);
        for (double v : new double[] { 1, 2, 3, 4, 5 }) {
            r.observe(0.0);
            r.observe(v);
            r.rollover(0.0);
        }
        assertEquals(3, r.size());
        assertEquals(4.0 + 5.0, r.sumLast(2), 1e-9);
    }

    /** restore() round-trips ring + baseline + last for persistence. */
    @Test
    void restoreReinstatesState() {
        DailyRollup r = new DailyRollup(365, false);
        r.restore(java.util.List.of(10.0, 20.0), 100.0, 105.0);
        assertEquals(2, r.size());
        assertEquals(20.0, r.yesterday(), 1e-9);
        assertEquals(5.0, r.dayAmount(), 1e-9); // 105 - 100
        assertEquals(30.0, r.sumLast(2), 1e-9);
    }
}

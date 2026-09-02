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
import static org.mockito.Mockito.mock;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.controller.dispatch.HeatPumpOptimizerController.PlanMemo;
import org.openhab.binding.emsmanager.internal.controller.peak.HardPeakShavingController;
import org.openhab.binding.emsmanager.internal.heatpump.ThermalPlanner;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.thing.ThingRegistry;

/**
 * The predictive half of {@link HeatPumpOptimizerController}: the DP planner counts hours from now
 * while the tariff schedule counts hours of the day, and its verdict has to hold between replans.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class HeatPumpOptimizerControllerTest {

    private static final double R = 0.005;
    private static final double C = 5_000_000;
    private static final double P_HP = 3000;
    private static final double COP = 4.0;

    private HeatPumpOptimizerController controller() {
        return new HeatPumpOptimizerController(mock(ThingRegistry.class), mock(ItemRegistry.class),
                new HardPeakShavingController(false, false));
    }

    @Test
    void scheduleIsRotatedSoIndexZeroIsTheCurrentHour() {
        double[] byHour = new double[24];
        for (int h = 0; h < 24; h++) {
            byHour[h] = h;
        }

        double[] fromNow = HeatPumpOptimizerController.rotateToNow(byHour, 22);

        assertEquals(22, fromNow[0], 1e-9);
        assertEquals(23, fromNow[1], 1e-9);
        assertEquals(0, fromNow[2], 1e-9, "past midnight the schedule wraps to today's same hour");
        assertEquals(21, fromNow[23], 1e-9);
    }

    /**
     * 22:00, house at 22 °C, 5 °C outside, and 23:00 is the one cheap hour of the day. Priced from
     * now the planner waits an hour for it; priced from midnight it sees 23 expensive hours first and
     * heats at once. The reference plan is computed with the rotated schedule and the test first
     * proves the two orderings really differ here.
     */
    @Test
    void plannerPricesTheHoursFromNowNotFromMidnight() {
        double[] tariff = new double[24];
        Arrays.fill(tariff, 0.50);
        tariff[23] = 0.05;
        double[] tOut = new double[24];
        Arrays.fill(tOut, 5);
        ZonedDateTime tenPm = ZonedDateTime.of(2026, 1, 15, 22, 0, 0, 0, ZoneId.systemDefault());
        ThermalPlanner.Plan fromNow = ThermalPlanner.plan(22.0, 20.0, 0.5, tOut,
                HeatPumpOptimizerController.rotateToNow(tariff, 22), R, C, P_HP, COP, false);
        ThermalPlanner.Plan fromMidnight = ThermalPlanner.plan(22.0, 20.0, 0.5, tOut, tariff, R, C, P_HP, COP, false);
        assertNotEquals(fromMidnight.action()[0], fromNow.action()[0],
                "precondition: the two orderings must decide differently for this test to prove anything");

        PlanMemo memo = HeatPumpOptimizerController.planFromNow(tenPm, 22.0, 20.0, 0.5, tOut, tariff, R, C, P_HP, COP,
                false);

        assertFalse(memo.preheatNow(), "the cheap hour is the next one, so wait for it");
        assertEquals(tenPm.plusHours(1), memo.preheatAt(), "pre-heating starts at the cheap 23:00 hour");
        assertEquals(fromNow.totalCost(), memo.planCost(), 1e-9);
    }

    @Test
    void theLastPlanStandsBetweenReplans() {
        HeatPumpOptimizerController c = controller();
        AtomicInteger plans = new AtomicInteger();
        PlanMemo heat = new PlanMemo(true, null, 1.0);

        PlanMemo first = c.planOrLatched("hp", 0L, true, () -> {
            plans.incrementAndGet();
            return heat;
        });
        PlanMemo fiveSecondsLater = c.planOrLatched("hp", 5_000L, true, () -> {
            plans.incrementAndGet();
            return PlanMemo.NONE;
        });

        assertTrue(first.preheatNow());
        assertTrue(fiveSecondsLater.preheatNow(), "a BOOST verdict must not blink off on the next tick");
        assertEquals(1, plans.get(), "no replan inside the interval");
    }

    @Test
    void replansOnceTheIntervalHasPassed() {
        HeatPumpOptimizerController c = controller();
        c.planOrLatched("hp", 0L, true, () -> new PlanMemo(true, null, 1.0));

        PlanMemo later = c.planOrLatched("hp", HeatPumpOptimizerController.REPLAN_INTERVAL_MS + 1, true,
                () -> PlanMemo.NONE);

        assertFalse(later.preheatNow(), "after the interval the fresh plan replaces the held one");
    }

    @Test
    void anInvalidModelDropsTheHeldPlan() {
        HeatPumpOptimizerController c = controller();
        c.planOrLatched("hp", 0L, true, () -> new PlanMemo(true, null, 1.0));

        PlanMemo invalid = c.planOrLatched("hp", 5_000L, false, () -> fail("must not plan on an invalid model"));

        assertFalse(invalid.preheatNow());
    }
}

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
package org.openhab.binding.emsmanager.internal.controller.ev;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.ems.EvElectrical;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;

/**
 * The "cheapest" cost estimate must price the hours between now and departure, and the plan items
 * must be addressable under a site's own naming.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class EvChargingPlanControllerTest {

    /** Cheap until noon, expensive after. */
    private static double[] dayNight() {
        double[] sched = new double[24];
        Arrays.fill(sched, 0, 12, 0.10);
        Arrays.fill(sched, 12, 24, 0.50);
        return sched;
    }

    /** 20:00, leaving at 23:00, 7 kWh short: every hour left is expensive, however cheap the morning was. */
    @Test
    void cheapestPricesTheHoursUntilDepartureNotTheMorningThatIsGone() {
        double cost = EvChargingPlanController.cheapestCostEstimate(dayNight(), 20, 3.0, 7.0, 0.99);

        assertEquals(7.0 * 0.50, cost, 1e-9);
    }

    /** 23:00, leaving at 02:00: the window wraps past midnight into the cheap hours. */
    @Test
    void cheapestWrapsPastMidnightUsingTodaysSchedule() {
        double cost = EvChargingPlanController.cheapestCostEstimate(dayNight(), 23, 3.0, 7.0, 0.99);

        assertEquals(7.0 * 0.10, cost, 1e-9, "00:00 and 01:00 are cheap on today's schedule");
    }

    @Test
    void withoutAScheduleTheCurrentPriceIsUsed() {
        assertEquals(7.0 * 0.30, EvChargingPlanController.cheapestCostEstimate(new double[0], 20, 3.0, 7.0, 0.30),
                1e-9);
        assertEquals(7.0 * 0.30, EvChargingPlanController.cheapestCostEstimate(dayNight(), 20, 0.5, 7.0, 0.30), 1e-9,
                "less than an hour left: nothing to pick from");
    }

    private ItemRegistry emptyRegistry() throws ItemNotFoundException {
        ItemRegistry registry = mock(ItemRegistry.class);
        when(registry.getItem(anyString())).thenThrow(new ItemNotFoundException("none"));
        return registry;
    }

    private EnergyContext ctxWithCar1() {
        CarSnapshot car = new CarSnapshot("car1", CarSnapshot.Mode.ECO, true, "Charging", 0, 0, 0, 0, 6.0, false);
        return new EnergyContext(Instant.now(), 0, 0, 0, 0, 0, 50, 30, false, 0, EnergyContext.Mode.BALANCED,
                Map.of("car1", car), 0, 0, 0, true, false, false, true, 0, 0, false, 0, 0, 60_000L, 0.30, new double[0],
                Double.NaN, Double.NaN, false);
    }

    @Test
    void planItemsFollowTheConfiguredPrefix() throws ItemNotFoundException {
        ItemRegistry registry = emptyRegistry();
        new EvChargingPlanController(mock(EventPublisher.class), registry, EvElectrical.DEFAULT, "Auto%d_Laadplan_")
                .evaluate(ctxWithCar1());

        verify(registry).getItem("Auto1_Laadplan_Enabled");
        verify(registry, never()).getItem("EVSE1_Plan_Enabled");
    }

    @Test
    void theOldConstructorKeepsTheOldItemNames() throws ItemNotFoundException {
        ItemRegistry registry = emptyRegistry();
        new EvChargingPlanController(mock(EventPublisher.class), registry, EvElectrical.DEFAULT)
                .evaluate(ctxWithCar1());

        verify(registry).getItem("EVSE1_Plan_Enabled");
    }

    @Test
    void theCheapestHoursArePlannedAsWindowsInTheOrderTheyCome() {
        // from 20:00, 6 hours left, prices dearest at 21 and 22: the two cheapest of the six are 20 and 23
        double[] sched = new double[24];
        java.util.Arrays.fill(sched, 0.30);
        sched[21] = 0.50;
        sched[22] = 0.50;
        sched[20] = 0.10;
        sched[23] = 0.12;
        int[] offsets = EvChargingPlanController.cheapestHours(sched, 20, 6.0, 14.0);
        assertArrayEquals(new int[] { 0, 3 }, offsets, "14 kWh at 7 kW is two hours: 20:00 and 23:00");
    }

    @Test
    void nothingNeededMeansNoWindows() {
        assertEquals(0, EvChargingPlanController.cheapestHours(new double[24], 8, 6.0, 0.0).length);
        assertEquals(0, EvChargingPlanController.cheapestHours(null, 8, 6.0, 10.0).length);
    }
}

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
package org.openhab.binding.emsmanager.internal.heatpump;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ThermalPlanner}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class ThermalPlannerTest {

    private static final double R = 0.005; // K/W — well-insulated home
    private static final double C = 5_000_000; // J/K
    private static final double P_HP = 3000; // W heat-pump electric
    private static final double COP = 4.0;

    @Test
    void cheapHoursPreferred() {
        // 24 hours: first 12 cheap (€0.10), last 12 expensive (€0.50).
        // T_out constant at 5 °C; target 20 °C with 0.5 °C deadband.
        // The planner should prefer to heat in the cheap window.
        double[] tOut = new double[24];
        double[] tariff = new double[24];
        java.util.Arrays.fill(tOut, 5);
        for (int i = 0; i < 12; i++) {
            tariff[i] = 0.10;
        }
        for (int i = 12; i < 24; i++) {
            tariff[i] = 0.50;
        }

        var plan = ThermalPlanner.plan(20.0, 20.0, 0.5, tOut, tariff, R, C, P_HP, COP);

        // Count heating hours in each half
        int heatCheap = 0, heatExp = 0;
        for (int h = 0; h < 12; h++) {
            heatCheap += plan.action()[h];
        }
        for (int h = 12; h < 24; h++) {
            heatExp += plan.action()[h];
        }
        assertTrue(heatCheap >= heatExp,
                "should heat at least as much in cheap window; cheap=" + heatCheap + " exp=" + heatExp);
    }

    @Test
    void warmHouseColdTariffNoHeatNeeded() {
        // Starting +3 °C above target with mild outdoor → coasts comfortably.
        double[] tOut = new double[24];
        double[] tariff = new double[24];
        java.util.Arrays.fill(tOut, 15);
        java.util.Arrays.fill(tariff, 0.30);
        var plan = ThermalPlanner.plan(23.0, 20.0, 0.5, tOut, tariff, R, C, P_HP, COP);
        int totalHeat = 0;
        for (int a : plan.action()) {
            totalHeat += a;
        }
        assertTrue(totalHeat < 5, "warm house in mild weather shouldn't need much heating; got " + totalHeat);
    }

    @Test
    void emptyInputsReturnEmptyPlan() {
        var plan = ThermalPlanner.plan(20.0, 20.0, 0.5, new double[0], new double[0], R, C, P_HP, COP);
        assertEquals(0, plan.action().length);
    }

    @Test
    void invalidModelParamsReturnEmpty() {
        var plan = ThermalPlanner.plan(20.0, 20.0, 0.5, new double[24], new double[24], Double.NaN, C, P_HP, COP);
        assertEquals(0, plan.action().length);
    }

    @Test
    void planCostNonNegative() {
        double[] tOut = new double[24];
        double[] tariff = new double[24];
        java.util.Arrays.fill(tOut, 0); // cold outside, will require heating
        java.util.Arrays.fill(tariff, 0.30);
        var plan = ThermalPlanner.plan(19.0, 20.0, 0.5, tOut, tariff, R, C, P_HP, COP);
        assertTrue(plan.totalCost() >= 0, "plan cost must be non-negative");
    }
}

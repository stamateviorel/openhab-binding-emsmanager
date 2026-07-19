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

        var plan = ThermalPlanner.plan(20.0, 20.0, 0.5, tOut, tariff, R, C, P_HP, COP, false);

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
        var plan = ThermalPlanner.plan(23.0, 20.0, 0.5, tOut, tariff, R, C, P_HP, COP, false);
        int totalHeat = 0;
        for (int a : plan.action()) {
            totalHeat += a;
        }
        assertTrue(totalHeat < 5, "warm house in mild weather shouldn't need much heating; got " + totalHeat);
    }

    @Test
    void emptyInputsReturnEmptyPlan() {
        var plan = ThermalPlanner.plan(20.0, 20.0, 0.5, new double[0], new double[0], R, C, P_HP, COP, false);
        assertEquals(0, plan.action().length);
    }

    @Test
    void invalidModelParamsReturnEmpty() {
        var plan = ThermalPlanner.plan(20.0, 20.0, 0.5, new double[24], new double[24], Double.NaN, C, P_HP, COP,
                false);
        assertEquals(0, plan.action().length);
    }

    @Test
    void planCostNonNegative() {
        double[] tOut = new double[24];
        double[] tariff = new double[24];
        java.util.Arrays.fill(tOut, 0); // cold outside, will require heating
        java.util.Arrays.fill(tariff, 0.30);
        var plan = ThermalPlanner.plan(19.0, 20.0, 0.5, tOut, tariff, R, C, P_HP, COP, false);
        assertTrue(plan.totalCost() >= 0, "plan cost must be non-negative");
    }

    @Test
    void coolingPrefersCheapHours() {
        // Cooling: first 12 h cheap, last 12 expensive; hot outside; target 24 °C.
        // The planner should pre-cool in the cheap window.
        double[] tOut = new double[24];
        double[] tariff = new double[24];
        java.util.Arrays.fill(tOut, 32);
        for (int i = 0; i < 12; i++) {
            tariff[i] = 0.10;
        }
        for (int i = 12; i < 24; i++) {
            tariff[i] = 0.50;
        }
        var plan = ThermalPlanner.plan(24.0, 24.0, 0.5, tOut, tariff, R, C, P_HP, COP, true);
        int coolCheap = 0, coolExp = 0;
        for (int h = 0; h < 12; h++) {
            coolCheap += plan.action()[h];
        }
        for (int h = 12; h < 24; h++) {
            coolExp += plan.action()[h];
        }
        assertTrue(coolCheap >= coolExp,
                "cooling should run at least as much in the cheap window; cheap=" + coolCheap + " exp=" + coolExp);
    }

    @Test
    void coolingRunsWhenHot() {
        double[] tOut = new double[24];
        double[] tariff = new double[24];
        java.util.Arrays.fill(tOut, 35); // hot → cooling needed to hold target
        java.util.Arrays.fill(tariff, 0.30);
        var plan = ThermalPlanner.plan(24.0, 24.0, 0.5, tOut, tariff, R, C, P_HP, COP, true);
        int totalCool = 0;
        for (int a : plan.action()) {
            totalCool += a;
        }
        assertTrue(totalCool > 0, "cooling should run when it's hot outside; got " + totalCool);
    }
}

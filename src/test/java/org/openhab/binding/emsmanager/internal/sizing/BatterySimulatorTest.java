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
package org.openhab.binding.emsmanager.internal.sizing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BatterySimulator}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class BatterySimulatorTest {

    private static final double TARIFF = 0.30;
    private static final double INJECTION = 0.05;

    /** Helper: build a 24-h sample series, one sample per hour, with custom gen + load curves. */
    private static List<BatterySimulator.Sample> day(double[] genW, double[] loadW) {
        List<BatterySimulator.Sample> out = new ArrayList<>(genW.length);
        long t0 = 1_700_000_000_000L; // arbitrary epoch
        for (int i = 0; i < genW.length; i++) {
            out.add(new BatterySimulator.Sample(t0 + i * 3_600_000L, genW[i], loadW[i], TARIFF, INJECTION));
        }
        return out;
    }

    @Test
    void noBatteryMeansAllSurplusExports() {
        // 25 samples = 24 intervals of 1 hour each → 24 kWh of energy
        double[] gen = new double[25];
        double[] load = new double[25];
        java.util.Arrays.fill(gen, 1000);
        java.util.Arrays.fill(load, 0);
        var result = BatterySimulator.simulate(day(gen, load), BatterySimulator.Params.defaultFor(0));
        assertEquals(24.0, result.totalExportKwh(), 0.01);
        assertEquals(0.0, result.totalImportKwh(), 0.01);
    }

    @Test
    void noBatteryAllShortfallImports() {
        double[] gen = new double[25];
        double[] load = new double[25];
        java.util.Arrays.fill(gen, 0);
        java.util.Arrays.fill(load, 1000);
        var result = BatterySimulator.simulate(day(gen, load), BatterySimulator.Params.defaultFor(0));
        assertEquals(24.0, result.totalImportKwh(), 0.01);
        assertEquals(0.0, result.totalExportKwh(), 0.01);
        assertEquals(24.0 * TARIFF, result.netCostEur(), 0.01);
    }

    @Test
    void batterySoaksUpExportAndDeliversAtNight() {
        // 7-day simulation so warm-up effects wash out + residual SoC stabilises.
        // Each day: gen 06-18 at 2 kW (24 kWh), load nighttime+morning 1 kW (12 kWh).
        int days = 7;
        double[] gen = new double[24 * days + 1];
        double[] load = new double[24 * days + 1];
        for (int h = 0; h < gen.length; h++) {
            int hOfDay = h % 24;
            gen[h] = (hOfDay >= 6 && hOfDay < 18) ? 2000 : 0;
            load[h] = (hOfDay < 6 || hOfDay >= 18) ? 1000 : 0;
        }
        var resultBig = BatterySimulator.simulate(day(gen, load), BatterySimulator.Params.defaultFor(20));
        var resultZero = BatterySimulator.simulate(day(gen, load), BatterySimulator.Params.defaultFor(0));
        assertTrue(resultBig.totalImportKwh() < resultZero.totalImportKwh() - 20,
                "battery should slash week's imports by >20 kWh; big=" + resultBig.totalImportKwh() + " zero="
                        + resultZero.totalImportKwh());
        // Account for residual SoC value: cost' = netCost − residualSoC × tariff
        double bigEff = resultBig.netCostEur() - resultBig.residualSocKwh() * TARIFF;
        double zeroEff = resultZero.netCostEur() - resultZero.residualSocKwh() * TARIFF;
        assertTrue(bigEff < zeroEff,
                "with residual-credit accounting, big battery cheaper: big=" + bigEff + " zero=" + zeroEff);
    }

    @Test
    void biggerBatterySavingsAreMonotonicOnLongReplay() {
        // 14 days. Per day: solar 06-18 at 2 kW, load constant 500 W = 12 kWh/day.
        int days = 14;
        double[] gen = new double[24 * days + 1];
        double[] load = new double[24 * days + 1];
        for (int h = 0; h < gen.length; h++) {
            int hOfDay = h % 24;
            gen[h] = (hOfDay >= 6 && hOfDay < 18) ? 2000 : 0;
            load[h] = 500;
        }
        double prevEff = Double.MAX_VALUE;
        for (double cap : new double[] { 0, 2, 5, 10, 20 }) {
            var r = BatterySimulator.simulate(day(gen, load), BatterySimulator.Params.defaultFor(cap));
            double eff = r.netCostEur() - r.residualSocKwh() * TARIFF;
            assertTrue(eff <= prevEff + 0.01,
                    "monotone (residual-credited): cap=" + cap + " eff=" + eff + " prev=" + prevEff);
            prevEff = eff;
        }
    }
}

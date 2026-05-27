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
package org.openhab.binding.emsmanager.internal.tariff.compare;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TariffComparisonCalculator}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class TariffComparisonCalculatorTest {

    @Test
    void flatCurveConstant() {
        double[] c = TariffComparisonCalculator.flatCurve(0.30);
        assertEquals(24, c.length);
        for (double v : c) {
            assertEquals(0.30, v, 1e-9);
        }
    }

    @Test
    void dayNightCurveSplit() {
        double[] c = TariffComparisonCalculator.dayNightCurve(0.32, 0.18, 7, 22);
        assertEquals(0.18, c[6], 1e-9, "06:00 is night");
        assertEquals(0.32, c[7], 1e-9, "07:00 is day");
        assertEquals(0.32, c[21], 1e-9, "21:00 is day");
        assertEquals(0.18, c[22], 1e-9, "22:00 is night");
    }

    @Test
    void dayNightWrapsMidnight() {
        // Day window 22:00 → 06:00 (wraps)
        double[] c = TariffComparisonCalculator.dayNightCurve(0.50, 0.10, 22, 6);
        assertEquals(0.50, c[23], 1e-9);
        assertEquals(0.50, c[0], 1e-9);
        assertEquals(0.50, c[5], 1e-9);
        assertEquals(0.10, c[6], 1e-9);
        assertEquals(0.10, c[12], 1e-9);
    }

    @Test
    void dailyCostWeightsByConsumption() {
        // All consumption at hour 0; price there is 1.0, elsewhere 0.
        double[] netImport = new double[24];
        netImport[0] = 5.0; // 5 kWh imported at hour 0
        double[] price = new double[24];
        price[0] = 1.0;
        assertEquals(5.0, TariffComparisonCalculator.dailyCost(netImport, price), 1e-9);
    }

    @Test
    void nightHeavyLoadFavorsNightTariff() {
        // Consumption concentrated at night (hours 0-5), 2 kWh each = 12 kWh/day.
        double[] netImport = new double[24];
        for (int h = 0; h < 6; h++) {
            netImport[h] = 2.0;
        }

        Map<String, double[]> curves = new LinkedHashMap<>();
        curves.put("flat", TariffComparisonCalculator.flatCurve(0.30));
        curves.put("daynight", TariffComparisonCalculator.dayNightCurve(0.32, 0.18, 7, 22));

        List<TariffComparisonCalculator.ProviderResult> ranked = TariffComparisonCalculator.compare(netImport, curves,
                30);

        // Night tariff (0.18 in those hours) should win over flat (0.30).
        assertEquals("daynight", ranked.get(0).provider());
        assertEquals(12 * 0.18 * 30, ranked.get(0).periodCostEur(), 1e-6);
        assertEquals(12 * 0.30 * 30, ranked.get(1).periodCostEur(), 1e-6);
    }

    @Test
    void engieFormulaApplied() {
        double[] spot = new double[24];
        java.util.Arrays.fill(spot, 0.10); // 0.10 €/kWh raw EPEX
        double[] c = TariffComparisonCalculator.engieDynamicCurve(spot);
        // 1.15 × 0.10 + 0.015 = 0.13
        assertEquals(0.13, c[0], 1e-9);
    }

    @Test
    void retailOnSpotFormula() {
        double[] spot = new double[24];
        java.util.Arrays.fill(spot, 0.10);
        double[] c = TariffComparisonCalculator.retailOnSpotCurve(spot, 0.21, 0.045);
        // 0.10 × 1.21 + 0.045 = 0.166
        assertEquals(0.166, c[0], 1e-9);
    }

    @Test
    void csvCurveRejectsShort() {
        assertNull(TariffComparisonCalculator.csvCurve("0.1,0.2,0.3"));
        assertNull(TariffComparisonCalculator.csvCurve(""));
        assertNull(TariffComparisonCalculator.csvCurve(null));
    }
}

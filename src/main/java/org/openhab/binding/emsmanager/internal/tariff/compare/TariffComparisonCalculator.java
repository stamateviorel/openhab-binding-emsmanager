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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Pure-math tariff comparison.
 *
 * <p>
 * Given a 24-element hour-of-day net-import profile (average kWh imported
 * in each hour of the day, over the look-back window) and a candidate
 * tariff's 24-element price curve (€/kWh per hour-of-day), compute the
 * period cost:
 *
 * <pre>
 *     dailyCost = Σ_{h=0}^{23} netImport[h] · price[h]
 *     periodCost = dailyCost · days
 * </pre>
 *
 * <p>
 * The hour-of-day aggregation collapses seasonal/weekly variation into a
 * representative day — adequate for ranking providers, which is the goal
 * (the absolute number is an estimate, the ordering is robust).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class TariffComparisonCalculator {

    private TariffComparisonCalculator() {
    }

    public record ProviderResult(String provider, double dailyCostEur, double periodCostEur) {
    }

    /**
     * @param netImportProfileKwh 24 values — average kWh imported per hour-of-day
     * @param providerCurves provider name → 24 values €/kWh per hour-of-day
     * @param days number of days the period cost should represent
     * @return providers ranked cheapest-first
     */
    public static List<ProviderResult> compare(double[] netImportProfileKwh, Map<String, double[]> providerCurves,
            double days) {
        List<ProviderResult> out = new ArrayList<>();
        for (Map.Entry<String, double[]> e : providerCurves.entrySet()) {
            double[] price = e.getValue();
            double daily = dailyCost(netImportProfileKwh, price);
            out.add(new ProviderResult(e.getKey(), daily, daily * days));
        }
        out.sort(Comparator.comparingDouble(ProviderResult::periodCostEur));
        return out;
    }

    /** Σ netImport[h] · price[h] over the 24 hours. Mismatched/short arrays handled gracefully. */
    public static double dailyCost(double[] netImportProfileKwh, double[] priceCurve) {
        int n = Math.min(netImportProfileKwh.length, priceCurve.length);
        double sum = 0;
        for (int h = 0; h < n; h++) {
            double kwh = netImportProfileKwh[h];
            double p = priceCurve[h];
            if (Double.isNaN(kwh) || Double.isNaN(p)) {
                continue;
            }
            sum += kwh * p;
        }
        return sum;
    }

    // ---- Price-curve builders for the standard tariff shapes ----

    public static double[] flatCurve(double price) {
        double[] c = new double[24];
        java.util.Arrays.fill(c, price);
        return c;
    }

    public static double[] dayNightCurve(double dayPrice, double nightPrice, int dayStart, int dayEnd) {
        double[] c = new double[24];
        for (int h = 0; h < 24; h++) {
            boolean isDay = dayStart <= dayEnd ? (h >= dayStart && h < dayEnd) : (h >= dayStart || h < dayEnd); // wraps
                                                                                                                // midnight
            c[h] = isDay ? dayPrice : nightPrice;
        }
        return c;
    }

    /** Parse a 24-value CSV into a price curve. Returns null if not exactly 24 parseable values. */
    public static double @org.eclipse.jdt.annotation.Nullable [] csvCurve(
            @org.eclipse.jdt.annotation.Nullable String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        String[] parts = csv.split(",");
        if (parts.length < 24) {
            return null;
        }
        double[] c = new double[24];
        for (int h = 0; h < 24; h++) {
            try {
                c[h] = Double.parseDouble(parts[h].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return c;
    }

    /**
     * Example dynamic-tariff formula curve: {@code 1.15 · EPEX(h) + 0.015} applied
     * to a representative spot-price day (a typical BE dynamic-contract shape).
     */
    public static double[] engieDynamicCurve(double[] spotCurve) {
        double[] c = new double[24];
        for (int h = 0; h < 24; h++) {
            double spot = (h < spotCurve.length && !Double.isNaN(spotCurve[h])) ? spotCurve[h] : 0;
            c[h] = 1.15 * spot + 0.015;
        }
        return c;
    }

    /** Retail-on-spot: {@code spot · (1 + vat) + markup} (Tibber / aWATTar shape). */
    public static double[] retailOnSpotCurve(double[] spotCurve, double vat, double markup) {
        double[] c = new double[24];
        for (int h = 0; h < 24; h++) {
            double spot = (h < spotCurve.length && !Double.isNaN(spotCurve[h])) ? spotCurve[h] : 0;
            c[h] = spot * (1 + vat) + markup;
        }
        return c;
    }
}

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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Pure-math year-replay simulator. Replays a historical (generation, load)
 * series through a parametric battery and returns the resulting grid
 * import/export totals + simulated cost.
 *
 * <p>
 * Fully unit-testable.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class BatterySimulator {

    /** One time-step sample. */
    public record Sample(long timestampMs, double generationW, // solar production (≥ 0)
            double loadW, // house consumption (≥ 0)
            double tariffEurPerKwh, // import price at this instant
            double injectionEurPerKwh // export price at this instant
    ) {
    }

    public record Params(double capacityKwh, double chargeEfficiency, double dischargeEfficiency, double maxChargeKw,
            double maxDischargeKw, double socMinFraction, // e.g., 0.10
            double socMaxFraction // e.g., 0.95
    ) {
        public static Params defaultFor(double capacityKwh) {
            return new Params(capacityKwh, 0.95, 0.95, capacityKwh * 0.5, capacityKwh * 0.5, // 0.5C charge/discharge
                    0.10, 0.95);
        }
    }

    public record Result(double capacityKwh, double totalImportKwh, double totalExportKwh, double totalSelfConsumedKwh, // generation
                                                                                                                        // that
                                                                                                                        // went
                                                                                                                        // into
                                                                                                                        // the
                                                                                                                        // house
                                                                                                                        // or
                                                                                                                        // battery
                                                                                                                        // →
                                                                                                                        // house
            double totalCycledKwh, // sum of charge throughput (for cycle counting)
            double importCostEur, double exportEarningsEur, double netCostEur, // importCostEur − exportEarningsEur
            double residualSocKwh // SoC at end minus SoC at start (kWh that would offset future cost)
    ) {
    }

    /**
     * Run the simulation. Returns aggregate results. Pure; no I/O, no global state.
     *
     * <p>
     * Integrates the net flow per sample (Δt seconds) against the configured
     * capacity and charge/discharge efficiencies.
     */
    public static Result simulate(List<Sample> samples, Params p) {
        // Start at SoC_min (empty) so that batteries of different sizes don't
        // get an artificial head-start from their bigger initial reservoir.
        // For multi-day replays the warm-up effect washes out anyway.
        double socMin = p.capacityKwh * p.socMinFraction;
        double socMax = p.capacityKwh * p.socMaxFraction;
        double socStart = socMin;
        double soc = socStart;

        double totalImportKwh = 0;
        double totalExportKwh = 0;
        double totalSelfConsumedKwh = 0;
        double totalCycledKwh = 0;
        double importCostEur = 0;
        double exportEarningsEur = 0;

        for (int i = 1; i < samples.size(); i++) {
            Sample s0 = samples.get(i - 1);
            Sample s = samples.get(i);
            double dtH = (s.timestampMs() - s0.timestampMs()) / 3_600_000.0;
            if (dtH <= 0 || dtH > 1.0) {
                continue; // skip gaps > 1 h or out-of-order samples
            }

            // Use sample-i values for the interval (right-edge attribution).
            double genKwh = (s.generationW() / 1000.0) * dtH;
            double loadKwh = (s.loadW() / 1000.0) * dtH;
            double netKwh = genKwh - loadKwh;
            // Track self-consumption: min(gen, load) is directly-served solar
            double directlyServed = Math.min(genKwh, loadKwh);
            totalSelfConsumedKwh += directlyServed;

            if (netKwh > 0) {
                // Excess generation → charge battery first, then export.
                double room = (socMax - soc) / p.chargeEfficiency(); // input-side capacity to fill the room
                double maxChargeKwh = p.maxChargeKw() * dtH;
                double chargeIn = Math.min(netKwh, Math.min(room, maxChargeKwh));
                if (chargeIn < 0) {
                    chargeIn = 0;
                }
                soc += chargeIn * p.chargeEfficiency();
                totalCycledKwh += chargeIn * p.chargeEfficiency();
                totalSelfConsumedKwh += chargeIn * p.chargeEfficiency(); // batt-stored will be discharged later
                double exportKwh = netKwh - chargeIn;
                totalExportKwh += exportKwh;
                exportEarningsEur += exportKwh * s.injectionEurPerKwh();
            } else if (netKwh < 0) {
                // Shortfall → discharge battery first, then import.
                double need = -netKwh;
                double available = (soc - socMin) * p.dischargeEfficiency();
                double maxDischargeKwh = p.maxDischargeKw() * dtH;
                double dischargeOut = Math.min(need, Math.min(available, maxDischargeKwh));
                if (dischargeOut < 0) {
                    dischargeOut = 0;
                }
                soc -= dischargeOut / p.dischargeEfficiency();
                // Battery-served counts as self-consumption (it was originally solar).
                // We don't double-count: the kWh was added to totalSelfConsumedKwh on the
                // charge side; this is when it gets DELIVERED but the energy was already
                // attributed at charge time.
                double importKwh = need - dischargeOut;
                totalImportKwh += importKwh;
                importCostEur += importKwh * s.tariffEurPerKwh();
            }
        }

        return new Result(p.capacityKwh, totalImportKwh, totalExportKwh, totalSelfConsumedKwh, totalCycledKwh,
                importCostEur, exportEarningsEur, importCostEur - exportEarningsEur, soc - socStart);
    }
}

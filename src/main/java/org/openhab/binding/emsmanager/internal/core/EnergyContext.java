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
package org.openhab.binding.emsmanager.internal.core;

import java.time.Instant;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.capability.Evse;

/**
 * Immutable per-tick snapshot of the energy world. The bridge builds one
 * of these each tick from the underlying items, then hands it to every
 * controller. Controllers are pure functions of this context — easy to
 * unit-test with synthetic contexts.
 *
 * <p>
 * Sign conventions used internally (the configurable input adapters
 * normalize site values to these):
 * <ul>
 * <li>{@code gridLoadW}: + = export to grid, − = import from grid</li>
 * <li>{@code batteryLoadW}: + = discharging, − = charging</li>
 * <li>{@code solarLoadW}: + = producing (only ever positive)</li>
 * <li>{@code houseLoadSumW}: + = consuming</li>
 * </ul>
 *
 * <p>
 * NaN means "value not available this tick" (item NULL/UNDEF/missing).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record EnergyContext(Instant tickAt, double gridLoadRawW, double gridLoadSmoothedW, double solarLoadW,
        double houseLoadSumW, double batteryLoadW, double batterySoC, double batteryReserveTargetPct,
        boolean batteryBelowReserve, double availableExcessW, Mode mode,
        // Per-car snapshots + aggregated phase totals
        Map<String, CarSnapshot> cars, double totalAmpsL1, double totalAmpsL2, double totalAmpsL3, boolean modbusFresh,
        // Peak-shaving inputs (consumed by HardPeakShavingController)
        boolean boilerOn, boolean aircoOn, boolean peakShavingEnabled,
        // Surplus-dispatch inputs (consumed by SolarSurplusDispatcher)
        double gridLoad5minAvgW, double cloudinessTodayPct, boolean boilerUserOverride,
        // Capacity-tariff inputs (consumed by CapacityTariffShavingController) — see CapacityTariffTracker.
        // currentQuarterAvgW: signed (− = import). monthlyPeakW: most-negative slot avg this month.
        double currentQuarterAvgW, double monthlyPeakW, long slotElapsedMs,
        // Current energy tariff price (linked from the tariff Thing via an item).
        // Stored as €/kWh; NaN when the link / Thing is missing.
        double tariffPriceNowEurPerKWh,
        // Cross-Thing mirrors — for SelfConsumptionOptimizer.
        double[] tariffSchedule24h, // length 24 OR empty when missing
        double forecastTodayKwh, // NaN when missing
        double forecastTomorrowKwh, // NaN when missing
        boolean shadowMode) {

    public enum Mode {
        SOLAR_EXCESS,
        BALANCED,
        GRID_IMPORT,
        BATTERY_DEPLETING,
        UNKNOWN
    }

    /**
     * The chargers as the generic {@link Evse} capability (each {@link CarSnapshot}
     * implements it). Lets consumers reason about EVs without the per-car item layout.
     */
    public java.util.Collection<Evse> evses() {
        return java.util.List.copyOf(cars.values());
    }
}

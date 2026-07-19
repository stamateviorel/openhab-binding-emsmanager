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
package org.openhab.binding.emsmanager.internal.config;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Configuration for a single {@code emsmanager:heatpump} Thing.
 *
 * <p>
 * The heat-pump asset is optional — it is built for users who want to plug
 * their existing pump's metering items into the binding and get SCOP-aware
 * mode recommendations. None of the fields are required (controller skips
 * per-field when missing), so an empty Thing is harmless.
 *
 * <p>
 * SCOP (Seasonal Coefficient Of Performance) is the heat output divided
 * by electricity input averaged over a year. A typical air-water pump in
 * Belgium runs SCOP ≈ 3.5–4.0; geothermal pumps ≈ 4.5–5.5. The optimizer
 * uses it to translate electricity tariff (€/kWh) into "effective heat
 * tariff" (€/kWh-thermal) shown in the UI.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class HeatPumpConfig {

    public String name = "Heat pump";

    /** Item reporting the heat pump's current draw in W. Optional. */
    public @Nullable String powerItem;

    /** Item to read the current indoor temp in °C. Optional. */
    public @Nullable String currentTempItem;

    /** Item to read the user-set target temp in °C. Optional. */
    public @Nullable String targetTempItem;

    /** Item to read the outdoor temp in °C. Enables the predictive (RLS+DP) branch. Optional. */
    public @Nullable String outdoorTempItem;

    /** Electrical input power of the pump when ON, W. Used by the DP planner. */
    public double heatPowerW = 3000.0;

    /** Enable the predictive thermal-model + DP planner branch. Requires outdoorTempItem. */
    public boolean enablePredictive = true;

    /** Item the optimizer's recommendation can be linked to / read from. Optional. */
    public @Nullable String modeItem;

    public double scopCop = 4.0;

    /**
     * Piecewise-linear SCOP curve as a CSV of {@code T_out:cop} pairs.
     * If non-empty, this overrides {@link #scopCop} (which is then used only
     * as fallback when no T_out is available).
     *
     * <p>
     * Default values are example datasheet COP figures for a typical
     * air-water residential heat pump; override with your unit's datasheet.
     */
    public String scopCurveCsv = "-15:1.8,-10:2.4,-5:3.1,0:3.5,5:3.9,10:4.3,15:4.7,20:5.0";
    public boolean allowBoostOnSurplus = true;
    public boolean allowDeferOnPeak = true;

    /** Tariff < dayAvg * cheapPriceThresholdRatio → cheap-window mode. */
    public double cheapPriceThresholdRatio = 0.7;

    /** Tariff > dayAvg * expensivePriceThresholdRatio → defer if temp allows. */
    public double expensivePriceThresholdRatio = 1.3;

    /** Allow boost only when solar excess exceeds this W (set 0 to disable threshold). */
    public int boostSurplusThresholdW = 3000;

    /** Hysteresis band around target temp in °C — won't override above target. */
    public double tempDeadbandC = 0.5;

    /** Climate direction: "heat" (default), "cool", or "auto" (read modeItem and map). */
    public String mode = "heat";

    /** For mode="auto": the modeItem numeric value that means heating (e.g. KNX airco 1). */
    public double heatModeValue = 1.0;

    /** For mode="auto": the modeItem numeric value that means cooling (e.g. KNX airco 3). */
    public double coolModeValue = 3.0;
}

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

/**
 * Bridge configuration POJO. Field names match the parameter names in
 * thing-types.xml; openHAB binds the Thing config into this class via
 * {@code getConfigAs(EmsBridgeConfig.class)}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EmsBridgeConfig {

    public int tickIntervalSeconds = 5;
    public int gridSafetyMarginW = 500;
    public int gridEwmaTauSec = 30;

    /** Master shadow-mode flag. Defaults TRUE for safety on first install. */
    public boolean shadowMode = true;

    // Battery asset config — lives on the bridge (no separate Battery Thing).
    /** auto / fixed / readonly. Default readonly for sites with no writable inverter setpoint. */
    public String batteryControlMode = "readonly";
    /** Item to write in batteryControlMode=auto. Empty/missing → handler refuses. */
    public String batterySetpointItemName = "";
    public int batteryFixedSetpointW = 0;
    public int batteryMinSetpointW = -3000;
    public int batteryMaxSetpointW = 3000;

    // Used by CostAnalyticsController. PV injection compensation varies by
    // supplier/market; 0.05 €/kWh is a typical placeholder. Override via Thing config.
    public double injectionPriceEurPerKWh = 0.05;

    // Boiler force-on schedule. CSV: "MON:HH:MM-HH:MM,TUE:HH:MM-HH:MM,…".
    // Days: MON TUE WED THU FRI SAT SUN. Inside the window, BoilerScheduleController
    // emits boiler=ON regardless of solar/tariff. Empty = disabled.
    public String boilerForceOnSchedule = "";

    // Source item names. The defaults below are examples; override via Thing
    // config. Blank means "use compile-time default".
    public String gridLoadItem = "Grid_Power";
    public String solarLoadItem = "Solar_Power";
    public String houseLoadSumItem = "House_Power";
    public String batteryLoadItem = "Battery_Power";
    public String batteryPercentageItem = "Battery_SoC";
    public String batteryReserveTargetItem = "Battery_Reserve_Target_Pct";

    // Sign-convention normalization. The binding's canonical convention is:
    // grid + = export/inject, solar + = producing, house + = consuming,
    // battery + = charging. Set an invert flag if YOUR item uses the opposite
    // sign (e.g. a grid meter where + = import/draw). Applied once in the
    // capability layer so every controller sees the canonical convention.
    public boolean invertGrid = false;
    public boolean invertSolar = false;
    public boolean invertHouse = false;
    public boolean invertBattery = false;

    // Per-car item naming patterns (use %d for the car number 1..4).
    // The defaults are examples for an EVSE + external-power-item layout.
    public String carModeItemPattern = "EVSE%d_Mode";
    public String carCableItemPattern = "EVSE%d_Cable";
    public String carStatusItemPattern = "EVSE%d_Status";
    public String carCurrentLimitItemPattern = "EVSE%d_CurrentLimit";
    public String carPauseItemPattern = "EVSE%d_Pause";
    public String carChargingItemPattern = "EVSE%d_Charging";
    public String carPowerOcppItemPattern = "EVSE%d_Power";
    public String carPowerKwItemPattern = "EVSE%d_Power_kW";
    public String carAmpsL1ItemPattern = "EVSE%d_Amps_L1";
    public String carAmpsL2ItemPattern = "EVSE%d_Amps_L2";
    public String carAmpsL3ItemPattern = "EVSE%d_Amps_L3";

    // Number of cars to scan (1–4).
    public int carCount = 4;

    // Remaining source/control item names. The defaults below are examples;
    // override via Thing config. Blank means "use the compile-time default
    // constant".
    public String boilerStateItem = "Boiler_Switch";
    public String aircoGroupItem = "Aircon_Switch";
    public String peakShavingEnabledItem = "PeakShaving_Enabled";
    public String weatherCloudinessItem = "Weather_Cloudiness";
    public String boilerUserOverrideItem = "EMS_Boiler_User_Override";

    // Backwards-compat: publish a set of mirror items (PeakShaving_Level/Status/Detail,
    // PeakShaving_EcoCap_A, Available_excess_W, EnergyOrchestrator_Mode,
    // Battery_below_reserve) to feed pre-existing UI widgets. Off by default — a
    // fresh install uses the binding's own channels instead.
    public boolean publishLegacyMirrorItems = false;

    // CO₂ tracking. The defaults are example values for a relatively low-carbon
    // grid: ~140 g/kWh imported; ~350 g/kWh avoided by injecting (offsetting the
    // marginal generation source). Override for your grid region.
    public double gridCo2GramsPerKWh = 140.0;
    public double injectionCo2OffsetGramsPerKWh = 350.0;

    // Emissions provider selector. "fixed" uses the constants above;
    // "electricitymaps" pulls live grid-mix periodically (e.g. via Electricity Maps).
    public String emissionsProvider = "fixed";
    public String electricityMapsApiKey = "";
    public String electricityMapsZone = "BE";

    // Soft-peak thresholds.
    public int softShaveThresholdW = -5000;
    public int softRecoveryW = -3000;
    public int softEcoCapA = 8;
    public int normalEcoCapA = 32;

    // Main breaker per phase (example default: 63 A).
    public int mainBreakerAmpsPerPhase = 63;

    // Capacity-tariff minimum billable demand. Some markets bill a minimum
    // demand (e.g. the Belgian capaciteitstarief floors at 2.5 kW) even if the
    // actual monthly peak is lower; below this floor there's no point shaving.
    // Markets without a capacity tariff can ignore this.
    public int capacityMinBillableW = 2500;

    // EV ECO mode is "always charging — minimal floor, ramp with solar". When
    // true, the peak/billing shedding controllers (capacity-tariff and hard
    // peak-shaving) never pause an ECO car; they shed the boiler and airco
    // instead. The hardware breaker-headroom limit still applies (physical
    // safety, not a policy). Default false keeps the shed-ECO-first behaviour.
    public boolean evEcoSacrosanct = false;

    // DHW boiler planner (BoilerPlanController): guarantee a daily hot-water energy
    // target by a "ready by" hour, solar-first during the day and topping up the gap
    // at the cheapest spot hours overnight. Disabled when boilerDailyTargetKwh <= 0.
    // boilerPlanShadow keeps it logging decisions without acting until validated.
    public double boilerDailyTargetKwh = 0.0;
    public int boilerReadyByHour = 7;
    public double boilerRatedKw = 3.0;
    public boolean boilerPlanShadow = true;
    // Optional W power item for the planner to measure delivered energy accurately
    // (rating-independent). Blank → fall back to boilerRatedKw × on-time.
    public String boilerPowerItem = "";

    // Assumed EV charge rate for the "cheapest" plan projection.
    // Defaults to a single-phase 32 A equivalent.
    public double evDefaultChargeRateKw = 7.0;

    // Battery sizing simulator config (example residential prices).
    public double batteryCostEurPerKWhInstalled = 350.0;
    public int batteryCycleLife = 6000;
    public int batteryPaybackYearsHorizon = 10;
    /**
     * Historical lookback for the battery sizing + tariff comparison
     * services. With the per-bucket query cap (1-hour chunks, small page
     * size) heap is now flat regardless of this value — only the runtime
     * grows (one query per hour of lookback). 30 days is a good
     * representativeness/runtime balance; raise to 365 for a seasonal
     * estimate if you don't mind the longer (still heap-safe) run.
     */
    public int batterySizingLookbackDays = 30;

    // Anomaly absolute floor below which deltas are ignored.
    public double anomalyAbsoluteFloorKwh = 0.3;

    // Outdoor-temp forecast (OpenMeteo) for the heat-pump DP planner. Defaults
    // to the site location; OpenMeteo needs no API key.
    public double latitude = 50.7834;
    public double longitude = 4.7736;

    // Statistics rollup tier — daily 23:58 snapshot of finalized totals into
    // clean EMS_Stat_* items so month/year charts stay fast. Default on.
    public boolean statisticsRollupEnabled = true;
}

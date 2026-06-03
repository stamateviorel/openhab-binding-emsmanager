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
package org.openhab.binding.emsmanager.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * Binding-wide constants for the EMS Manager binding.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class EmsManagerBindingConstants {

    public static final String BINDING_ID = "emsmanager";

    // Thing types
    public static final ThingTypeUID THING_TYPE_BRIDGE = new ThingTypeUID(BINDING_ID, "bridge");
    public static final ThingTypeUID THING_TYPE_FORECAST_SOLAR = new ThingTypeUID(BINDING_ID, "forecast-solar");
    public static final ThingTypeUID THING_TYPE_TARIFF = new ThingTypeUID(BINDING_ID, "tariff");
    public static final ThingTypeUID THING_TYPE_BATTERY = new ThingTypeUID(BINDING_ID, "battery");
    public static final ThingTypeUID THING_TYPE_DEVICE_METER = new ThingTypeUID(BINDING_ID, "device-meter");
    public static final ThingTypeUID THING_TYPE_HEATPUMP = new ThingTypeUID(BINDING_ID, "heatpump");
    public static final ThingTypeUID THING_TYPE_CHARGER = new ThingTypeUID(BINDING_ID, "charger");

    // device-meter channel IDs
    public static final String DM_CHANNEL_CURRENT_W = "currentW";
    public static final String DM_CHANNEL_KWH_TODAY = "kwhToday";
    public static final String DM_CHANNEL_KWH_YESTERDAY = "kwhYesterday";
    public static final String DM_CHANNEL_KWH_LAST_7 = "kwhLast7Days";
    public static final String DM_CHANNEL_KWH_LAST_30 = "kwhLast30Days";
    public static final String DM_CHANNEL_KWH_YEAR = "kwhYear";
    public static final String DM_CHANNEL_CATEGORY = "category";
    public static final String DM_CHANNEL_COLOR = "color";

    // bridge channels for aggregate device-meter rollups
    public static final String CHANNEL_DEVICE_TRACKED_W = "deviceMeterTrackedW";
    public static final String CHANNEL_DEVICE_UNTRACKED_W = "deviceMeterUntrackedW";
    public static final String CHANNEL_DEVICE_TRACKED_KWH_DAY = "deviceMeterTrackedKwhDay";
    public static final String CHANNEL_DEVICE_UNTRACKED_KWH_DAY = "deviceMeterUntrackedKwhDay";

    // heat pump (asset) Thing channels — written by HeatPumpOptimizerController each tick.
    public static final String HP_CHANNEL_RECOMMENDED_MODE = "recommendedMode"; // String: OFF/ECO/COMFORT/BOOST
    public static final String HP_CHANNEL_REASON = "reason"; // String: human-readable Dutch
    public static final String HP_CHANNEL_EFFECTIVE_PRICE_EUR = "effectivePriceEurPerKWh"; // tariff / SCOP
    public static final String HP_CHANNEL_DAILY_KWH = "dailyKwhEstimate"; // rough projection
    public static final String HP_CHANNEL_OPTIMIZER_ACTIVE = "optimizerActive"; // Switch — recommendation differs from
                                                                                // current
    // Predictive thermal-model + DP planner outputs.
    public static final String HP_CHANNEL_MODEL_R = "modelR"; // K/W
    public static final String HP_CHANNEL_MODEL_C = "modelC"; // J/K
    public static final String HP_CHANNEL_MODEL_RMSE = "modelRmse"; // °C
    public static final String HP_CHANNEL_PLAN_PREHEAT_AT = "planPreheatStartsAt"; // DateTime
    public static final String HP_CHANNEL_PLAN_COST_24H = "planCostNext24hEur"; // €

    // Forecast Thing channel IDs (prefix FC_ to disambiguate from bridge channels)
    public static final String FC_CHANNEL_NOW_W = "nowW";
    public static final String FC_CHANNEL_NEXT_1H_WH = "next1hWh";
    public static final String FC_CHANNEL_NEXT_3H_WH = "next3hWh";
    public static final String FC_CHANNEL_NEXT_6H_WH = "next6hWh";
    public static final String FC_CHANNEL_TODAY_KWH = "todayKwh";
    public static final String FC_CHANNEL_TOMORROW_KWH = "tomorrowKwh";
    public static final String FC_CHANNEL_PEAK_TODAY_AT = "peakTodayAt";
    public static final String FC_CHANNEL_LAST_REFRESH_AT = "lastRefreshAt";
    public static final String FC_CHANNEL_RATE_LIMIT_REMAINING = "rateLimitRemaining";
    public static final String FC_CHANNEL_LAST_ERROR = "lastError";
    public static final String FC_CHANNEL_HOURLY_TODAY_CSV = "hourlyForecastTodayCsv"; // hourly forecast CSV

    // Tariff Thing channel IDs (prefix TR_)
    public static final String TR_CHANNEL_NOW_PRICE = "nowPriceEurPerKWh";
    public static final String TR_CHANNEL_NEXT_1H_PRICE = "next1hPriceEurPerKWh";
    public static final String TR_CHANNEL_TODAY_MIN = "todayMinPrice";
    public static final String TR_CHANNEL_TODAY_MAX = "todayMaxPrice";
    public static final String TR_CHANNEL_TODAY_AVG = "todayAvgPrice";
    public static final String TR_CHANNEL_CHEAPEST_HOUR_START = "cheapestHourStart";
    public static final String TR_CHANNEL_MOST_EXPENSIVE_HOUR_START = "mostExpensiveHourStart";
    public static final String TR_CHANNEL_SCHEDULE_24H = "schedule24hCsv";
    public static final String TR_CHANNEL_SCHEDULE_48H = "schedule48hCsv";
    public static final String TR_CHANNEL_LAST_REFRESH_AT = "tariffLastRefreshAt";

    // Bridge channel IDs — liveness
    public static final String CHANNEL_SHADOW_MODE = "shadowMode";
    public static final String CHANNEL_LAST_TICK_AT = "lastTickAt";
    public static final String CHANNEL_TICK_COUNT = "tickCount";
    public static final String CHANNEL_CONTROLLER_COUNT = "controllerCount";

    // Bridge channel IDs — energy context
    public static final String CHANNEL_GRID_LOAD_RAW = "gridLoadRawW";
    public static final String CHANNEL_GRID_LOAD_SMOOTHED = "gridLoadSmoothedW";
    public static final String CHANNEL_SOLAR_LOAD = "solarLoadW";
    public static final String CHANNEL_HOUSE_LOAD_SUM = "houseLoadSumW";
    public static final String CHANNEL_BATTERY_LOAD = "batteryLoadW";
    public static final String CHANNEL_BATTERY_SOC = "batterySoC";
    public static final String CHANNEL_BATTERY_RESERVE_TARGET = "batteryReserveTargetPct";
    public static final String CHANNEL_BATTERY_BELOW_RESERVE = "batteryBelowReserve";
    public static final String CHANNEL_AVAILABLE_EXCESS = "availableExcessW";
    public static final String CHANNEL_ENERGY_MODE = "energyMode";

    // Bridge config parameter names (kept in sync with thing-types.xml)
    public static final String CFG_TICK_INTERVAL_SECONDS = "tickIntervalSeconds";
    public static final String CFG_GRID_SAFETY_MARGIN_W = "gridSafetyMarginW";
    public static final String CFG_GRID_EWMA_TAU_SEC = "gridEwmaTauSec";
    public static final String CFG_SHADOW_MODE = "shadowMode";

    // Source items read by the ContextBuilder (single source of truth).
    public static final String ITEM_GRID_LOAD = "Grid_Power";
    public static final String ITEM_SOLAR_LOAD = "Solar_Power";
    public static final String ITEM_HOUSE_LOAD_SUM = "House_Power";
    public static final String ITEM_BATTERY_LOAD = "Battery_Power";
    public static final String ITEM_BATTERY_PERCENTAGE = "Battery_SoC";
    public static final String ITEM_BATTERY_RESERVE_TARGET = "Battery_Reserve_Target_Pct";

    // Per-car EVSE items (N = 1..4).
    public static final String ITEM_CAR_MODE_FMT = "EVSE%d_Mode";
    public static final String ITEM_CAR_CABLE_FMT = "EVSE%d_Cable";
    public static final String ITEM_CAR_STATUS_FMT = "EVSE%d_Status";
    public static final String ITEM_CAR_CURRENT_LIMIT_FMT = "EVSE%d_CurrentLimit";
    public static final String ITEM_CAR_PAUSE_FMT = "EVSE%d_Pause";
    public static final String ITEM_CAR_CHARGING_FMT = "EVSE%d_Charging";
    public static final String ITEM_CAR_POWER_FMT = "EVSE%d_Power"; // metered-via-charger cars only
    public static final String ITEM_CAR_POWER_KW_FMT = "EVSE%d_Power_kW"; // per-car power (kW)
    public static final String ITEM_CAR_AMPS_L1_FMT = "EVSE%d_Amps_L1";
    public static final String ITEM_CAR_AMPS_L2_FMT = "EVSE%d_Amps_L2";
    public static final String ITEM_CAR_AMPS_L3_FMT = "EVSE%d_Amps_L3";

    // Energy-mode derivation thresholds.
    public static final int DEFAULT_RESERVE_PCT = 30;
    public static final int SOLAR_EXCESS_W = 1000;
    public static final int IMPORT_W = -500;

    // SoftPeakShavingController sticky band.
    public static final int SOFT_THRESHOLD_W = -5000;
    public static final int SOFT_RECOVERY_W = -3000;
    public static final int SOFT_ECO_CAP_A = 8;
    public static final int NORMAL_ECO_CAP_A = 32;

    // HardPeakShavingController tier triggers.
    public static final int HARD_PEAK_THRESHOLD_W = -15000;
    public static final int HARD_RECOVERY_THRESHOLD_W = -10000;
    public static final int HARD_PEAK_DURATION_SEC = 180;
    public static final int HARD_TIER_INTERVAL_SEC = 45;
    public static final int HARD_MAX_TIER = 3;

    // Items the HardPeakShavingController reads / would-write.
    public static final String ITEM_BOILER_REAL = "Boiler_Switch";
    public static final String ITEM_AIRCO_GROUP = "Aircon_Switch";
    public static final String ITEM_PEAK_SHAVING_ENABLED = "PeakShaving_Enabled";

    // Asset IDs used in SetpointRequests from the hard controller.
    public static final String ASSET_BOILER = "boiler";
    public static final String ASSET_AIRCO = "airco-group";
    public static final String ASSET_BATTERY = "battery";

    // SolarSurplusDispatcher inputs + thresholds.
    public static final String ITEM_WEATHER_CLOUDINESS = "Weather_Cloudiness";
    public static final String ITEM_BOILER_USER_OVERRIDE = "EMS_Boiler_User_Override";
    public static final long GRID_5MIN_WINDOW_MS = 5L * 60L * 1000L;
    public static final int SURPLUS_DEFAULT_ON_THRESHOLD_W = 2000;
    public static final int SURPLUS_GLOOMY_ON_THRESHOLD_W = 500; // cloudiness > 70%
    public static final int SURPLUS_SUNNY_ON_THRESHOLD_W = 2500; // cloudiness < 20%
    public static final int SURPLUS_BELOW_RESERVE_PENALTY_W = 1500;
    public static final int SURPLUS_OFF_THRESHOLD_W = -1000; // 5-min avg below this → boiler off

    // Controller priorities (lower runs first).
    public static final int PRIO_SAFETY_BREAKER = 10;
    public static final int PRIO_CAPACITY_TARIFF = 30;
    public static final int PRIO_HARD_PEAK_SHAVING = 40;
    public static final int PRIO_SOFT_PEAK_SHAVING = 50;
    public static final int PRIO_EV_COORDINATOR = 60;
    public static final int PRIO_EV_CHARGING_PLAN = 65; // observer; runs after EV coordinator to read its effects
    public static final int PRIO_BOILER_PLAN = 67; // DHW deadline planner; runs before SolarSurplus
    public static final int PRIO_HEATPUMP_OPTIMIZER = 85; // observer; runs after battery TOU + before self-consumption
    public static final int PRIO_SOLAR_SURPLUS = 70;
    public static final int PRIO_BATTERY_TOU = 80;
    public static final int PRIO_SELF_CONSUMPTION = 90;
    public static final int PRIO_PRODUCTION_SHAVING = 100;
    public static final int PRIO_COST_ANALYTICS = 110; // read-only observer
    public static final int PRIO_LONG_TERM_STATS = 115; // read-only observer, runs after CostAnalytics
    public static final int PRIO_CO2_TRACKING = 117; // read-only observer, runs after LongTermStats
    public static final int PRIO_ANOMALY_DETECTION = 118; // read-only observer, runs after Co2Tracking
    public static final int PRIO_STATS_ROLLUP = 119; // read-only observer, runs last (needs fresh CO₂ net)

    // Tariff mirror item (linked to the tariff Thing's nowPrice channel).
    public static final String ITEM_TARIFF_NOW_PRICE = "EMS_Tariff_Now_EurPerKWh";

    // Analytics items written by CostAnalyticsController.
    public static final String ITEM_EMS_SELFCONSUMPTION_KWH_DAY = "EMS_SelfConsumption_kWh_Day";
    public static final String ITEM_EMS_SELFCONSUMPTION_KWH_MONTH = "EMS_SelfConsumption_kWh_Month";
    public static final String ITEM_EMS_FEEDIN_KWH_DAY = "EMS_FeedIn_kWh_Day";
    public static final String ITEM_EMS_FEEDIN_KWH_MONTH = "EMS_FeedIn_kWh_Month";
    public static final String ITEM_EMS_SUPPLY_KWH_DAY = "EMS_Supply_kWh_Day";
    public static final String ITEM_EMS_SUPPLY_KWH_MONTH = "EMS_Supply_kWh_Month";
    public static final String ITEM_EMS_COST_EUR_MONTH = "EMS_Cost_EUR_Month";
    public static final String ITEM_EMS_COST_EUR_TOTAL = "EMS_Cost_EUR_Total";
    public static final String ITEM_EMS_SAVINGS_EUR_MONTH = "EMS_Savings_EUR_Month";
    public static final String ITEM_EMS_SAVINGS_EUR_TOTAL = "EMS_Savings_EUR_Total";
    public static final String ITEM_EMS_EARNINGS_EUR_MONTH = "EMS_Earnings_EUR_Month";
    public static final String ITEM_EMS_EARNINGS_EUR_TOTAL = "EMS_Earnings_EUR_Total";

    // CO₂ items written by Co2TrackingController (read by the rollup tier).
    public static final String ITEM_EMS_CO2_NET_TODAY_KG = "EMS_CO2_Net_Today_kg";

    // Statistics rollup tier — one clean datapoint per day, written by
    // StatisticsRollupController at 23:58. These back the fast month/year/
    // multi-year bar charts (a year is ~365 points, not millions).
    public static final String ITEM_EMS_STAT_SOLAR_KWH = "EMS_Stat_Solar_kWh";
    public static final String ITEM_EMS_STAT_HOUSE_KWH = "EMS_Stat_House_kWh";
    public static final String ITEM_EMS_STAT_SELFCONSUMPTION_KWH = "EMS_Stat_SelfConsumption_kWh";
    public static final String ITEM_EMS_STAT_FEEDIN_KWH = "EMS_Stat_FeedIn_kWh";
    public static final String ITEM_EMS_STAT_SUPPLY_KWH = "EMS_Stat_Supply_kWh";
    public static final String ITEM_EMS_STAT_CO2_KG = "EMS_Stat_CO2_kg";

    // Cross-Thing channel-mirror items.
    public static final String ITEM_TARIFF_SCHEDULE_24H_CSV = "EMS_Tariff_Schedule24h_CSV";
    public static final String ITEM_FORECAST_TODAY_KWH = "EMS_Forecast_Today_kWh";
    public static final String ITEM_FORECAST_TOMORROW_KWH = "EMS_Forecast_Tomorrow_kWh";

    // EV charging plan items (per-car N=1..4). Inputs (settable):
    public static final String ITEM_CAR_PLAN_TARGET_KWH_FMT = "EVSE%d_Plan_Target_kWh";
    public static final String ITEM_CAR_PLAN_DEPARTURE_FMT = "EVSE%d_Plan_Departure_At";
    public static final String ITEM_CAR_PLAN_STRATEGY_FMT = "EVSE%d_Plan_Strategy"; // "now" / "cheapest" /
                                                                                    // "solar-first"
    public static final String ITEM_CAR_PLAN_ENABLED_FMT = "EVSE%d_Plan_Enabled"; // Switch ON/OFF
    // Outputs (read-only, published by EvChargingPlanController):
    public static final String ITEM_CAR_PLAN_REQUIRED_KWH_FMT = "EVSE%d_Plan_Required_kWh";
    public static final String ITEM_CAR_PLAN_PROJECTED_COST_FMT = "EVSE%d_Plan_Projected_Cost_EUR";
    public static final String ITEM_CAR_PLAN_HOURS_REM_FMT = "EVSE%d_Plan_Hours_Remaining";
    public static final String ITEM_CAR_PLAN_STATUS_FMT = "EVSE%d_Plan_Status"; // Dutch one-liner
    public static final String ITEM_CAR_PLAN_FEASIBLE_FMT = "EVSE%d_Plan_Feasible"; // Switch ON=OK / OFF=at risk

    // SelfConsumptionOptimizer plan channels
    public static final String CHANNEL_OPTIMIZER_PLAN_CSV = "optimizerPlan24hCsv";
    public static final String CHANNEL_OPTIMIZER_NEXT_CHARGE_HOUR = "optimizerNextChargeHour";
    public static final String CHANNEL_OPTIMIZER_NEXT_DISCHARGE_HOUR = "optimizerNextDischargeHour";

    // Backwards-compat mirror item default.
    public static final String ITEM_BATTERY_RESERVE_TARGET_DEFAULT = "30";

    // Capacity-tariff (e.g. the Belgian capaciteitstarief) constants.
    public static final int CAPACITY_MIN_BILLABLE_W = 2500; // min billable, e.g. 2.5 kW
    public static final int CAPACITY_SHED_MARGIN_W = 300; // safety margin under projected peak

    // Capacity-tariff channels
    public static final String CHANNEL_CAPACITY_MONTHLY_PEAK_W = "capacityMonthlyPeakW";
    public static final String CHANNEL_CAPACITY_CURRENT_QUARTER_W = "capacityCurrentQuarterAvgW";
    public static final String CHANNEL_CAPACITY_PROJECTED_QUARTER_W = "capacityProjectedQuarterW";
    public static final String CHANNEL_CAPACITY_WOULD_EXCEED = "capacityWouldExceedMonthlyPeak";
    public static final String CHANNEL_CAPACITY_STATUS = "capacityTariffStatus";

    // EV coordinator constants.
    public static final int PHASES = 3;
    public static final int PHASE_VOLTAGE = 230;
    public static final int RAMP_UP_STEP_A = 5; // amps per tick (drops are immediate)
    public static final int HYSTERESIS_A = 3;

    // Core diagnostic channels
    public static final String CHANNEL_SOFT_ECO_CAP_A = "softPeakShavingEcoCapA";
    public static final String CHANNEL_BREAKER_HEADROOM_A = "breakerHeadroomA";
    public static final String CHANNEL_LAST_DECISION_LOG = "lastDecisionLog";
    public static final String CHANNEL_MODBUS_FRESH = "modbusFresh";

    // Per-car EMS reason channels (car%dReason → car1Reason..car4Reason).
    public static final String CHANNEL_CAR_REASON_FMT = "car%dReason";

    // HardPeakShavingController channels
    public static final String CHANNEL_HARD_PEAK_LEVEL = "hardPeakShavingLevel";
    public static final String CHANNEL_HARD_PEAK_STATUS = "hardPeakShavingStatus";
    public static final String CHANNEL_HARD_PEAK_DETAIL = "hardPeakShavingDetail";

    // SolarSurplusDispatcher diagnostic channels
    public static final String CHANNEL_GRID_5MIN_AVG = "gridLoad5minAvgW";
    public static final String CHANNEL_SURPLUS_ON_THRESHOLD = "surplusOnThresholdW";
    public static final String CHANNEL_BOILER_USER_OVERRIDE = "boilerUserOverride";

    private EmsManagerBindingConstants() {
        // not instantiable
    }
}

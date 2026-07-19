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

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.capability.ItemSiteReader;
import org.openhab.binding.emsmanager.internal.capability.Site;
import org.openhab.binding.emsmanager.internal.config.EmsBridgeConfig;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * Reads the source items into an {@link EnergyContext} each tick.
 *
 * <p>
 * Every item read is defensive: missing item → ItemNotFoundException →
 * {@code Double.NaN}; NULL/UNDEF state → {@code Double.NaN}. The derived
 * fields ({@code availableExcessW}, {@code energyMode},
 * {@code batteryBelowReserve}) are computed from configurable thresholds.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ContextBuilder {

    private final ItemRegistry itemRegistry;
    private final EwmaFilter gridEwma;
    private final RollingAverage grid5minAvg;
    private final CapacityTariffTracker capacityTracker;
    private final int gridSafetyMarginW;
    private final EmsBridgeConfig cfg;
    private final @Nullable ThingRegistry thingRegistry;

    public ContextBuilder(ItemRegistry itemRegistry, EwmaFilter gridEwma, RollingAverage grid5minAvg,
            CapacityTariffTracker capacityTracker, int gridSafetyMarginW, EmsBridgeConfig cfg) {
        this(itemRegistry, gridEwma, grid5minAvg, capacityTracker, gridSafetyMarginW, cfg, null);
    }

    public ContextBuilder(ItemRegistry itemRegistry, EwmaFilter gridEwma, RollingAverage grid5minAvg,
            CapacityTariffTracker capacityTracker, int gridSafetyMarginW, EmsBridgeConfig cfg,
            @Nullable ThingRegistry thingRegistry) {
        this.itemRegistry = itemRegistry;
        this.gridEwma = gridEwma;
        this.grid5minAvg = grid5minAvg;
        this.capacityTracker = capacityTracker;
        this.gridSafetyMarginW = gridSafetyMarginW;
        this.cfg = cfg;
        this.thingRegistry = thingRegistry;
    }

    private String nameOr(String configured, String fallback) {
        return (configured == null || configured.isBlank()) ? fallback : configured;
    }

    public EnergyContext build(boolean shadowMode) {
        long nowMs = System.currentTimeMillis();
        Instant tickAt = Instant.ofEpochMilli(nowMs);

        // All device readings come through the capability layer, which resolves
        // configured item names and normalizes the sign convention in one place.
        Site site = ItemSiteReader.read(this::readNumber, item -> "ON".equals(readString(item)), cfg);

        double gridRaw = site.grid().watts();
        double gridSmoothed = Double.isNaN(gridRaw) ? gridEwma.value() : gridEwma.update(gridRaw, nowMs);
        if (!Double.isNaN(gridRaw)) {
            grid5minAvg.add(gridRaw, nowMs);
            capacityTracker.sample(gridRaw, nowMs);
        }
        double grid5min = grid5minAvg.average();
        CapacityTariffTracker.State capState = capacityTracker.state(nowMs);
        double solar = site.solar().watts();
        double house = site.house().watts();
        double battery = site.battery().watts();
        double soc = site.battery().soc();

        double reserve = site.battery().reserveTarget();
        if (Double.isNaN(reserve)) {
            reserve = DEFAULT_RESERVE_PCT;
        }

        boolean belowReserve = !Double.isNaN(soc) && soc < reserve;

        double availableExcess;
        if (!Double.isNaN(gridSmoothed) && gridSmoothed > gridSafetyMarginW) {
            availableExcess = Math.round(gridSmoothed - gridSafetyMarginW);
        } else {
            availableExcess = 0.0;
        }

        EnergyContext.Mode mode = classifyMode(gridSmoothed, soc, reserve);

        // Per-car snapshots. Prefer charger Things if any are defined (opt-in,
        // upstream-friendly); otherwise fall back to the carCount + car%d patterns
        // so existing installs are byte-for-byte unaffected.
        Map<String, CarSnapshot> cars = new LinkedHashMap<>();
        double totalL1 = 0.0, totalL2 = 0.0, totalL3 = 0.0;
        boolean anyCarAmpsNonZero = false;

        java.util.List<CarSnapshot> snapshots = new java.util.ArrayList<>();
        java.util.List<CarSnapshot> fromThings = buildCarsFromThings();
        if (!fromThings.isEmpty()) {
            snapshots.addAll(fromThings);
        } else {
            int carCount = Math.max(0, Math.min(8, cfg.carCount));
            for (int n = 1; n <= carCount; n++) {
                snapshots.add(buildCar(n));
            }
        }
        for (CarSnapshot car : snapshots) {
            cars.put(car.carKey(), car);
            if (!Double.isNaN(car.ampsL1())) {
                totalL1 += car.ampsL1();
            }
            if (!Double.isNaN(car.ampsL2())) {
                totalL2 += car.ampsL2();
            }
            if (!Double.isNaN(car.ampsL3())) {
                totalL3 += car.ampsL3();
            }
            if (car.ampsL1() > 0.1 || car.ampsL2() > 0.1 || car.ampsL3() > 0.1) {
                anyCarAmpsNonZero = true;
            }
        }
        // Measurement-freshness fallback: any non-zero per-car amps proves the
        // metering bridge is alive. Without that signal we trust by default (the
        // hardware breaker is the last-resort safety net).
        boolean modbusFresh = anyCarAmpsNonZero || !Double.isNaN(gridRaw);

        // Peak-shaving inputs.
        boolean boilerOn = site.boiler().on();
        boolean aircoOn = site.airco().on();
        String enabledStr = readString(nameOr(cfg.peakShavingEnabledItem, ITEM_PEAK_SHAVING_ENABLED));
        // Treat NULL / UNDEF as enabled (safety default).
        boolean peakShavingEnabled = (enabledStr == null) || "ON".equals(enabledStr);

        // Surplus-dispatch inputs.
        double cloudiness = readNumber(nameOr(cfg.weatherCloudinessItem, ITEM_WEATHER_CLOUDINESS)); // NaN if missing
        boolean userOverride = "ON".equals(readString(nameOr(cfg.boilerUserOverrideItem, ITEM_BOILER_USER_OVERRIDE)));

        // Tariff mirror item, linked to the tariff Thing's nowPrice channel.
        double tariffPriceNow = readNumber(ITEM_TARIFF_NOW_PRICE);

        // Cross-Thing mirrors. Parse tariff CSV string into 24 doubles.
        double[] tariffSchedule = parseScheduleCsv(readString(ITEM_TARIFF_SCHEDULE_24H_CSV));
        double forecastToday = readNumber(ITEM_FORECAST_TODAY_KWH);
        double forecastTomorrow = readNumber(ITEM_FORECAST_TOMORROW_KWH);

        return new EnergyContext(tickAt, gridRaw, gridSmoothed, solar, house, battery, soc, reserve, belowReserve,
                availableExcess, mode, cars, totalL1, totalL2, totalL3, modbusFresh, boilerOn, aircoOn,
                peakShavingEnabled, grid5min, cloudiness, userOverride, capState.currentSlotAvgW(),
                capState.monthlyPeakW(), capState.slotElapsedMs(), tariffPriceNow, tariffSchedule, forecastToday,
                forecastTomorrow, shadowMode);
    }

    /** Parse a 24-element CSV of €/kWh prices. Returns empty array on any failure. */
    private static double[] parseScheduleCsv(@org.eclipse.jdt.annotation.Nullable String csv) {
        if (csv == null || csv.isEmpty()) {
            return new double[0];
        }
        try {
            String[] parts = csv.split(",");
            if (parts.length < 24) {
                return new double[0];
            }
            double[] out = new double[24];
            for (int i = 0; i < 24; i++) {
                out[i] = Double.parseDouble(parts[i].trim());
            }
            return out;
        } catch (Exception e) {
            return new double[0];
        }
    }

    /** Discover {@code emsmanager:charger} Things and build a snapshot per charger. Empty if none. */
    private java.util.List<CarSnapshot> buildCarsFromThings() {
        java.util.List<CarSnapshot> out = new java.util.ArrayList<>();
        var registry = thingRegistry;
        if (registry == null) {
            return out;
        }
        for (org.openhab.core.thing.Thing t : registry.getAll()) {
            if (!THING_TYPE_CHARGER.equals(t.getThingTypeUID())) {
                continue;
            }
            var h = t.getHandler();
            if (h instanceof org.openhab.binding.emsmanager.internal.charger.ChargerHandler ch) {
                out.add(buildCarFromCfg(ch.carKey(), ch.getCfg()));
            }
        }
        return out;
    }

    private CarSnapshot buildCarFromCfg(String carKey,
            org.openhab.binding.emsmanager.internal.config.ChargerConfig cfg) {
        CarSnapshot.Mode mode = parseMode(readString(cfg.modeItem));
        boolean cable = "ON".equals(readString(cfg.cableItem));
        String status = readString(cfg.statusItem);
        if (status == null) {
            status = "";
        }
        double limit = readNumber(cfg.currentLimitItem);
        boolean paused = "ON".equals(readString(cfg.pauseItem));

        double drawW = 0.0;
        double kw = readNumber(cfg.powerKwItem);
        if (!Double.isNaN(kw)) {
            drawW = Math.abs(kw) * 1000.0;
        } else {
            double w = readNumber(cfg.powerOcppItem);
            drawW = Double.isNaN(w) ? 0.0 : Math.abs(w);
        }

        double l1 = nz(readNumber(cfg.ampsL1Item));
        double l2 = nz(readNumber(cfg.ampsL2Item));
        double l3 = nz(readNumber(cfg.ampsL3Item));

        return new CarSnapshot(carKey, mode, cable, status, l1, l2, l3, drawW, Double.isNaN(limit) ? 0.0 : limit,
                paused);
    }

    private CarSnapshot buildCar(int n) {
        CarSnapshot.Mode mode = parseMode(
                readString(String.format(nameOr(cfg.carModeItemPattern, ITEM_CAR_MODE_FMT), n)));
        boolean cable = "ON".equals(readString(String.format(nameOr(cfg.carCableItemPattern, ITEM_CAR_CABLE_FMT), n)));
        String status = readString(String.format(nameOr(cfg.carStatusItemPattern, ITEM_CAR_STATUS_FMT), n));
        if (status == null) {
            status = "";
        }
        double limit = readNumber(String.format(nameOr(cfg.carCurrentLimitItemPattern, ITEM_CAR_CURRENT_LIMIT_FMT), n));
        boolean paused = "ON".equals(readString(String.format(nameOr(cfg.carPauseItemPattern, ITEM_CAR_PAUSE_FMT), n)));

        // Power source — read both potential patterns and pick the first that returns a number.
        // External power item (kW) first → multiply ×1000; fall back to OCPP MeterValue (W). This
        // makes the binding portable: a site with all-OCPP chargers leaves the external-power
        // patterns blank, a site with all externally-metered chargers leaves the OCPP patterns
        // blank, and mixed sites work as-is.
        double drawW = 0.0;
        double kw = readNumber(String.format(nameOr(cfg.carPowerKwItemPattern, ITEM_CAR_POWER_KW_FMT), n));
        if (!Double.isNaN(kw)) {
            drawW = Math.abs(kw) * 1000.0;
        } else {
            double w = readNumber(String.format(nameOr(cfg.carPowerOcppItemPattern, ITEM_CAR_POWER_FMT), n));
            drawW = Double.isNaN(w) ? 0.0 : Math.abs(w);
        }

        double l1 = nz(readNumber(String.format(nameOr(cfg.carAmpsL1ItemPattern, ITEM_CAR_AMPS_L1_FMT), n)));
        double l2 = nz(readNumber(String.format(nameOr(cfg.carAmpsL2ItemPattern, ITEM_CAR_AMPS_L2_FMT), n)));
        double l3 = nz(readNumber(String.format(nameOr(cfg.carAmpsL3ItemPattern, ITEM_CAR_AMPS_L3_FMT), n)));

        return new CarSnapshot("car" + n, mode, cable, status, l1, l2, l3, drawW, Double.isNaN(limit) ? 0.0 : limit,
                paused);
    }

    private CarSnapshot.Mode parseMode(@org.eclipse.jdt.annotation.Nullable String s) {
        if (s == null) {
            return CarSnapshot.Mode.UNKNOWN;
        }
        switch (s) {
            case "ECO":
                return CarSnapshot.Mode.ECO;
            case "SNEL":
                return CarSnapshot.Mode.SNEL;
            case "OFF":
                return CarSnapshot.Mode.OFF;
            default:
                return CarSnapshot.Mode.UNKNOWN;
        }
    }

    /** Treat NaN/negative as 0 for breaker-headroom totals (matches JS parseAmp). */
    private double nz(double v) {
        if (Double.isNaN(v) || v < 0) {
            return 0.0;
        }
        return v;
    }

    private EnergyContext.Mode classifyMode(double grid, double soc, double reserve) {
        if (Double.isNaN(grid)) {
            return EnergyContext.Mode.UNKNOWN;
        }
        if (grid > SOLAR_EXCESS_W) {
            return EnergyContext.Mode.SOLAR_EXCESS;
        }
        if (grid < IMPORT_W) {
            if (!Double.isNaN(soc) && soc < reserve) {
                return EnergyContext.Mode.BATTERY_DEPLETING;
            }
            return EnergyContext.Mode.GRID_IMPORT;
        }
        return EnergyContext.Mode.BALANCED;
    }

    private double readNumber(@org.eclipse.jdt.annotation.Nullable String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return Double.NaN;
        }
        try {
            Item item = itemRegistry.getItem(itemName);
            State state = item.getState();
            if (state instanceof UnDefType) {
                return Double.NaN;
            }
            String s = state.toString();
            if (s == null || "NULL".equals(s) || "UNDEF".equals(s) || s.isEmpty()) {
                return Double.NaN;
            }
            int sp = s.indexOf(' ');
            String numPart = (sp > 0) ? s.substring(0, sp) : s;
            return Double.parseDouble(numPart);
        } catch (ItemNotFoundException | NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Returns null on missing / NULL / UNDEF / null-or-blank name. */
    private @org.eclipse.jdt.annotation.Nullable String readString(
            @org.eclipse.jdt.annotation.Nullable String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return null;
        }
        try {
            Item item = itemRegistry.getItem(itemName);
            State state = item.getState();
            if (state instanceof UnDefType) {
                return null;
            }
            String s = state.toString();
            if (s == null || "NULL".equals(s) || "UNDEF".equals(s)) {
                return null;
            }
            return s;
        } catch (ItemNotFoundException e) {
            return null;
        }
    }
}

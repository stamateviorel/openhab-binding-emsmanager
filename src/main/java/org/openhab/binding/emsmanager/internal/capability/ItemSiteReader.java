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
package org.openhab.binding.emsmanager.internal.capability;

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.config.EmsBridgeConfig;

/**
 * Builds an item-backed {@link Site} snapshot. This is the single place that
 * resolves configured item names (with the compile-time defaults as fallback)
 * and applies the sign-convention normalization, so every consumer downstream
 * sees the canonical convention regardless of how the user's items are wired.
 *
 * <p>
 * The actual item reads are injected as functions, which keeps this class
 * free of any openHAB runtime dependency and trivially unit-testable.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ItemSiteReader {

    /** Reads a numeric item state; returns {@code Double.NaN} when unavailable. */
    @FunctionalInterface
    public interface NumberReader {
        double read(String itemName);
    }

    /** Reads an on/off item state. */
    @FunctionalInterface
    public interface SwitchReader {
        boolean isOn(String itemName);
    }

    private ItemSiteReader() {
    }

    public static Site read(NumberReader num, SwitchReader sw, EmsBridgeConfig cfg) {
        double grid = num.read(nameOr(cfg.gridLoadItem, ITEM_GRID_LOAD));
        if (!Double.isNaN(grid) && cfg.gridImportPositive) {
            grid = -grid; // normalize to canonical: + = export
        }
        double solar = num.read(nameOr(cfg.solarLoadItem, ITEM_SOLAR_LOAD));
        double house = num.read(nameOr(cfg.houseLoadSumItem, ITEM_HOUSE_LOAD_SUM));
        double batteryW = num.read(nameOr(cfg.batteryLoadItem, ITEM_BATTERY_LOAD));
        if (!Double.isNaN(batteryW) && !cfg.batteryChargePositive) {
            batteryW = -batteryW; // normalize to canonical: + = charging
        }
        double soc = num.read(nameOr(cfg.batteryPercentageItem, ITEM_BATTERY_PERCENTAGE));
        double reserve = num.read(nameOr(cfg.batteryReserveTargetItem, ITEM_BATTERY_RESERVE_TARGET));

        boolean boilerOn = sw.isOn(nameOr(cfg.boilerStateItem, ITEM_BOILER_REAL));
        boolean aircoOn = sw.isOn(nameOr(cfg.aircoGroupItem, ITEM_AIRCO_GROUP));

        return new Snapshot(new EnergyReading.Of(grid), new EnergyReading.Of(solar), new EnergyReading.Of(house),
                new Battery.Of(batteryW, soc, reserve), new ControllableLoad.Of(ASSET_BOILER, boilerOn),
                new ControllableLoad.Of(ASSET_AIRCO, aircoOn));
    }

    private static String nameOr(String configured, String fallback) {
        return (configured == null || configured.isBlank()) ? fallback : configured;
    }

    /** Immutable {@link Site} snapshot. */
    private static final class Snapshot implements Site {
        private final EnergyReading grid;
        private final EnergyReading solar;
        private final EnergyReading house;
        private final Battery battery;
        private final ControllableLoad boiler;
        private final ControllableLoad airco;

        Snapshot(EnergyReading grid, EnergyReading solar, EnergyReading house, Battery battery, ControllableLoad boiler,
                ControllableLoad airco) {
            this.grid = grid;
            this.solar = solar;
            this.house = house;
            this.battery = battery;
            this.boiler = boiler;
            this.airco = airco;
        }

        @Override
        public EnergyReading grid() {
            return grid;
        }

        @Override
        public EnergyReading solar() {
            return solar;
        }

        @Override
        public EnergyReading house() {
            return house;
        }

        @Override
        public Battery battery() {
            return battery;
        }

        @Override
        public ControllableLoad boiler() {
            return boiler;
        }

        @Override
        public ControllableLoad airco() {
            return airco;
        }
    }
}

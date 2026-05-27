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

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.config.EmsBridgeConfig;

/**
 * Tests for {@link ItemSiteReader} — item-name resolution + sign-convention
 * normalization into the capability {@link Site} snapshot.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class ItemSiteReaderTest {

    private Site read(Map<String, Double> numbers, Set<String> on, EmsBridgeConfig cfg) {
        return ItemSiteReader.read(name -> numbers.getOrDefault(name, Double.NaN), on::contains, cfg);
    }

    @Test
    void canonicalConventionPassesThrough() {
        EmsBridgeConfig cfg = new EmsBridgeConfig(); // defaults: all invert flags false (canonical)
        Site site = read(Map.of("Grid_Power", 2000.0, "Solar_Power", 5000.0, "House_Power", 3000.0, "Battery_Power",
                1500.0, "Battery_SoC", 80.0, "Battery_Reserve_Target_Pct", 30.0), Set.of("Boiler_Switch"), cfg);
        assertEquals(2000.0, site.grid().watts(), 1e-9, "export stays positive");
        assertEquals(5000.0, site.solar().watts(), 1e-9);
        assertEquals(3000.0, site.house().watts(), 1e-9);
        assertEquals(1500.0, site.battery().watts(), 1e-9, "charging stays positive");
        assertEquals(80.0, site.battery().soc(), 1e-9);
        assertEquals(30.0, site.battery().reserveTarget(), 1e-9);
        assertTrue(site.boiler().on());
        assertFalse(site.airco().on());
    }

    @Test
    void invertGridNegatesReading() {
        EmsBridgeConfig cfg = new EmsBridgeConfig();
        cfg.invertGrid = true; // e.g. Fronius: + = import
        Site site = read(Map.of("Grid_Power", 2000.0), Set.of(), cfg);
        assertEquals(-2000.0, site.grid().watts(), 1e-9,
                "import-positive grid is flipped to canonical export-positive");
    }

    @Test
    void invertBatteryNegatesReading() {
        EmsBridgeConfig cfg = new EmsBridgeConfig();
        cfg.invertBattery = true; // item uses + = discharging
        Site site = read(Map.of("Battery_Power", 1500.0), Set.of(), cfg);
        assertEquals(-1500.0, site.battery().watts(), 1e-9,
                "discharge-positive battery flips to canonical charge-positive");
    }

    @Test
    void invertSolarAndHouseNegateReadings() {
        EmsBridgeConfig cfg = new EmsBridgeConfig();
        cfg.invertSolar = true;
        cfg.invertHouse = true;
        Site site = read(Map.of("Solar_Power", -5000.0, "House_Power", -3000.0), Set.of(), cfg);
        assertEquals(5000.0, site.solar().watts(), 1e-9, "negative-producing solar flips to canonical + = producing");
        assertEquals(3000.0, site.house().watts(), 1e-9, "negative-consuming house flips to canonical + = consuming");
    }

    @Test
    void missingItemsAreUnavailable() {
        EmsBridgeConfig cfg = new EmsBridgeConfig();
        Site site = read(Map.of(), Set.of(), cfg);
        assertFalse(site.grid().available());
        assertTrue(Double.isNaN(site.grid().watts()));
        assertFalse(site.battery().socAvailable());
        assertFalse(site.boiler().on());
    }

    @Test
    void honorsConfiguredItemNames() {
        EmsBridgeConfig cfg = new EmsBridgeConfig();
        cfg.gridLoadItem = "My_Grid";
        cfg.batteryPercentageItem = "My_SoC";
        Site site = read(Map.of("My_Grid", -800.0, "My_SoC", 55.0), Set.of(), cfg);
        assertEquals(-800.0, site.grid().watts(), 1e-9);
        assertEquals(55.0, site.battery().soc(), 1e-9);
    }
}

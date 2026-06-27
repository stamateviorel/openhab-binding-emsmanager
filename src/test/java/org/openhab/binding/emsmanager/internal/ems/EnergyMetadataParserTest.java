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
package org.openhab.binding.emsmanager.internal.ems;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EnergyMetadataParser} — pins the {@code energy:} metadata vocabulary that
 * realises Kai Kreuzer's item-marking idea (openhab-core #3478).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class EnergyMetadataParserTest {

    @Test
    void parsesPvProvider() {
        EnergyParticipant p = EnergyMetadataParser
                .parse("Solar_Power", "provider", Map.<String, Object> of("role", "pv")).orElseThrow();
        EnergyProvider prov = assertInstanceOf(EnergyProvider.class, p);
        assertEquals("Solar_Power", prov.id());
        assertEquals(ProviderRole.PV, prov.role());
        assertFalse(prov.controllable());
        assertNull(prov.controlItem());
        assertNull(prov.priceItem());
    }

    @Test
    void parsesGridProviderWithPrice() {
        EnergyProvider prov = assertInstanceOf(EnergyProvider.class,
                EnergyMetadataParser.parse("Grid_Power", "provider",
                        Map.<String, Object> of("role", "grid", "price", "Grid_Price", "schedule", "Grid_Schedule"))
                        .orElseThrow());
        assertEquals(ProviderRole.GRID, prov.role());
        assertEquals("Grid_Price", prov.priceItem());
        assertEquals("Grid_Schedule", prov.scheduleItem());
        assertFalse(prov.controllable());
    }

    @Test
    void parsesControllableBattery() {
        EnergyProvider prov = assertInstanceOf(EnergyProvider.class, EnergyMetadataParser.parse("Battery_Power",
                "provider",
                Map.<String, Object> of("role", "battery", "control", "Battery_Setpoint", "min", -3000, "max", 3000))
                .orElseThrow());
        assertEquals(ProviderRole.BATTERY, prov.role());
        assertTrue(prov.controllable());
        assertEquals("Battery_Setpoint", prov.controlItem());
        assertEquals(-3000.0, prov.minW(), 1e-9);
        assertEquals(3000.0, prov.maxW(), 1e-9);
    }

    @Test
    void parsesSimpleConsumer() {
        EnergyConsumer c = assertInstanceOf(EnergyConsumer.class, EnergyMetadataParser
                .parse("Boiler_Switch", "consumer", Map.<String, Object> of("profile", "simple")).orElseThrow());
        assertEquals("Boiler_Switch", c.id());
        PowerProfile.Simple profile = assertInstanceOf(PowerProfile.Simple.class, c.profile());
        assertEquals("Boiler_Switch", profile.itemName());
    }

    @Test
    void parsesControllableConsumerWithDemand() {
        EnergyConsumer c = assertInstanceOf(EnergyConsumer.class,
                EnergyMetadataParser.parse("Wallbox_Power", "consumer", Map.<String, Object> of("profile",
                        "controllable", "min", 0, "max", 11000, "demandKwh", 10, "deadlineHour", 7)).orElseThrow());
        PowerProfile.Controllable profile = assertInstanceOf(PowerProfile.Controllable.class, c.profile());
        assertEquals(0.0, profile.minW(), 1e-9);
        assertEquals(11000.0, profile.maxW(), 1e-9);
        assertEquals(10.0, c.demandKwh(), 1e-9);
        assertEquals(7, c.deadlineHour());
    }

    @Test
    void consumerDefaultsToSimpleProfile() {
        EnergyConsumer c = assertInstanceOf(EnergyConsumer.class,
                EnergyMetadataParser.parse("X", "consumer", Map.<String, Object> of()).orElseThrow());
        assertInstanceOf(PowerProfile.Simple.class, c.profile());
    }

    @Test
    void rejectsUnknownRoleValueAndNull() {
        assertTrue(EnergyMetadataParser.parse("X", "provider", Map.<String, Object> of("role", "wind")).isEmpty());
        assertTrue(EnergyMetadataParser.parse("X", "frobnicate", Map.<String, Object> of()).isEmpty());
        assertTrue(EnergyMetadataParser.parse("X", null, Map.<String, Object> of()).isEmpty());
    }
}

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
package org.openhab.binding.emsmanager.internal.bridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * Before this existed the bridge accepted no commands at all: every setting was Thing config and
 * the one switch on the dashboard silently echoed its own state back.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class BridgeSetpointsTest {

    @Test
    void aSwitchCommandActuallyChangesShadowMode() {
        assertEquals(new BridgeSetpoints.Setting("shadowMode", true),
                BridgeSetpoints.resolve(CHANNEL_SHADOW_MODE, OnOffType.ON).orElseThrow());
        assertEquals(new BridgeSetpoints.Setting("shadowMode", false),
                BridgeSetpoints.resolve(CHANNEL_SHADOW_MODE, OnOffType.OFF).orElseThrow());
    }

    @Test
    void eachSetpointWritesItsOwnConfigKey() {
        assertEquals("boilerDailyTargetKwh",
                BridgeSetpoints.resolve(CHANNEL_SET_BOILER_TARGET_KWH, new DecimalType(4)).orElseThrow().key());
        assertEquals("boilerReadyByHour",
                BridgeSetpoints.resolve(CHANNEL_SET_BOILER_READY_BY_HOUR, new DecimalType(7)).orElseThrow().key());
        assertEquals("gridSafetyMarginW",
                BridgeSetpoints.resolve(CHANNEL_SET_GRID_SAFETY_MARGIN_W, new DecimalType(500)).orElseThrow().key());
        assertEquals("capacityMinBillableW",
                BridgeSetpoints.resolve(CHANNEL_SET_CAPACITY_BUDGET_W, new DecimalType(2500)).orElseThrow().key());
    }

    @Test
    void hoursAndWattsAreWholeNumbersBecauseTheConfigKeysAreInts() {
        assertEquals(7,
                BridgeSetpoints.resolve(CHANNEL_SET_BOILER_READY_BY_HOUR, new DecimalType(7.6)).orElseThrow().value(),
                "an int config key given a double would fail to apply");
        assertEquals(500, BridgeSetpoints.resolve(CHANNEL_SET_GRID_SAFETY_MARGIN_W, new DecimalType(500.4))
                .orElseThrow().value());
    }

    @Test
    void theHotWaterTargetKeepsItsHalfKilowattHours() {
        assertEquals(4.5, (double) BridgeSetpoints.resolve(CHANNEL_SET_BOILER_TARGET_KWH, new DecimalType(4.5))
                .orElseThrow().value(), 1e-9, "rounding this one would make the 0.5 step on the slider a lie");
    }

    @Test
    void aQuantityCommandIsAcceptedWithItsUnitStripped() {
        assertEquals(750, BridgeSetpoints.resolve(CHANNEL_SET_GRID_SAFETY_MARGIN_W, new QuantityType<>(750, Units.WATT))
                .orElseThrow().value(), "a slider on a Number:Power item sends a quantity, not a bare number");
    }

    @Test
    void outOfRangeValuesAreClampedRatherThanApplied() {
        assertEquals(0,
                BridgeSetpoints.resolve(CHANNEL_SET_GRID_SAFETY_MARGIN_W, new DecimalType(-1)).orElseThrow().value(),
                "a negative grid margin would eat into the breaker limit");
        assertEquals(3000, BridgeSetpoints.resolve(CHANNEL_SET_GRID_SAFETY_MARGIN_W, new DecimalType(999999))
                .orElseThrow().value());
        assertEquals(23,
                BridgeSetpoints.resolve(CHANNEL_SET_BOILER_READY_BY_HOUR, new DecimalType(48)).orElseThrow().value(),
                "hour 48 does not exist");
    }

    @Test
    void anUnknownChannelOrJunkCommandChangesNothing() {
        assertEquals(Optional.empty(), BridgeSetpoints.resolve("someOtherChannel", new DecimalType(1)));
        assertEquals(Optional.empty(),
                BridgeSetpoints.resolve(CHANNEL_SET_BOILER_TARGET_KWH, org.openhab.core.types.RefreshType.REFRESH));
    }

    @Test
    void everySetpointChannelIsReachableFromItsConfigKey() {
        BridgeSetpoints.channelsByConfigKey()
                .forEach((key, channel) -> assertTrue(
                        BridgeSetpoints.resolve(channel, new DecimalType(1)).isPresent()
                                || CHANNEL_SHADOW_MODE.equals(channel),
                        "declared mapping for " + key + " does not resolve"));
    }

    @Test
    void theHandlerPublishesSetpointsWhenItStarts() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path
                .of("src/main/java/org/openhab/binding/" + "emsmanager/internal/bridge/EmsManagerBridgeHandler.java"));
        String initialize = source.substring(source.indexOf("EMS Manager bridge initialized"));

        assertTrue(initialize.contains("publishSettings();"),
                "a control that never publishes its value opens blank and reads as zero");
    }

    @Test
    void aFailedDailyAnalyticsRunIsRetriedRatherThanLosingTheDay() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path
                .of("src/main/java/org/openhab/binding/" + "emsmanager/internal/bridge/EmsManagerBridgeHandler.java"));
        int runStart = source.indexOf("scheduler.execute(() -> {", source.indexOf("Daily analytics") - 4000);
        String beforeRun = source.substring(0, runStart);
        String insideRun = source.substring(runStart);

        assertFalse(beforeRun.endsWith("lastAnalyticsDate = today;\n        "),
                "claiming the day before the work means one failure loses it silently");
        assertTrue(insideRun.contains("lastAnalyticsDate = today;"), "the day is claimed by the run that succeeded");
        assertTrue(insideRun.contains("analyticsRetryAfterMs"), "a failure has to leave a way back");
    }
}

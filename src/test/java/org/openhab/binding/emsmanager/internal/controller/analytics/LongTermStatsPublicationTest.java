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
package org.openhab.binding.emsmanager.internal.controller.analytics;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Holds {@link LongTermStatsController} to the Items it claims to maintain.
 * <p>
 * Three of them - {@code EMS_SelfConsumption_kWh_Month}, {@code EMS_FeedIn_kWh_Month} and
 * {@code EMS_Supply_kWh_Month} - were declared, persisted, restored and read by the dashboard, and written by
 * nothing at all. They held whatever a restart had restored, which looked like data and was not: on 31 August 2026
 * the month showed 27 kWh bought against 880 over the last thirty days.
 * <p>
 * Nothing caught it because every test asked whether a computed number was right, and none asked whether it was ever
 * published. This one walks the declared metric table instead of a hand-written list, so a metric added later is
 * covered the day it appears.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class LongTermStatsPublicationTest {

    @SuppressWarnings("unchecked")
    private List<String[]> metrics() throws ReflectiveOperationException {
        Field field = LongTermStatsController.class.getDeclaredField("METRICS");
        field.setAccessible(true);
        List<String[]> metrics = (List<String[]>) field.get(null);
        assertNotNull(metrics, "the metric table must be readable");
        return metrics;
    }

    /** The spans the controller derives. Every metric must offer all of them. */
    @Test
    void everyMetricPublishesEverySpan() throws ReflectiveOperationException {
        List<String> missing = new ArrayList<>();
        String source = readSource();
        for (String[] metric : metrics()) {
            String prefix = metric[1];
            for (String span : List.of("_Last7Days", "_Last30Days", "_Year")) {
                if (!source.contains("\"" + span + "\"")) {
                    missing.add(prefix + span);
                }
            }
        }
        assertTrue(missing.isEmpty(), "spans never published: " + missing);
    }

    /**
     * Month-to-date has exactly one owner.
     * <p>
     * Two controllers publishing one Item is invisible in the code and obvious in the event log: the three kWh
     * month counters were written by both this controller and the cost accumulator, six milliseconds apart, and
     * oscillated between the two values every tick for as long as it ran. The rule is not "skip the metrics whose
     * source is the month Item" - it is that whoever integrates the accumulator owns the month.
     */
    @Test
    void theMonthToDateBelongsToTheRunningAccumulator() throws ReflectiveOperationException {
        String stats = readSource();
        String cost = readCostAnalyticsSource();

        assertFalse(stats.contains("_Month\""), "this controller must not publish any month Item: "
                + stats.lines().filter(line -> line.contains("_Month\"")).toList());

        List<String> unowned = new ArrayList<>();
        for (String[] metric : metrics()) {
            String monthItem = metric[1] + "_Month";
            String constant = constantNameFor(monthItem);
            if (constant == null || !cost.contains("(" + constant + ",")) {
                unowned.add(monthItem);
            }
        }
        assertTrue(unowned.isEmpty(),
                "dropping the month publish is only safe while the accumulator publishes it: " + unowned);
    }

    /** The constant in the binding's item table whose value is this Item name. */
    private static @org.eclipse.jdt.annotation.Nullable String constantNameFor(String itemName)
            throws ReflectiveOperationException {
        for (Field field : org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.class.getFields()) {
            if (field.getType() == String.class && itemName.equals(field.get(null))) {
                return field.getName();
            }
        }
        return null;
    }

    private String readCostAnalyticsSource() {
        try {
            return java.nio.file.Files.readString(
                    java.nio.file.Path.of("src/main/java/org/openhab/binding/emsmanager/internal/controller/analytics/"
                            + "CostAnalyticsController.java"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cost analytics source not readable", e);
        }
    }

    private String readSource() {
        try {
            java.nio.file.Path path = java.nio.file.Path
                    .of("src/main/java/org/openhab/binding/emsmanager/internal/controller/analytics/"
                            + "LongTermStatsController.java");
            return java.nio.file.Files.readString(path);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("controller source not readable", e);
        }
    }

    @Test
    void theClockHourIsPublishedForHourIndexedWidgets() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/org/openhab/binding/"
                + "emsmanager/internal/controller/analytics/LongTermStatsController.java"));

        assertTrue(source.contains("publish(ITEM_EMS_CLOCK_HOUR, now.getHour()"),
                "the day strip cannot mark the current hour without this");
    }
}

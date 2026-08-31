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
     * The month is the one that was missing, and the one whose absence is invisible - the Item exists and holds a
     * plausible number whether or not anything maintains it.
     */
    @Test
    void aMetricWhoseSourceIsNotTheMonthItemHasItsMonthPublished() throws ReflectiveOperationException {
        String source = readSource();

        assertTrue(source.contains("publish(monthItem"), "the month span must actually be published, not merely named");
        assertTrue(source.contains("!monthItem.equals(m[0])"),
                "a metric read FROM its month Item must not have that Item written back, or the accumulator is fed "
                        + "its own output");
    }

    /**
     * The kWh metrics read a daily counter, so their month Item has no other writer and must be derived here. The
     * EUR metrics read the month Item itself and must not be.
     */
    @Test
    void theEnergyMetricsAreTheOnesNeedingADerivedMonth() throws ReflectiveOperationException {
        List<String> derived = new ArrayList<>();
        List<String> selfSourced = new ArrayList<>();
        for (String[] metric : metrics()) {
            if ((metric[1] + "_Month").equals(metric[0])) {
                selfSourced.add(metric[1]);
            } else {
                derived.add(metric[1]);
            }
        }

        assertEquals(List.of("EMS_SelfConsumption_kWh", "EMS_FeedIn_kWh", "EMS_Supply_kWh"), derived,
                "these three had no writer for their month Item");
        assertEquals(List.of("EMS_Cost_EUR", "EMS_Savings_EUR", "EMS_Earnings_EUR"), selfSourced,
                "these are read from their own month Item and must be left alone");
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
}

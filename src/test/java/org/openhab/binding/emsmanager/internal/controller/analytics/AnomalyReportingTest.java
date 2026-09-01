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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.anomaly.AnomalyDetector;
import org.openhab.binding.emsmanager.internal.devicemeter.DeviceMeterHandler;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.thing.ThingRegistry;

/**
 * Four devices were flagged anomalous at 00:00 on 1 September - a running daily total compared
 * against a distribution of finished days is always far below it, so every device looked like a
 * wild outlier just after midnight.
 * <p>
 * The first attempt at this only made the low side wait until the evening, which is no fix at all
 * on this site: car2 takes about half its daily energy after 23:00, so a 21:00 check would have
 * called it unusually low on a night it charged normally. A part-finished day cannot answer the
 * question at any hour. It is answered once, when the day is complete.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class AnomalyReportingTest {

    private static Path userdata = Path.of("");

    /**
     * Without this the tests read the running installation's own anomaly history, which made them
     * pass for reasons that had nothing to do with the code under test.
     */
    private static final @org.eclipse.jdt.annotation.Nullable String PREVIOUS_USERDATA = System
            .getProperty("openhab.userdata");

    @BeforeAll
    static void isolateFromTheLiveInstall() throws IOException {
        userdata = Files.createTempDirectory("ems-anomaly-test");
        Files.createDirectories(userdata.resolve("cache"));
        userdata.toFile().deleteOnExit();
        System.setProperty("openhab.userdata", userdata.toString());
    }

    @AfterAll
    static void restoreCacheLocation() {
        // Restoring rather than clearing: the build sets this property for the whole JVM, and a
        // test class that wipes it silently un-sandboxes every class that runs after it.
        String previous = PREVIOUS_USERDATA;
        if (previous == null) {
            System.clearProperty("openhab.userdata");
        } else {
            System.setProperty("openhab.userdata", previous);
        }
    }

    /** A device with a real weekday baseline, so the detector has something to fire against. */
    private static void seedBaseline(String deviceId, int dow, double... days) throws IOException {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < days.length; i++) {
            values.append(i > 0 ? "," : "").append(days[i]);
        }
        Files.writeString(userdata.resolve("cache").resolve("emsmanager-anomaly-" + deviceId + ".json"),
                "{\"deviceId\":\"" + deviceId + "\",\"lastAlertMs\":0,\"historyByDow\":{\"" + dow + "\":[" + values
                        + "]}}");
    }

    private static long millisAt(LocalDate day, int hour) {
        return ZonedDateTime.of(day, java.time.LocalTime.of(hour, 0), ZoneId.systemDefault()).toInstant()
                .toEpochMilli();
    }

    /** A device at zero against a median of 17 kWh - security, at 00:01, scoring z = -68. */
    private static AnomalyDetector.Result nearZeroSoFar() {
        return AnomalyDetector.detect(new double[] { 17.0, 17.5, 16.9, 17.2, 17.1 }, 0.01, 0.3, 3.5);
    }

    /** A device already past a whole day's usual - a real signal whenever it happens. */
    private static AnomalyDetector.Result farAboveUsual() {
        return AnomalyDetector.detect(new double[] { 5.0, 5.2, 4.9, 5.1, 5.0 }, 40.0, 0.3, 3.5);
    }

    @Test
    void theDetectorItselfStillFlagsBothDirections() {
        assertTrue(nearZeroSoFar().anomaly(), "the statistics are right; it is the comparison that was wrong");
        assertTrue(farAboveUsual().anomaly());
    }

    @Test
    void aPartFinishedDayCannotBeCalledUnusuallyLow() {
        assertFalse(AnomalyDetectionController.reportableFromRunningTotal(nearZeroSoFar()),
                "every device is below a full day's median until its day is over");
    }

    @Test
    void usingFarMoreThanUsualIsStillReportedImmediately() {
        assertTrue(AnomalyDetectionController.reportableFromRunningTotal(farAboveUsual()),
                "past a whole day's usual is unambiguous however much of the day is left");
    }

    @Test
    void theRunningDetailDoesNotClaimToBeAVerdict() {
        String text = AnomalyDetectionController.describeForTest(0.01, nearZeroSoFar(), false);

        assertTrue(text.contains("tot nu toe"), "it is a reading so far, not a finding of normality");
        assertTrue(text.contains("0.01 kWh"), "car1 read 'Vandaag 25.00 kWh' on a day it used none");
    }

    @Test
    void theDetailIsPublishedOnEveryPassNotOnlyWhenAnAlertFires() throws IOException {
        seedBaseline("dev1", 2, 5.0, 5.1, 4.9, 5.0, 5.2);
        List<String> posted = new ArrayList<>();

        controller(posted).evaluateOne(meterAt("dev1", 5.0), LocalDate.of(2026, 9, 1), 2, millisAt(DAY, 12));

        assertTrue(posted.stream().anyMatch(e -> e.contains("EMS_Anomaly_dev1_Detail")),
                "a device with nothing wrong must still refresh its detail, or the last alert stands forever");
    }

    @Test
    void aQuietDeviceIsNotFlaggedDuringTheDayEvenAtNine() throws IOException {
        // car2's shape: it takes about half its daily energy after 23:00, so at 21:00 on a perfectly
        // normal night it has done almost nothing. The first version of this fix flagged exactly this.
        seedBaseline("dev2", 2, 31.0, 32.0, 31.5, 31.8, 32.1);
        List<String> posted = new ArrayList<>();

        boolean flagged = controller(posted).evaluateOne(meterAt("dev2", 0.0), LocalDate.of(2026, 9, 1), 2,
                millisAt(DAY, 21));

        assertFalse(flagged, "a car that always charges late must not be reported missing at nine in the evening");
        assertTrue(posted.stream().noneMatch(e -> e.contains("dev2_Active") && e.contains("ON")));
    }

    @Test
    void usingFarMoreThanUsualIsFlaggedWhileTheDayIsStillRunning() throws IOException {
        seedBaseline("dev3", 2, 5.0, 5.1, 4.9, 5.0, 5.2);
        List<String> posted = new ArrayList<>();

        boolean flagged = controller(posted).evaluateOne(meterAt("dev3", 40.0), LocalDate.of(2026, 9, 1), 2,
                millisAt(DAY, 12));

        assertTrue(flagged, "eight times a whole day's usual, by lunchtime, is worth saying immediately");
        assertTrue(posted.stream().anyMatch(e -> e.contains("dev3_Active") && e.contains("ON")));
    }

    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

    private AnomalyDetectionController controller(List<String> posted) {
        EventPublisher publisher = mock(EventPublisher.class);
        doAnswer(inv -> {
            posted.add(inv.getArgument(0).toString());
            return null;
        }).when(publisher).post(any(Event.class));
        return new AnomalyDetectionController(publisher, mock(ItemRegistry.class), mock(ThingRegistry.class), 0.3);
    }

    private DeviceMeterHandler meterAt(String id, double kwhToday) {
        DeviceMeterHandler meter = mock(DeviceMeterHandler.class);
        when(meter.deviceId()).thenReturn(id);
        when(meter.kwhToday()).thenReturn(kwhToday);
        when(meter.yesterdayKwh()).thenReturn(Double.NaN);
        return meter;
    }
}

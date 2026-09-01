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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.anomaly.AnomalyDetector;
import org.openhab.binding.emsmanager.internal.devicemeter.DeviceMeterHandler;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.thing.ThingRegistry;

/**
 * Observed live on 2026-09-01: four devices flagged anomalous at 00:00, because a running daily
 * total is compared against a whole day's median and every device is near zero just after midnight.
 * The alert was arithmetic, not information.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class AnomalyReportingTest {

    /** A device at zero against a median of 17 kWh - security, at 00:01, scoring z = -68. */
    private static AnomalyDetector.Result nearZeroAtStartOfDay() {
        return AnomalyDetector.detect(new double[] { 17.0, 17.5, 16.9, 17.2, 17.1 }, 0.01, 0.3, 3.5);
    }

    /** A device already far past a whole day's usual by lunchtime - a real signal. */
    private static AnomalyDetector.Result farAboveUsual() {
        return AnomalyDetector.detect(new double[] { 5.0, 5.2, 4.9, 5.1, 5.0 }, 40.0, 0.3, 3.5);
    }

    @Test
    void theDetectorItselfStillFlagsBothDirections() {
        assertTrue(nearZeroAtStartOfDay().anomaly(), "the statistics are right; it is the timing that was wrong");
        assertTrue(farAboveUsual().anomaly());
    }

    @Test
    void usingLittleSoFarIsNotAnAnomalyEarlyInTheDay() {
        assertTrue(AnomalyDetectionController.suppressedForTest(nearZeroAtStartOfDay(), 0),
                "at midnight every device is below its daily median; that is the calendar, not a fault");
        assertTrue(AnomalyDetectionController.suppressedForTest(nearZeroAtStartOfDay(), 12));
    }

    @Test
    void butItStillCountsOnceTheDayIsEffectivelyOver() {
        assertFalse(AnomalyDetectionController.suppressedForTest(nearZeroAtStartOfDay(), 21),
                "a device that did nothing all day is worth knowing about by evening");
        assertFalse(AnomalyDetectionController.suppressedForTest(nearZeroAtStartOfDay(), 23));
    }

    @Test
    void usingFarMoreThanUsualIsReportedAtAnyHour() {
        assertFalse(AnomalyDetectionController.suppressedForTest(farAboveUsual(), 0),
                "already past a whole day's usual by midnight is a real signal, not a calendar artefact");
        assertFalse(AnomalyDetectionController.suppressedForTest(farAboveUsual(), 12));
    }

    @Test
    void theDetailAlwaysDescribesTodayNotTheLastAlert() {
        String normal = AnomalyDetectionController.describeForTest(0.0, farAboveUsual(), false, 12);

        assertTrue(normal.contains("0.00 kWh"),
                "car1 read 'Vandaag 25.00 kWh' on a day it used none - the text must follow today");
        assertTrue(normal.contains("normaal"), "and must not read like an alert when nothing is wrong");
    }

    @Test
    void aSuppressedLowReadingSaysWhyRatherThanClaimingNormality() {
        String early = AnomalyDetectionController.describeForTest(0.01, nearZeroAtStartOfDay(), false, 6);

        assertTrue(early.contains("vroeg"), "it is not normal and it is not an alert; it is too early to say");
    }

    @Test
    void theDetailIsPublishedOnEveryPassNotOnlyWhenAnAlertFires() {
        EventPublisher publisher = mock(EventPublisher.class);
        List<String> posted = new ArrayList<>();
        doAnswer(inv -> {
            posted.add(inv.getArgument(0).toString());
            return null;
        }).when(publisher).post(any(Event.class));

        AnomalyDetectionController controller = new AnomalyDetectionController(publisher, mock(ItemRegistry.class),
                mock(ThingRegistry.class), 0.3);

        DeviceMeterHandler meter = mock(DeviceMeterHandler.class);
        when(meter.deviceId()).thenReturn("car1");
        when(meter.kwhToday()).thenReturn(0.0);
        when(meter.yesterdayKwh()).thenReturn(Double.NaN);

        // A quiet, entirely unremarkable device: exactly the case that used to publish nothing and
        // leave yesterday's alert text standing.
        controller.evaluateOne(meter, LocalDate.of(2026, 9, 1), 2, 1_756_684_800_000L);

        assertTrue(posted.stream().anyMatch(e -> e.contains("EMS_Anomaly_car1_Detail")),
                "a device with nothing wrong must still refresh its detail, or the last alert stands forever");
    }
}

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
package org.openhab.binding.emsmanager.internal.anomaly;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AnomalyDetector}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class AnomalyDetectorTest {

    @Test
    void steadyHistoryNoAnomaly() {
        double[] history = { 1.0, 1.1, 0.9, 1.05 };
        var r = AnomalyDetector.detect(history, 1.02);
        assertFalse(r.anomaly());
    }

    @Test
    void clearOutlierTriggersAlert() {
        double[] history = { 1.0, 1.1, 0.9, 1.05 };
        var r = AnomalyDetector.detect(history, 5.0); // 5× the normal
        assertTrue(r.anomaly(), "5× normal must alert; z=" + r.zScore());
    }

    @Test
    void singleHistoryOutlierDoesNotShiftBaselineMuch() {
        // Robustness vs mean: with a single huge outlier in history, the median
        // stays anchored. Today at ~normal range should NOT alert.
        double[] history = { 1.0, 1.1, 0.9, 99.0 }; // last week was a laundry day
        var r = AnomalyDetector.detect(history, 1.05);
        assertFalse(r.anomaly(), "1.05 vs median ≈ 1.05 with MAD ≈ 0.1 should not alert");
    }

    @Test
    void belowAbsoluteFloorSuppressed() {
        // Tiny noise on a low-consumption device: z might be huge but delta is small
        double[] history = { 0.01, 0.012, 0.008, 0.011 };
        var r = AnomalyDetector.detect(history, 0.05, 0.3, 3.5);
        assertFalse(r.anomaly(), "delta 0.04 < floor 0.3 should not alert");
    }

    @Test
    void emptyHistoryNoAnomaly() {
        var r = AnomalyDetector.detect(new double[] {}, 5.0);
        assertFalse(r.anomaly());
    }

    @Test
    void exactMedianZeroZ() {
        double[] history = { 2.0, 2.0, 2.0, 2.0 };
        var r = AnomalyDetector.detect(history, 2.0);
        assertEquals(0.0, r.zScore(), 1e-9);
        assertFalse(r.anomaly());
    }

    @Test
    void negativeDeltaCanAlert() {
        // Underuse can also be anomaly (heating off when it shouldn't be, etc.)
        double[] history = { 10.0, 11.0, 9.5, 10.5 };
        var r = AnomalyDetector.detect(history, 1.0); // 90 % drop
        assertTrue(r.anomaly(), "huge drop should alert too");
        assertTrue(r.zScore() < 0);
    }
}

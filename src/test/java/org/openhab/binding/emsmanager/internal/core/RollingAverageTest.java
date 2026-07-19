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

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for the time-windowed rolling average.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class RollingAverageTest {

    @Test
    void emptyIsNaN() {
        assertTrue(Double.isNaN(new RollingAverage(60_000).average()));
    }

    @Test
    void meanOfInWindowSamples() {
        RollingAverage ra = new RollingAverage(60_000);
        ra.add(10, 1000);
        ra.add(20, 2000);
        ra.add(30, 3000);
        assertEquals(20.0, ra.average(), 1e-9);
    }

    @Test
    void dropsExpiredSamples() {
        RollingAverage ra = new RollingAverage(10_000); // 10 s window
        ra.add(100, 0);
        ra.add(200, 5_000);
        // At t=12000, the t=0 sample (age 12s > 10s window) drops.
        ra.add(300, 12_000);
        // Surviving: 200 (t=5000, age 7s) + 300 (t=12000) → mean 250
        assertEquals(250.0, ra.average(), 1e-9);
    }

    @Test
    void ignoresNaN() {
        RollingAverage ra = new RollingAverage(60_000);
        ra.add(10, 1000);
        ra.add(Double.NaN, 2000);
        ra.add(30, 3000);
        assertEquals(20.0, ra.average(), 1e-9, "NaN should be skipped, not poison the mean");
    }
}

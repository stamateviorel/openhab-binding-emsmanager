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
 * Tests for the time-aware EWMA filter — first-sample latching, damping of
 * spikes, propagation of a sustained step input.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class EwmaFilterTest {

    @Test
    void firstSampleLatches() {
        EwmaFilter f = new EwmaFilter(30_000); // tau 30 s
        double v = f.update(1000.0, 1_000L);
        assertEquals(1000.0, v, 1e-9, "first sample becomes the value");
        assertEquals(1000.0, f.value(), 1e-9);
    }

    @Test
    void singleSpikeIsDamped() {
        EwmaFilter f = new EwmaFilter(30_000);
        f.update(0.0, 1_000L);
        // 1-second tick with a 1 kW spike — alpha = 1/(1+30) = 0.032
        double v = f.update(1000.0, 2_000L);
        assertTrue(v < 100.0, "spike must be heavily damped (got " + v + ")");
    }

    @Test
    void sustainedStepPropagates() {
        EwmaFilter f = new EwmaFilter(30_000);
        f.update(0.0, 0L);
        long t = 0;
        for (int i = 0; i < 600; i++) { // 10 minutes of 1-Hz samples
            t += 1000;
            f.update(1000.0, t);
        }
        assertEquals(1000.0, f.value(), 1.0, "after ~20× tau, output reaches input");
    }

    @Test
    void sameTimestampBarelyMoves() {
        // Implementations vary in how they treat dt=0; we only assert that the
        // filter doesn't jump fully to the new value.
        EwmaFilter f = new EwmaFilter(30_000);
        f.update(500.0, 1_000L);
        double v = f.update(1000.0, 1_000L);
        assertTrue(Math.abs(v - 500.0) < 50.0, "near-zero dt: output must stay near previous (got " + v + ")");
    }
}

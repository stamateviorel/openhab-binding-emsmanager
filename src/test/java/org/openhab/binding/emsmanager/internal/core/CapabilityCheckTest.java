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
 * Tests for breaker-headroom + amp-clamp math.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class CapabilityCheckTest {

    @Test
    void clampWithinWindow() {
        assertEquals(16, CapabilityCheck.clampAmps(16.0));
        assertEquals(6, CapabilityCheck.clampAmps(3.0), "below MIN clamps up");
        assertEquals(32, CapabilityCheck.clampAmps(50.0), "above MAX clamps down");
        assertEquals(10, CapabilityCheck.clampAmps(10.9), "floors fractional");
    }

    @Test
    void headroomWithNoOtherLoad() {
        // Raw headroom = full effective limit (53 A), but the method caps the
        // result at MAX_CHARGING_CURRENT_A since no charger can draw more.
        int h = CapabilityCheck.breakerHeadroomA(0, 0, 0, 0, 0, 0);
        assertEquals(CapabilityCheck.MAX_CHARGING_CURRENT_A, h);
    }

    @Test
    void headroomSubtractsOtherChargers() {
        // This charger draws 10 A on L1, total L1 = 30 A (20 A is "other").
        // Raw headroom = 53 − 20 = 33 A → capped to MAX (32 A).
        int h = CapabilityCheck.breakerHeadroomA(10, 0, 0, 30, 0, 0);
        assertEquals(CapabilityCheck.MAX_CHARGING_CURRENT_A, h);
    }

    @Test
    void headroomTakesWorstPhase() {
        // L1 other = 40 A → headroom 13 A; L2 other = 10 A → headroom 43 A.
        // Worst (min) wins.
        int h = CapabilityCheck.breakerHeadroomA(0, 0, 0, 40, 10, 5);
        assertEquals(CapabilityCheck.EFFECTIVE_LIMIT_A - 40, h);
    }

    @Test
    void headroomNeverImpliesUnsafeWhenOverloaded() {
        // Other load already exceeds the effective limit → headroom goes negative,
        // which the caller treats as "pause".
        int h = CapabilityCheck.breakerHeadroomA(0, 0, 0, 60, 0, 0);
        assertTrue(h < CapabilityCheck.MIN_CHARGING_CURRENT_A,
                "overloaded phase must yield headroom below MIN; got " + h);
    }
}

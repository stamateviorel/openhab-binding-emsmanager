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
package org.openhab.binding.emsmanager.internal.heatpump;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ScopCurve}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class ScopCurveTest {

    private static final String DEFAULT = "-15:1.8,-10:2.4,-5:3.1,0:3.5,5:3.9,10:4.3,15:4.7,20:5.0";

    @Test
    void exactKeyReturnsExactValue() {
        ScopCurve c = new ScopCurve(DEFAULT);
        assertEquals(3.5, c.copAt(0.0), 1e-9);
        assertEquals(5.0, c.copAt(20.0), 1e-9);
    }

    @Test
    void linearInterpolationBetweenKeys() {
        ScopCurve c = new ScopCurve(DEFAULT);
        // halfway between 0:3.5 and 5:3.9 → 3.7
        assertEquals(3.7, c.copAt(2.5), 1e-9);
    }

    @Test
    void belowLowestClampsToFirst() {
        ScopCurve c = new ScopCurve(DEFAULT);
        assertEquals(1.8, c.copAt(-25.0), 1e-9, "below lowest key clamps");
    }

    @Test
    void aboveHighestClampsToLast() {
        ScopCurve c = new ScopCurve(DEFAULT);
        assertEquals(5.0, c.copAt(40.0), 1e-9, "above highest key clamps");
    }

    @Test
    void emptyCurveReturnsNaN() {
        assertTrue(Double.isNaN(new ScopCurve("").copAt(10.0)));
        assertTrue(Double.isNaN(new ScopCurve(null).copAt(10.0)));
    }

    @Test
    void malformedEntriesSkipped() {
        ScopCurve c = new ScopCurve("garbage,0:3.5,oops:1.0,10:4.3");
        assertEquals(3.5, c.copAt(0.0), 1e-9);
        assertEquals(4.3, c.copAt(10.0), 1e-9);
    }

    @Test
    void nanInputReturnsNaN() {
        ScopCurve c = new ScopCurve(DEFAULT);
        assertTrue(Double.isNaN(c.copAt(Double.NaN)));
    }
}

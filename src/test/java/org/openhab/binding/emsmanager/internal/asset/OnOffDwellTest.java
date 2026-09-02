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
package org.openhab.binding.emsmanager.internal.asset;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * A switched load is not allowed to flap, whoever asks.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class OnOffDwellTest {

    private static final long MIN = 300_000L;

    @Test
    void theFirstSwitchIsAllowedBecauseNothingIsKnownYet() {
        assertTrue(new OnOffDwell(MIN, MIN).mayLeave("ON", 1_000_000L));
    }

    @Test
    void afterOurOwnSwitchTheNewStateHolds() {
        OnOffDwell d = new OnOffDwell(MIN, MIN);
        d.switched("ON", 1_000_000L);
        assertFalse(d.mayLeave("ON", 1_000_000L + 5_000L), "five seconds later it must stay on");
        assertEquals(295L, d.remainingSeconds("ON", 1_000_000L + 5_000L));
        assertTrue(d.mayLeave("ON", 1_000_000L + MIN), "after the minimum it may go");
    }

    @Test
    void aChangeMadeByHandRestartsTheClockToo() {
        OnOffDwell d = new OnOffDwell(MIN, MIN);
        d.switched("OFF", 1_000_000L);
        d.observe("ON", 1_100_000L); // someone pressed the switch
        assertFalse(d.mayLeave("ON", 1_100_000L + 60_000L), "their ON holds like ours would");
        assertTrue(d.mayLeave("ON", 1_100_000L + MIN));
    }

    @Test
    void onAndOffCanHaveDifferentMinimums() {
        OnOffDwell d = new OnOffDwell(600_000L, 60_000L);
        d.switched("OFF", 0L);
        assertTrue(d.mayLeave("OFF", 60_000L));
        d.switched("ON", 60_000L);
        assertFalse(d.mayLeave("ON", 60_000L + 300_000L), "a compressor gets its longer run");
    }
}

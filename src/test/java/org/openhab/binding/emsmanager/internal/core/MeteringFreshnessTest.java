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
 * A metering bridge that stops updating leaves plausible-looking values behind; freshness has
 * to come from when the Items last spoke, not from what they say.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class MeteringFreshnessTest {

    private static final long NOW = 1_000_000_000L;

    @Test
    void aRecentUpdateIsFresh() {
        assertTrue(ContextBuilder.freshFrom(NOW, NOW - 5_000L, true));
    }

    @Test
    void frozenValuesAreStaleHoweverPlausibleTheyLook() {
        assertFalse(ContextBuilder.freshFrom(NOW, NOW - ContextBuilder.METERING_STALE_MS, true));
        assertFalse(ContextBuilder.freshFrom(NOW, NOW - 3_600_000L, true));
    }

    @Test
    void meteringThatHasNotSpokenYetIsNotTrusted() {
        assertFalse(ContextBuilder.freshFrom(NOW, 0L, true),
                "items exist but have never updated since the watch began");
    }

    @Test
    void aSiteWithoutMeteringItemsFallsBackToPlausibility() {
        assertTrue(ContextBuilder.freshFrom(NOW, -1L, true));
        assertFalse(ContextBuilder.freshFrom(NOW, -1L, false));
    }
}

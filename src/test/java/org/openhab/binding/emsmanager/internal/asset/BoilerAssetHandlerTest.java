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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.openhab.core.events.EventPublisher;

/**
 * Two controllers taking turns must not turn into a relay chattering every tick.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class BoilerAssetHandlerTest {

    private static EnergyContext boiler(boolean on) {
        return new EnergyContext(Instant.now(), 0, 0, 0, 0, 0, 50, 30, false, 0, EnergyContext.Mode.BALANCED, Map.of(),
                0, 0, 0, true, on, false, true, 0, Double.NaN, false, 0, 0, 60_000L, 0.30, new double[0], Double.NaN,
                Double.NaN, false);
    }

    private static SetpointRequest ask(boolean on, String who) {
        return new SetpointRequest("boiler", SetpointRequest.Kind.ONOFF, on ? 1.0 : 0.0, 100, who, "test");
    }

    @Test
    void theSecondFlipInsideTheDwellIsHeld() {
        EventPublisher publisher = mock(EventPublisher.class);
        BoilerAssetHandler h = new BoilerAssetHandler(publisher, "Boiler", 300_000L);

        assertTrue(h.apply(ask(true, "production-shaving"), boiler(false), false), "first switch goes through");
        assertFalse(h.apply(ask(false, "solar-surplus-dispatcher"), boiler(true), false),
                "five seconds later the other controller must wait");
        verify(publisher, times(1)).post(any());
    }

    /**
     * The four ways a write does not happen used to be one {@code false}, which is why the site had a
     * dispatch log and no dispatch history.
     */
    @Test
    void eachWayOfNotWritingSaysWhichOneItWas() {
        assertEquals(AssetWriteOutcome.UNCHANGED, handler(300_000L).write(ask(true, "a"), boiler(true), false),
                "already on, so nobody is asking for anything");

        BoilerAssetHandler dwelling = handler(300_000L);
        assertEquals(AssetWriteOutcome.WROTE, dwelling.write(ask(true, "a"), boiler(false), false));
        assertEquals(AssetWriteOutcome.HELD, dwelling.write(ask(false, "b"), boiler(true), false),
                "wanted, and blocked by the dwell rather than by the state");

        assertEquals(AssetWriteOutcome.REFUSED,
                handler(0L).write(new SetpointRequest("boiler", SetpointRequest.Kind.AMPS, 16, 100, "c", "wrong kind"),
                        boiler(true), false));
        assertEquals(AssetWriteOutcome.SHADOWED, handler(0L).write(ask(true, "d"), boiler(false), true));
    }

    private static BoilerAssetHandler handler(long dwellMs) {
        return new BoilerAssetHandler(mock(EventPublisher.class), "Boiler", dwellMs);
    }

    @Test
    void aZeroDwellKeepsTheOldBehaviour() {
        EventPublisher publisher = mock(EventPublisher.class);
        BoilerAssetHandler h = new BoilerAssetHandler(publisher, "Boiler", 0L);
        assertTrue(h.apply(ask(true, "a"), boiler(false), false));
        assertTrue(h.apply(ask(false, "b"), boiler(true), false));
        verify(publisher, times(2)).post(any());
    }
}

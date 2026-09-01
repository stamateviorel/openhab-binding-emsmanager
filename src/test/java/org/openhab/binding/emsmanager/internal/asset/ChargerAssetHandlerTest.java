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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;

/**
 * The last code between a decision and a 32 A contactor, and it had no tests at all.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class ChargerAssetHandlerTest {

    private final List<String> sent = new ArrayList<>();

    private EventPublisher recordingPublisher() {
        EventPublisher publisher = mock(EventPublisher.class);
        doAnswer(inv -> {
            sent.add(inv.getArgument(0).toString());
            return null;
        }).when(publisher).post(any(Event.class));
        return publisher;
    }

    private ChargerAssetHandler handler(int breakerLimitA) {
        return new ChargerAssetHandler(recordingPublisher(), "car1", "Car1_Pause", "Car1_Limit", "Car1_Charging",
                breakerLimitA);
    }

    private EnergyContext contextWithCarAt(double currentLimitA) {
        EnergyContext ctx = mock(EnergyContext.class);
        CarSnapshot car = mock(CarSnapshot.class);
        when(car.currentLimitA()).thenReturn(currentLimitA);
        when(car.paused()).thenReturn(false);
        when(ctx.cars()).thenReturn(Map.of("car1", car));
        return ctx;
    }

    private static SetpointRequest amps(double value) {
        return new SetpointRequest("car1", SetpointRequest.Kind.AMPS, value, 50, "test", "test");
    }

    @Test
    void anAmpsRequestReachesTheChargerItem() {
        assertTrue(handler(32).apply(amps(16), contextWithCarAt(6), false));

        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("Car1_Limit"), "the current limit item is the one commanded");
        assertTrue(sent.get(0).contains("16"));
    }

    @Test
    void aRequestAboveTheBreakerIsClampedNotPassedOn() {
        handler(32).apply(amps(200), contextWithCarAt(6), false);

        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("32"), "200 A must never reach a 32 A charger");
        assertFalse(sent.get(0).contains("200"));
    }

    @Test
    void aNegativeRequestBecomesZeroRatherThanNonsense() {
        handler(32).apply(amps(-5), contextWithCarAt(6), false);

        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("0"), "a negative current has no meaning at a charger");
    }

    @Test
    void shadowModeDecidesButWritesNothing() {
        assertFalse(handler(32).apply(amps(16), contextWithCarAt(6), true));

        assertTrue(sent.isEmpty(), "shadow mode exists so the binding can be trusted to touch nothing");
    }

    @Test
    void thereIsNoWriteWhenTheChargerAlreadySitsAtTheValue() {
        assertFalse(handler(32).apply(amps(16), contextWithCarAt(16), false));

        assertTrue(sent.isEmpty(), "resending a value the charger already holds is chatter on the wire");
    }

    @Test
    void aClampedRequestStillDedupesAgainstTheClampedValue() {
        ChargerAssetHandler handler = handler(32);
        handler.apply(amps(200), contextWithCarAt(6), false);
        handler.apply(amps(999), contextWithCarAt(6), false);

        assertEquals(1, sent.size(), "both clamp to 32 A, so the second is the same command as the first");
    }

    @Test
    void anUnconfiguredItemIsNotWrittenTo() {
        ChargerAssetHandler handler = new ChargerAssetHandler(recordingPublisher(), "car1", null, null, null, 32);

        assertFalse(handler.apply(amps(16), contextWithCarAt(6), false));
        assertTrue(sent.isEmpty(), "a charger with no current-limit item must not have one invented");
    }

    @Test
    void aZeroOrNegativeBreakerLimitFallsBackRatherThanBlockingEverything() {
        handler(0).apply(amps(16), contextWithCarAt(6), false);

        assertEquals(1, sent.size(), "a misconfigured limit must not clamp every charge to zero");
        assertTrue(sent.get(0).contains("16"));
    }
}

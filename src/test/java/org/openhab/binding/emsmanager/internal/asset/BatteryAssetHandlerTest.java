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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.config.BatteryConfig;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointDedupe;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.types.DecimalType;

/**
 * A setpoint goes to the inverter once, and again only when the item no longer holds it.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class BatteryAssetHandlerTest {

    private static final String ITEM = "EMS_Battery_Setpoint_W";

    private final List<String> sent = new ArrayList<>();

    private EventPublisher recordingPublisher() {
        EventPublisher publisher = mock(EventPublisher.class);
        doAnswer(inv -> {
            sent.add(inv.getArgument(0).toString());
            return null;
        }).when(publisher).post(any(Event.class));
        return publisher;
    }

    private BatteryConfig config(String mode) {
        BatteryConfig c = new BatteryConfig();
        c.controlMode = mode;
        c.setpointItemName = ITEM;
        c.minSetpointW = -3000;
        c.maxSetpointW = 3000;
        c.fixedSetpointW = 500;
        return c;
    }

    /** ACK window 0, so nothing but the value-level dedupe stands between two identical requests. */
    private BatteryAssetHandler handler(String mode, @org.eclipse.jdt.annotation.Nullable ItemRegistry registry) {
        return new BatteryAssetHandler(recordingPublisher(), config(mode), registry, new SetpointDedupe(0));
    }

    private ItemRegistry registryHolding(int watts) {
        Item item = mock(Item.class);
        when(item.getState()).thenReturn(new DecimalType(watts));
        ItemRegistry registry = mock(ItemRegistry.class);
        when(registry.get(ITEM)).thenReturn(item);
        return registry;
    }

    private static SetpointRequest watts(double value) {
        return new SetpointRequest("battery", SetpointRequest.Kind.WATTS_BATTERY, value, 80, "test", "test");
    }

    private EnergyContext ctx() {
        return mock(EnergyContext.class);
    }

    @Test
    void theSameSetpointIsNotRepostedEveryTick() {
        BatteryAssetHandler h = handler("auto", null);

        assertTrue(h.apply(watts(2000), ctx(), false));
        assertFalse(h.apply(watts(2000), ctx(), false));
        assertFalse(h.apply(watts(2000), ctx(), false));

        assertEquals(1, sent.size(), "147 identical commands in 36 minutes is what this test is for");
    }

    @Test
    void aDifferentSetpointIsSent() {
        BatteryAssetHandler h = handler("auto", null);
        h.apply(watts(2000), ctx(), false);

        assertTrue(h.apply(watts(0), ctx(), false));
        assertEquals(2, sent.size());
    }

    @Test
    void aValueTheItemAlreadyHoldsIsNotSent() {
        BatteryAssetHandler h = handler("auto", registryHolding(2000));

        assertFalse(h.apply(watts(2000), ctx(), false));
        assertTrue(sent.isEmpty());
    }

    /** Someone else moved the item (a rule, the UI): the item disagrees with what we sent, so send again. */
    @Test
    void isResentWhenTheItemNoLongerHoldsIt() {
        BatteryAssetHandler h = handler("auto", registryHolding(0));

        assertTrue(h.apply(watts(2000), ctx(), false));
        assertTrue(h.apply(watts(2000), ctx(), false), "the item reads 0 W, so the 2000 W must go out again");
        assertEquals(2, sent.size());
    }

    @Test
    void fixedModeHoldsTheFixedValueWhateverIsAsked() {
        BatteryAssetHandler h = handler("fixed", null);

        assertTrue(h.apply(watts(-2000), ctx(), false));
        assertFalse(h.apply(watts(2000), ctx(), false), "same fixed value → deduped");

        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("500"), "the fixed 500 W, not the requested −2000 W");
        assertFalse(sent.get(0).contains("2000"));
    }

    @Test
    void fixedModeClampsToTheConfiguredRange() {
        BatteryConfig c = config("fixed");
        c.fixedSetpointW = 9000;
        new BatteryAssetHandler(recordingPublisher(), c, null, new SetpointDedupe(0)).apply(watts(0), ctx(), false);

        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("3000"));
    }

    @Test
    void readonlyStillRejects() {
        assertFalse(handler("readonly", null).apply(watts(2000), ctx(), false));
        assertTrue(sent.isEmpty());
    }

    @Test
    void shadowWritesNothing() {
        assertFalse(handler("auto", null).apply(watts(2000), ctx(), true));
        assertTrue(sent.isEmpty());
    }
}

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
package org.openhab.binding.emsmanager.internal.bridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.types.DecimalType;

/**
 * An items-file reload replaces every Item instance; a watch attached to the old one goes deaf.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class ItemWatchTest {

    private final Map<String, Item> registryItems = new ConcurrentHashMap<>();
    private final ItemRegistry registry = mock(ItemRegistry.class);
    private final Map<String, String> seen = new ConcurrentHashMap<>();

    /** Item listeners are notified on a pool thread, so the test has to wait for them. */
    private @org.eclipse.jdt.annotation.Nullable String awaitSeen(String name) throws InterruptedException {
        for (int i = 0; i < 200 && !seen.containsKey(name); i++) {
            Thread.sleep(10);
        }
        return seen.get(name);
    }

    private long awaitUpdate(ItemWatch w, String name) throws InterruptedException {
        for (int i = 0; i < 200 && w.newestUpdateMs(List.of(name)) == 0L; i++) {
            Thread.sleep(10);
        }
        return w.newestUpdateMs(List.of(name));
    }

    private ItemWatch watchOf(String... names) {
        when(registry.get(anyString())).thenAnswer(call -> registryItems.get(call.<String> getArgument(0)));
        ItemWatch w = new ItemWatch(registry, List.of(names), (name, state) -> seen.put(name, state.toString()));
        w.start();
        return w;
    }

    @Test
    void aChangeOnAWatchedItemIsReported() throws InterruptedException {
        NumberItem amps = new NumberItem("Amps_car1_L1");
        registryItems.put(amps.getName(), amps);
        ItemWatch w = watchOf("Amps_car1_L1");

        amps.setState(new DecimalType(12));

        assertEquals("12", awaitSeen("Amps_car1_L1"));
        assertEquals(1, w.attachedCount());
    }

    @Test
    void theWatchFollowsAReloadedItem() throws InterruptedException {
        NumberItem old = new NumberItem("Car1_Mode");
        registryItems.put(old.getName(), old);
        ItemWatch w = watchOf("Car1_Mode");

        NumberItem reloaded = new NumberItem("Car1_Mode");
        registryItems.put(reloaded.getName(), reloaded);
        w.updated(old, reloaded);
        reloaded.setState(new DecimalType(2));

        assertEquals("2", awaitSeen("Car1_Mode"), "the new instance must be heard");
        seen.clear();
        old.setState(new DecimalType(3));
        Thread.sleep(200);
        assertNull(seen.get("Car1_Mode"), "the dead instance must not");
    }

    @Test
    void anItemThatAppearsLaterIsPickedUp() throws InterruptedException {
        ItemWatch w = watchOf("Amps_car2_L1");
        assertEquals(0, w.attachedCount());

        NumberItem late = new NumberItem("Amps_car2_L1");
        w.added(late);
        late.setState(new DecimalType(7));

        assertEquals("7", awaitSeen("Amps_car2_L1"));
    }

    @Test
    void aSameValueUpdateStillCountsAsProofOfLife() throws InterruptedException {
        NumberItem amps = new NumberItem("Amps_car1_L1");
        registryItems.put(amps.getName(), amps);
        ItemWatch w = watchOf("Amps_car1_L1");
        assertEquals(0L, w.newestUpdateMs(List.of("Amps_car1_L1")), "nothing has spoken yet");

        amps.setState(new DecimalType(0));
        long first = awaitUpdate(w, "Amps_car1_L1");
        assertTrue(first > 0);
        Thread.sleep(20);
        amps.setState(new DecimalType(0));
        Thread.sleep(200);
        assertTrue(w.newestUpdateMs(List.of("Amps_car1_L1")) > first, "an unchanged value must still refresh liveness");
        assertTrue(w.watchesAnyOf(List.of("Amps_car1_L1")));
        assertFalse(w.watchesAnyOf(List.of("Amps_car9_L1")));
    }

    @Test
    void stoppingDetachesEverything() throws InterruptedException {
        NumberItem amps = new NumberItem("Amps_car1_L1");
        registryItems.put(amps.getName(), amps);
        ItemWatch w = watchOf("Amps_car1_L1");
        w.stop();

        amps.setState(new DecimalType(5));
        Thread.sleep(200);

        assertNull(seen.get("Amps_car1_L1"));
        verify(registry).removeRegistryChangeListener(w);
    }
}

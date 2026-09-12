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
package org.openhab.binding.emsmanager.internal.ems;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openhab.binding.emsmanager.internal.asset.AssetWriteOutcome;
import org.openhab.binding.emsmanager.internal.ledger.ActionJournal;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.types.Command;

/**
 * Tests for {@link EmsActuator#toCommand} — the mapping from an {@link EmsAction} to the item
 * command (the actuation half of #3478).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class EmsActuatorTest {

    @TempDir
    java.nio.file.@org.eclipse.jdt.annotation.Nullable Path dir;

    /**
     * This is the one write path that does not pass an asset handler. A journal it can slip past is
     * worse than no journal, because it reads as a complete record.
     */
    @Test
    void theEnginesOwnWritesReachTheJournalToo() throws Exception {
        java.nio.file.Path d = dir;
        assertNotNull(d);
        ActionJournal journal = new ActionJournal(d.resolve("j.json"), () -> 1_757_000_000_000L);
        ItemRegistry items = mock(ItemRegistry.class);
        when(items.getItem(anyString())).thenAnswer(call -> {
            String name = (String) call.getArgument(0);
            if ("Missing".equals(name)) {
                throw new ItemNotFoundException(name);
            }
            return new NumberItem(name);
        });
        EventPublisher events = mock(EventPublisher.class);
        EmsActuator actuator = new EmsActuator(events, items, journal);

        assertTrue(actuator.apply(new EmsAction("Wallbox", EmsAction.Kind.SET_WATTS, 4200.0, "surplus")));
        assertFalse(actuator.apply(new EmsAction("Missing", EmsAction.Kind.ONOFF, 1.0, "no such item")));

        verify(events, times(1)).post(any(Event.class));
        assertEquals(2, journal.size(), "the write and the refusal are both facts about what the EMS did");
        assertEquals(AssetWriteOutcome.REFUSED, journal.entries().get(0).outcome);
        assertEquals("ems-engine", journal.entries().get(1).controller);
    }

    @Test
    void onOffMapsToSwitchCommand() {
        assertEquals(OnOffType.ON, EmsActuator.toCommand(new EmsAction("X", EmsAction.Kind.ONOFF, 1.0, "")));
        assertEquals(OnOffType.OFF, EmsActuator.toCommand(new EmsAction("X", EmsAction.Kind.ONOFF, 0.0, "")));
    }

    @Test
    void setWattsMapsToNumericCommand() {
        Command c = EmsActuator.toCommand(new EmsAction("Wallbox", EmsAction.Kind.SET_WATTS, 4200.0, ""));
        DecimalType d = assertInstanceOf(DecimalType.class, c);
        assertEquals(4200.0, d.doubleValue(), 1e-9);
    }

    @Test
    void setModeMapsToStringCommandAndHoldToNone() {
        org.openhab.core.types.Command c = EmsActuator
                .toCommand(new EmsAction("WP", EmsAction.Kind.SET_MODE, 2.0, "encouraged", "mode"));
        org.openhab.core.library.types.StringType st = assertInstanceOf(org.openhab.core.library.types.StringType.class,
                c);
        assertEquals("encouraged", st.toString());
        assertNull(EmsActuator.toCommand(new EmsAction("WP", EmsAction.Kind.SET_MODE, 0.0, "gated")),
                "mode without a string (shed by a gate) -> no command");
        assertNull(EmsActuator.toCommand(new EmsAction("DW", EmsAction.Kind.HOLD, 0.0, "waiting")),
                "hold -> no command");
    }
}

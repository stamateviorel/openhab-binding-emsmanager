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
package org.openhab.binding.emsmanager.internal.ledger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openhab.binding.emsmanager.internal.asset.AssetWriteOutcome;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemStateEvent;
import org.openhab.core.library.items.StringItem;
import org.openhab.core.types.UnDefType;

/**
 * The journal speaks in asset ids and enum names; the page has to read like a sentence.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class JournalPublisherTest {

    @TempDir
    java.nio.file.@org.eclipse.jdt.annotation.Nullable Path dir;

    private final Map<String, String> published = new HashMap<>();
    private final AtomicLong clock = new AtomicLong(1_757_000_000_000L);

    private ActionJournal journal() {
        java.nio.file.Path d = dir;
        assertNotNull(d);
        return new ActionJournal(d.resolve("journal.json"), clock::get);
    }

    private JournalPublisher publisher(ActionJournal journal) throws Exception {
        EventPublisher events = mock(EventPublisher.class);
        doAnswer(call -> {
            Event e = call.getArgument(0);
            if (e instanceof ItemStateEvent state) {
                published.put(state.getItemName(), state.getItemState().toString());
            }
            return null;
        }).when(events).post(any(Event.class));

        ItemRegistry items = mock(ItemRegistry.class);
        when(items.getItem(anyString())).thenAnswer(call -> {
            String name = call.getArgument(0);
            StringItem item = new StringItem(name);
            item.setState(UnDefType.NULL);
            return item;
        });
        return new JournalPublisher(events, items, journal, ZoneId.of("Europe/Brussels"));
    }

    @Test
    public void anEmptyJournalSaysSoRatherThanPublishingNothing() throws Exception {
        publisher(journal()).publish(LocalDate.of(2026, 9, 12));

        assertEquals("[]", published.get("EMS_Journal_Rows_JSON"));
        assertEquals("Nothing has changed yet", published.get("EMS_Journal_Label"));
    }

    /** "Nothing dispatched" on its own reads as "the EMS is idle", which is a different claim. */
    @Test
    public void aQuietHistoryUnderAStandingRequestSaysSo() throws Exception {
        ActionJournal j = journal();
        j.record("battery", "WATTS_BATTERY", "2000 W", "battery-tou", "peak", AssetWriteOutcome.UNCHANGED);

        publisher(j).publish(LocalDate.of(2026, 9, 12));

        assertEquals("Nothing has changed yet · 1 request standing", published.get("EMS_Journal_Label"));
        String standing = published.get("EMS_Standing_Rows_JSON");
        assertNotNull(standing);
        assertTrue(standing.contains("already there"), standing);
    }

    @Test
    public void anActionReadsAsASentence() throws Exception {
        ActionJournal j = journal();
        j.record("car3", "AMPS", "16 A", "ev-coordinator", "sun covers it", AssetWriteOutcome.WROTE);

        publisher(j).publish(LocalDate.of(2026, 9, 12));

        String rows = published.get("EMS_Journal_Rows_JSON");
        assertNotNull(rows);
        assertTrue(rows.contains("\"l\":\"Auto 3 → 16 A\""), rows);
        assertTrue(rows.contains("ev-coordinator · sun covers it"), rows);
        assertTrue(rows.contains("\"o\":\"sent\""), rows);
    }

    @Test
    public void aFilterOnlyChangesWhatIsShown() throws Exception {
        ActionJournal j = journal();
        j.record("boiler", "ONOFF", "ON", "solar-surplus-dispatcher", "spare sun", AssetWriteOutcome.WROTE);
        clock.addAndGet(5_000);
        j.record("ems-battery", "WATTS_BATTERY", "2000 W", "battery-tou", "readonly", AssetWriteOutcome.REFUSED);

        JournalPublisher p = publisher(j);
        p.publish(LocalDate.of(2026, 9, 12));
        String all = published.get("EMS_Journal_Rows_JSON");
        assertNotNull(all);
        assertTrue(all.contains("Boiler") && all.contains("Ems battery"), all);
        assertEquals("2.0", published.get("EMS_Journal_Count"), "the count is of what was kept, not of what is shown");
    }

    @Test
    public void theRowsAreValidJsonWhateverAControllerPutInItsReason() throws Exception {
        ActionJournal j = journal();
        j.record("boiler", "ONOFF", "ON", "solar-surplus-dispatcher", "he said \"now\"\nand meant it",
                AssetWriteOutcome.WROTE);

        publisher(j).publish(LocalDate.of(2026, 9, 12));

        String rows = published.get("EMS_Journal_Rows_JSON");
        assertNotNull(rows);
        // a repeater handed a string that does not parse renders nothing at all, silently
        com.google.gson.JsonArray parsed = new com.google.gson.Gson().fromJson(rows, com.google.gson.JsonArray.class);
        assertNotNull(parsed, rows);
        assertEquals(1, parsed.size(), rows);
    }

    @Test
    public void aDayOldActionIsDatedRatherThanLeftAtAClockTime() throws Exception {
        ActionJournal j = journal();
        j.record("boiler", "ONOFF", "OFF", "peak-shaving", "tier 3", AssetWriteOutcome.WROTE);

        java.time.LocalDate day = java.time.Instant.ofEpochMilli(clock.get()).atZone(ZoneId.of("Europe/Brussels"))
                .toLocalDate();
        publisher(j).publish(day.plusDays(2));

        String rows = published.get("EMS_Journal_Rows_JSON");
        assertNotNull(rows);
        assertFalse(rows.contains("\"d\":\"today\""), rows);
        assertFalse(rows.contains("\"d\":\"yesterday\""), rows);
    }
}

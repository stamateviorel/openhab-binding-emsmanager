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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openhab.binding.emsmanager.internal.asset.AssetWriteOutcome;

/**
 * The dispatch loop offers this thousands of entries a day and almost all of them are "nothing
 * changed". What it keeps, and what it merges, is the whole design.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class ActionJournalTest {

    @TempDir
    @org.eclipse.jdt.annotation.Nullable
    Path dir;

    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

    private ActionJournal journal() {
        return new ActionJournal(file(), clock::get);
    }

    private Path file() {
        Path d = dir;
        assertNotNull(d);
        return d.resolve("journal.json");
    }

    private static ActionJournal.@org.eclipse.jdt.annotation.Nullable Entry boiler(ActionJournal j, String value,
            AssetWriteOutcome outcome) {
        return j.record("boiler", "ONOFF", value, "solar-surplus-dispatcher", "3.2 kW spare", outcome);
    }

    @Test
    public void anAssetSittingWhereItWasAskedToSitIsNotAnEvent() {
        ActionJournal j = journal();

        assertNull(boiler(j, "ON", AssetWriteOutcome.UNCHANGED));
        assertEquals(0, j.size(), "seventeen thousand of these a day is the reason the journal is readable");
    }

    @Test
    public void everyOtherOutcomeIsKeptNewestFirst() {
        ActionJournal j = journal();

        boiler(j, "ON", AssetWriteOutcome.WROTE);
        clock.addAndGet(60_000);
        boiler(j, "OFF", AssetWriteOutcome.WROTE);

        List<ActionJournal.Entry> entries = j.entries();
        assertEquals(2, entries.size());
        assertEquals("OFF", entries.get(0).value, "the newest action is the one you read first");
    }

    @Test
    public void aRepeatedRefusalIsOneLineWithACount() {
        ActionJournal j = journal();

        for (int i = 0; i < 240; i++) {
            clock.addAndGet(5_000);
            j.record("ems-battery", "WATTS_BATTERY", "2000 W", "battery-tou", "readonly", AssetWriteOutcome.REFUSED);
        }

        assertEquals(1, j.size(), "a battery refusing every tick is one fact, not 240");
        ActionJournal.Entry only = j.entries().get(0);
        assertEquals(240, only.count);
        assertTrue(only.lastAt > only.firstAt, "the line has to say how long it has been going on");
    }

    @Test
    public void aChangeOfValueStartsItsOwnLine() {
        ActionJournal j = journal();

        j.record("car3", "AMPS", "6 A", "ev-coordinator", "sun only", AssetWriteOutcome.WROTE);
        clock.addAndGet(5_000);
        j.record("car3", "AMPS", "16 A", "ev-coordinator", "more sun", AssetWriteOutcome.WROTE);

        assertEquals(2, j.size());
    }

    @Test
    public void twoAssetsTakingTurnsDoNotSwallowEachOther() {
        ActionJournal j = journal();

        for (int i = 0; i < 10; i++) {
            clock.addAndGet(5_000);
            j.record("ems-battery", "WATTS_BATTERY", "0 W", "battery-tou", "readonly", AssetWriteOutcome.REFUSED);
            j.record("airco", "ONOFF", "OFF", "peak-shaving", "holding", AssetWriteOutcome.HELD);
        }

        // coalescing that only looked at the newest entry would have written twenty lines here
        assertEquals(2, j.size());
    }

    @Test
    public void anOutcomeThatHasStoodAllDayStartsAFreshLine() {
        ActionJournal j = journal();

        j.record("airco", "ONOFF", "OFF", "peak-shaving", "tier 4", AssetWriteOutcome.HELD);
        clock.addAndGet(7L * 60L * 60L * 1000L);
        j.record("airco", "ONOFF", "OFF", "peak-shaving", "tier 4", AssetWriteOutcome.HELD);

        assertEquals(2, j.size(), "a hold this morning and a hold this evening are two different events");
    }

    @Test
    public void theRingStopsGrowing() {
        ActionJournal j = journal();

        for (int i = 0; i < ActionJournal.MAX_ENTRIES + 50; i++) {
            clock.addAndGet(5_000);
            j.record("car" + i, "AMPS", i + " A", "ev-coordinator", "test", AssetWriteOutcome.WROTE);
        }

        assertEquals(ActionJournal.MAX_ENTRIES, j.size());
        assertTrue(j.entries().get(0).value.contains(String.valueOf(ActionJournal.MAX_ENTRIES + 49)),
                "the ring drops the oldest, never the newest");
    }

    @Test
    public void itSurvivesARestart() throws Exception {
        ActionJournal first = journal();
        first.record("boiler", "ONOFF", "ON", "solar-surplus-dispatcher", "3.2 kW spare", AssetWriteOutcome.WROTE);
        clock.addAndGet(5_000);
        first.record("car3", "AMPS", "16 A", "ev-coordinator", "sun", AssetWriteOutcome.WROTE);
        first.flush();
        assertTrue(Files.exists(file()));

        ActionJournal reopened = journal();
        assertEquals(2, reopened.size());
        ActionJournal.Entry newest = reopened.entries().get(0);
        assertEquals("car3", newest.asset);
        assertEquals("ev-coordinator", newest.controller);
        assertEquals(AssetWriteOutcome.WROTE, newest.outcome);
    }

    @Test
    public void anUnreadableJournalCostsTheHistoryAndNotTheBinding() throws Exception {
        Files.writeString(file(), "{ this is not json");

        ActionJournal j = journal();

        assertEquals(0, j.size());
        assertNotNull(j.record("boiler", "ONOFF", "ON", "x", "y", AssetWriteOutcome.WROTE), "and it keeps recording");
    }
}

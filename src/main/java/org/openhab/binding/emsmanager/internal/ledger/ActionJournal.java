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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.asset.AssetWriteOutcome;
import org.openhab.binding.emsmanager.internal.util.CachePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * What the EMS actually did, kept so it can be read back.
 *
 * <p>
 * Until this existed the binding's own decisions were the one thing about the site with no history:
 * every kWh was in the database and not one action was, so "why did the boiler come on at four" had
 * no answer beyond a log file that rotates.
 *
 * <p>
 * The dispatch loop offers this every write of every asset, which is some seventeen thousand calls a
 * day, and almost all of them are an asset already sitting where it was asked to sit. Those are
 * dropped ({@link AssetWriteOutcome#UNCHANGED}). Of the rest, a repeat of the same outcome on the
 * same asset coalesces into the entry already there and bumps its count, so a battery in readonly
 * mode refusing a setpoint every five seconds is one line that says so, not a day of noise.
 *
 * <p>
 * Dropping "already there" is right for a history and wrong for the present, because it goes silent
 * exactly when a standing request is not producing anything: the EMS can ask for a 2 kW discharge
 * every five seconds for an hour, the battery can sit at zero, and a log of changes has nothing to
 * say about it. So every request, dropped or kept, also updates {@link #standing()} — what each asset
 * is being asked for right now, since when, and how that request is being answered.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ActionJournal {

    /** Roughly a fortnight of a busy site, and small enough to ship to a page whole. */
    public static final int MAX_ENTRIES = 400;

    static final String CACHE_FILE = "emsmanager-journal.json";

    /** Coalescing stops here: an outcome that has stood this long starts a new line. */
    private static final long COALESCE_WINDOW_MS = 6L * 60L * 60L * 1000L;

    /** A coalesce bump alone is not worth a disk write more often than this. */
    private static final long FLUSH_INTERVAL_MS = 60L * 1000L;

    private static final Logger LOGGER = LoggerFactory.getLogger(ActionJournal.class);
    private static final Gson GSON = new Gson();

    /** One thing the EMS did, or declined to do. Mutable only in its tail fields. */
    public static final class Entry {
        public final long firstAt;
        public final String asset;
        public final String what;
        public final String value;
        public final String controller;
        public final String reason;
        public final AssetWriteOutcome outcome;
        public long lastAt;
        public int count;

        Entry(long at, String asset, String what, String value, String controller, String reason,
                AssetWriteOutcome outcome) {
            this.firstAt = at;
            this.lastAt = at;
            this.asset = asset;
            this.what = what;
            this.value = value;
            this.controller = controller;
            this.reason = reason;
            this.outcome = outcome;
            this.count = 1;
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Map<String, Entry> latestPerAsset = new HashMap<>();
    private final Map<String, Entry> standing = new LinkedHashMap<>();
    private final Path file;
    private final LongSupplier clock;

    private boolean dirty;
    private long lastWriteMs;

    public ActionJournal() {
        this(CachePaths.cacheFile(CACHE_FILE), System::currentTimeMillis);
    }

    /** Visible for testing: lets a test own both the file and the clock. */
    public ActionJournal(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
        load();
    }

    /**
     * Offer one dispatch result to the journal.
     *
     * @return the entry it became, or null where the outcome was not worth keeping
     */
    public synchronized @Nullable Entry record(String asset, String what, String value, String controller,
            String reason, AssetWriteOutcome outcome) {
        long now = clock.getAsLong();
        updateStanding(asset, what, value, controller, reason, outcome, now);
        if (!outcome.worthRecording()) {
            return null;
        }
        // keyed by asset AND kind: a charger is commanded with amps and pause in the same tick, and an
        // index keyed by asset alone made those two take turns evicting each other, so neither ever
        // coalesced and every repeat cost a ring slot and a disk write
        Entry latest = latestPerAsset.get(asset + '|' + what);
        if (latest != null && latest.outcome == outcome && latest.what.equals(what) && latest.value.equals(value)
                && now - latest.firstAt < COALESCE_WINDOW_MS) {
            latest.lastAt = now;
            latest.count++;
            dirty = true;
            flushIfDue(now);
            return latest;
        }
        Entry entry = new Entry(now, asset, what, value, controller, reason, outcome);
        entries.addFirst(entry);
        latestPerAsset.put(asset + '|' + what, entry);
        while (entries.size() > MAX_ENTRIES) {
            Entry dropped = entries.removeLast();
            latestPerAsset.remove(dropped.asset + '|' + dropped.what, dropped);
        }
        dirty = true;
        // a genuinely new action is rare enough to be worth the disk write it costs
        write(now);
        return entry;
    }

    /**
     * What each asset is being asked for right now. Unlike the history this keeps "already there",
     * because "the EMS is asking and the asset already holds it" is the answer to why nothing is
     * happening.
     */
    private void updateStanding(String asset, String what, String value, String controller, String reason,
            AssetWriteOutcome outcome, long now) {
        String key = asset + '|' + what;
        Entry current = standing.get(key);
        if (current != null && current.outcome == outcome && current.value.equals(value)) {
            current.lastAt = now;
            current.count++;
            return;
        }
        Entry fresh = new Entry(now, asset, what, value, controller, reason, outcome);
        standing.put(key, fresh);
    }

    /** One line per asset the EMS is currently commanding, oldest standing first. */
    public synchronized List<Entry> standing() {
        return new ArrayList<>(standing.values());
    }

    /** Drops standing requests nothing has repeated lately, so a finished one does not linger. */
    public synchronized void expireStanding(long olderThanMs) {
        long cutoff = clock.getAsLong() - olderThanMs;
        standing.values().removeIf(e -> e.lastAt < cutoff);
    }

    /** Newest first. A copy, so a publisher can walk it while the dispatch loop runs. */
    public synchronized List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    public synchronized int size() {
        return entries.size();
    }

    /** Persist a pending coalesce bump. Called on shutdown, where the interval no longer applies. */
    public synchronized void flush() {
        if (dirty) {
            write(clock.getAsLong());
        }
    }

    private void flushIfDue(long now) {
        if (dirty && now - lastWriteMs >= FLUSH_INTERVAL_MS) {
            write(now);
        }
    }

    private void write(long now) {
        try {
            JsonArray arr = new JsonArray();
            for (Entry e : entries) {
                JsonObject o = new JsonObject();
                o.addProperty("at", e.firstAt);
                o.addProperty("last", e.lastAt);
                o.addProperty("asset", e.asset);
                o.addProperty("what", e.what);
                o.addProperty("value", e.value);
                o.addProperty("by", e.controller);
                o.addProperty("why", e.reason);
                o.addProperty("outcome", e.outcome.name());
                o.addProperty("n", e.count);
                arr.add(o);
            }
            JsonObject root = new JsonObject();
            root.add("entries", arr);
            CachePaths.writeAtomic(file, GSON.toJson(root));
            dirty = false;
            lastWriteMs = now;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("ActionJournal.save: {}", e.getMessage());
        }
    }

    private void load() {
        try {
            if (!Files.exists(file)) {
                return;
            }
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (root == null || !root.has("entries")) {
                return;
            }
            for (JsonElement el : root.getAsJsonArray("entries")) {
                JsonObject o = el.getAsJsonObject();
                Entry e = new Entry(o.get("at").getAsLong(), o.get("asset").getAsString(), o.get("what").getAsString(),
                        o.get("value").getAsString(), o.get("by").getAsString(), o.get("why").getAsString(),
                        AssetWriteOutcome.valueOf(o.get("outcome").getAsString()));
                e.lastAt = o.has("last") ? o.get("last").getAsLong() : e.firstAt;
                e.count = o.has("n") ? o.get("n").getAsInt() : 1;
                entries.addLast(e);
                latestPerAsset.putIfAbsent(e.asset + '|' + e.what, e);
            }
            LOGGER.info("ActionJournal: restored {} entries", entries.size());
        } catch (Throwable t) {
            // a journal is a record, not state anything depends on: an unreadable one starts empty
            entries.clear();
            latestPerAsset.clear();
            LOGGER.warn("ActionJournal: cache unreadable, starting empty: {}", t.getMessage());
        }
    }
}

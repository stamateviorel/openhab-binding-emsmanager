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
        if (!outcome.worthRecording()) {
            return null;
        }
        long now = clock.getAsLong();
        Entry latest = latestPerAsset.get(asset);
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
        latestPerAsset.put(asset, entry);
        while (entries.size() > MAX_ENTRIES) {
            Entry dropped = entries.removeLast();
            latestPerAsset.remove(dropped.asset, dropped);
        }
        dirty = true;
        // a genuinely new action is rare enough to be worth the disk write it costs
        write(now);
        return entry;
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
                latestPerAsset.putIfAbsent(e.asset, e);
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

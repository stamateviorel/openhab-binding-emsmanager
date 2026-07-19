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
package org.openhab.binding.emsmanager.internal.forecast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Tiny file-backed cache for the latest forecast snapshot. Survives bundle
 * restarts so we don't burn an API call on every reload — important for the
 * forecast.solar free tier (12 calls/h).
 *
 * <p>
 * Stored at {@code /var/lib/openhab/cache/emsmanager-forecast-cache.json}
 * as a small JSON blob. Best-effort: any IO/parse failure returns {@code null}
 * and the caller proceeds without the cache.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ForecastCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(ForecastCache.class);
    private static final Path CACHE_PATH = Path.of("/var/lib/openhab/cache/emsmanager-forecast-cache.json");
    private static final Gson GSON = new Gson();

    private ForecastCache() {
    }

    /** @return cached snapshot, or {@code null} if missing/unreadable/unparseable. */
    public static @Nullable ForecastSnapshot load() {
        try {
            if (!Files.exists(CACHE_PATH)) {
                return null;
            }
            String raw = Files.readString(CACHE_PATH);
            JsonObject obj = GSON.fromJson(raw, JsonObject.class);
            if (obj == null) {
                return null;
            }
            long refreshedAtMs = obj.has("refreshedAtMs") ? obj.get("refreshedAtMs").getAsLong() : 0L;
            double nowW = optD(obj, "nowW");
            double next1hWh = optD(obj, "next1hWh");
            double next3hWh = optD(obj, "next3hWh");
            double next6hWh = optD(obj, "next6hWh");
            double todayKwh = optD(obj, "todayKwh");
            double tomorrowKwh = optD(obj, "tomorrowKwh");
            Long peakMs = obj.has("peakTodayAtMs") && !obj.get("peakTodayAtMs").isJsonNull()
                    ? obj.get("peakTodayAtMs").getAsLong()
                    : null;
            Integer rateLimit = obj.has("rateLimitRemaining") && !obj.get("rateLimitRemaining").isJsonNull()
                    ? obj.get("rateLimitRemaining").getAsInt()
                    : null;
            String hourlyCsv = obj.has("hourlyTodayCsv") && !obj.get("hourlyTodayCsv").isJsonNull()
                    ? obj.get("hourlyTodayCsv").getAsString()
                    : "";
            String seriesCsv = obj.has("hourlySeriesCsv") && !obj.get("hourlySeriesCsv").isJsonNull()
                    ? obj.get("hourlySeriesCsv").getAsString()
                    : "";
            return new ForecastSnapshot(Instant.ofEpochMilli(refreshedAtMs), nowW, next1hWh, next3hWh, next6hWh,
                    todayKwh, tomorrowKwh, peakMs == null ? null : Instant.ofEpochMilli(peakMs), rateLimit, null,
                    hourlyCsv, seriesCsv);
        } catch (Throwable t) {
            LOGGER.debug("ForecastCache.load: {}", t.getMessage());
            return null;
        }
    }

    /** Best-effort save; never throws. */
    public static void save(ForecastSnapshot snap) {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("refreshedAtMs", snap.refreshedAt().toEpochMilli());
            obj.addProperty("nowW", snap.nowW());
            obj.addProperty("next1hWh", snap.next1hWh());
            obj.addProperty("next3hWh", snap.next3hWh());
            obj.addProperty("next6hWh", snap.next6hWh());
            obj.addProperty("todayKwh", snap.todayKwh());
            obj.addProperty("tomorrowKwh", snap.tomorrowKwh());
            Instant peak = snap.peakTodayAt();
            if (peak != null) {
                obj.addProperty("peakTodayAtMs", peak.toEpochMilli());
            }
            Integer rateLimit = snap.rateLimitRemaining();
            if (rateLimit != null) {
                obj.addProperty("rateLimitRemaining", rateLimit);
            }
            if (snap.hourlyTodayCsv() != null && !snap.hourlyTodayCsv().isEmpty()) {
                obj.addProperty("hourlyTodayCsv", snap.hourlyTodayCsv());
                obj.addProperty("hourlySeriesCsv", snap.hourlySeriesCsv());
            }
            Files.writeString(CACHE_PATH, GSON.toJson(obj), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            LOGGER.debug("ForecastCache.save: {}", e.getMessage());
        }
    }

    private static double optD(JsonObject obj, String key) {
        try {
            return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsDouble() : Double.NaN;
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}

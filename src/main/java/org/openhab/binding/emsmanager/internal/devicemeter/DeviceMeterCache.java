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
package org.openhab.binding.emsmanager.internal.devicemeter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Per-device file-backed cache of:
 * <ul>
 * <li>kwhToday running total (so we don't lose it across bundle restarts)</li>
 * <li>365-day ring buffer of past daily totals (yesterday / last7 / last30 / year math)</li>
 * <li>lastSeenDay marker for rollover detection</li>
 * </ul>
 *
 * <p>
 * Stored at {@code /var/lib/openhab/cache/emsmanager-device-meter-<id>.json}
 * where {@code <id>} is the Thing's id (e.g. "boiler", "car1"). One file per
 * device, small JSON.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class DeviceMeterCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceMeterCache.class);
    private static final Path CACHE_DIR = Path.of("/var/lib/openhab/cache");
    private static final Gson GSON = new Gson();
    private static final int RING_DAYS = 365;

    public static record State(LocalDate lastSeenDay, double kwhToday, Deque<Double> ring) {
    }

    private DeviceMeterCache() {
    }

    public static @Nullable State load(String deviceId) {
        try {
            Path path = pathFor(deviceId);
            if (!Files.exists(path)) {
                return null;
            }
            String raw = Files.readString(path);
            JsonObject obj = GSON.fromJson(raw, JsonObject.class);
            if (obj == null) {
                return null;
            }
            LocalDate lastDay = obj.has("lastSeenDay") ? LocalDate.parse(obj.get("lastSeenDay").getAsString())
                    : LocalDate.MIN;
            double today = obj.has("kwhToday") ? obj.get("kwhToday").getAsDouble() : 0.0;
            Deque<Double> ring = new ArrayDeque<>();
            JsonArray arr = obj.has("ring") ? obj.getAsJsonArray("ring") : null;
            if (arr != null) {
                for (JsonElement el : arr) {
                    try {
                        ring.addLast(el.getAsDouble());
                    } catch (Exception ignore) {
                    }
                }
            }
            return new State(lastDay, today, ring);
        } catch (Throwable t) {
            LOGGER.debug("DeviceMeterCache.load[{}]: {}", deviceId, t.getMessage());
            return null;
        }
    }

    public static void save(String deviceId, State s) {
        try {
            Files.createDirectories(CACHE_DIR);
            JsonObject obj = new JsonObject();
            obj.addProperty("lastSeenDay", s.lastSeenDay().toString());
            obj.addProperty("kwhToday", s.kwhToday());
            JsonArray arr = new JsonArray();
            for (Double v : s.ring()) {
                arr.add(v);
            }
            obj.add("ring", arr);
            Files.writeString(pathFor(deviceId), GSON.toJson(obj), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Throwable t) {
            LOGGER.debug("DeviceMeterCache.save[{}]: {}", deviceId, t.getMessage());
        }
    }

    public static int maxRingDays() {
        return RING_DAYS;
    }

    private static Path pathFor(String deviceId) {
        return CACHE_DIR.resolve("emsmanager-device-meter-" + sanitize(deviceId) + ".json");
    }

    private static String sanitize(String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}

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
package org.openhab.binding.emsmanager.internal.tariff;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.tariff.spot.HourlyPrices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Tiny file-backed cache for the latest dynamic-spot price schedule.
 * Survives bundle restarts — important to avoid burning API calls
 * (ENTSO-E has a published rate budget, Tibber generously sized but
 * still polite to cache).
 *
 * <p>
 * Stored at {@code /var/lib/openhab/cache/emsmanager-tariff-cache.json}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class TariffCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(TariffCache.class);
    private static final Path CACHE_PATH = Path.of("/var/lib/openhab/cache/emsmanager-tariff-cache.json");
    private static final Gson GSON = new Gson();

    private TariffCache() {
    }

    public static @Nullable HourlyPrices load() {
        try {
            if (!Files.exists(CACHE_PATH)) {
                return null;
            }
            String raw = Files.readString(CACHE_PATH);
            JsonObject obj = GSON.fromJson(raw, JsonObject.class);
            if (obj == null) {
                return null;
            }
            long fetchedAtMs = obj.has("fetchedAtMs") ? obj.get("fetchedAtMs").getAsLong() : 0L;
            JsonObject pricesObj = obj.getAsJsonObject("prices");
            if (pricesObj == null) {
                return null;
            }
            TreeMap<Instant, Double> out = new TreeMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> e : pricesObj.entrySet()) {
                out.put(Instant.ofEpochMilli(Long.parseLong(e.getKey())), e.getValue().getAsDouble());
            }
            return new HourlyPrices(out, Instant.ofEpochMilli(fetchedAtMs), null);
        } catch (Throwable t) {
            LOGGER.debug("TariffCache.load: {}", t.getMessage());
            return null;
        }
    }

    public static void save(HourlyPrices prices) {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("fetchedAtMs", prices.fetchedAt().toEpochMilli());
            JsonObject pricesObj = new JsonObject();
            for (Map.Entry<Instant, Double> e : prices.prices().entrySet()) {
                pricesObj.addProperty(String.valueOf(e.getKey().toEpochMilli()), e.getValue());
            }
            obj.add("prices", pricesObj);
            Files.writeString(CACHE_PATH, GSON.toJson(obj), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Throwable t) {
            LOGGER.debug("TariffCache.save: {}", t.getMessage());
        }
    }
}

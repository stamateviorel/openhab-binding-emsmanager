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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.core.CapacityTariffTracker;
import org.openhab.binding.emsmanager.internal.util.CachePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * The month-to-date capacity peak on disk.
 * <p>
 * The peak is what the grid operator bills on, and every restart or settings change used to reset
 * it to zero, after which the shaving controller believed the month's worst quarter-hour was the
 * billing floor and shed loads for nothing until a new peak had been recorded the hard way.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class CapacityPeakStore {

    private static final String CACHE_FILE = "emsmanager-capacity-cache.json";
    private static final Logger LOGGER = LoggerFactory.getLogger(CapacityPeakStore.class);
    private static final Gson GSON = new Gson();

    private CapacityPeakStore() {
    }

    public static CapacityTariffTracker.@Nullable Persisted load() {
        try {
            Path path = CachePaths.cacheFile(CACHE_FILE);
            if (!Files.exists(path)) {
                return null;
            }
            JsonObject o = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (o == null || !o.has("year") || !o.has("month") || !o.has("monthlyPeakW")) {
                return null;
            }
            return new CapacityTariffTracker.Persisted(o.get("year").getAsInt(), o.get("month").getAsInt(),
                    o.get("monthlyPeakW").getAsDouble());
        } catch (Throwable t) {
            LOGGER.warn("Capacity peak cache unreadable, the month starts from zero: {}", t.getMessage());
            return null;
        }
    }

    public static void save(CapacityTariffTracker.Persisted p) {
        try {
            Path path = CachePaths.cacheFile(CACHE_FILE);
            Files.createDirectories(path.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("year", p.year());
            o.addProperty("month", p.month());
            o.addProperty("monthlyPeakW", p.monthlyPeakW());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(o), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            LOGGER.warn("Capacity peak could not be saved: {}", t.getMessage());
        }
    }
}

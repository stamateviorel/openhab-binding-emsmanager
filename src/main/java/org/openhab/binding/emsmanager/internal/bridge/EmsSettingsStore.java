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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.config.EmsBridgeConfig;
import org.openhab.binding.emsmanager.internal.util.CachePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Setpoints changed from the dashboard, kept by the binding rather than in Thing configuration.
 *
 * Thing configuration is the obvious place for them and the wrong one: a Thing defined in a
 * {@code .things} file is owned by that file, so {@code updateConfiguration} does not stick and the
 * control silently does nothing - which is worse than having no control at all. These overrides sit
 * on top of whatever the Thing declares, so a value the user never touched still follows the file.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class EmsSettingsStore {

    private static final String CACHE_FILE = "emsmanager-settings.json";
    private static final Logger LOGGER = LoggerFactory.getLogger(EmsSettingsStore.class);
    private static final Gson GSON = new Gson();

    private final Map<String, Object> overrides = new ConcurrentHashMap<>();

    public EmsSettingsStore() {
        load();
    }

    /** Overlay the stored overrides onto the Thing's own configuration. */
    public EmsBridgeConfig applyTo(EmsBridgeConfig cfg) {
        Object shadow = overrides.get("shadowMode");
        if (shadow instanceof Boolean b) {
            cfg.shadowMode = b;
        }
        cfg.boilerDailyTargetKwh = num("boilerDailyTargetKwh", cfg.boilerDailyTargetKwh);
        cfg.boilerReadyByHour = (int) num("boilerReadyByHour", cfg.boilerReadyByHour);
        cfg.gridSafetyMarginW = (int) num("gridSafetyMarginW", cfg.gridSafetyMarginW);
        cfg.capacityMinBillableW = (int) num("capacityMinBillableW", cfg.capacityMinBillableW);
        return cfg;
    }

    public void put(String key, Object value) {
        overrides.put(key, value);
        save();
    }

    public boolean isEmpty() {
        return overrides.isEmpty();
    }

    public Map<String, Object> asMap() {
        return Map.copyOf(overrides);
    }

    private double num(String key, double fallback) {
        Object v = overrides.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private void load() {
        try {
            Path path = CachePaths.cacheFile(CACHE_FILE);
            if (!Files.exists(path)) {
                return;
            }
            JsonObject o = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (o == null) {
                return;
            }
            for (String key : o.keySet()) {
                var el = o.get(key);
                if (el.isJsonNull()) {
                    continue;
                }
                if (el.getAsJsonPrimitive().isBoolean()) {
                    overrides.put(key, el.getAsBoolean());
                } else if (el.getAsJsonPrimitive().isNumber()) {
                    overrides.put(key, el.getAsDouble());
                }
            }
            LOGGER.info("EMS settings overrides loaded: {}", overrides);
        } catch (Throwable t) {
            LOGGER.warn("EMS settings unreadable, falling back to Thing configuration: {}", t.getMessage());
        }
    }

    private void save() {
        try {
            Path path = CachePaths.cacheFile(CACHE_FILE);
            Files.createDirectories(path.getParent());
            JsonObject o = new JsonObject();
            overrides.forEach((k, v) -> {
                if (v instanceof Boolean b) {
                    o.addProperty(k, b);
                } else if (v instanceof Number n) {
                    o.addProperty(k, n);
                }
            });
            Files.writeString(path, GSON.toJson(o), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Throwable t) {
            LOGGER.warn("EMS settings could not be saved, change will not survive a restart: {}", t.getMessage());
        }
    }
}

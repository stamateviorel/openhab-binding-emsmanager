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
import org.eclipse.jdt.annotation.Nullable;
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

    /** An override and the file value it was made against. */
    private record Override(Object value, @Nullable Object fileValue) {
    }

    private final Map<String, Override> overrides = new ConcurrentHashMap<>();

    public EmsSettingsStore() {
        load();
    }

    /**
     * Overlay the stored overrides onto the Thing's own configuration.
     * <p>
     * An override only stands while the file still says what it said when the override was made.
     * Once the file changes, the file wins and the override is dropped - otherwise a value touched
     * once from the dashboard could never be set from the file again, which for {@code shadowMode}
     * is the kill switch.
     */
    public EmsBridgeConfig applyTo(EmsBridgeConfig cfg) {
        cfg.shadowMode = bool("shadowMode", cfg.shadowMode);
        cfg.boilerDailyTargetKwh = num("boilerDailyTargetKwh", cfg.boilerDailyTargetKwh);
        cfg.boilerReadyByHour = (int) num("boilerReadyByHour", cfg.boilerReadyByHour);
        cfg.gridSafetyMarginW = (int) num("gridSafetyMarginW", cfg.gridSafetyMarginW);
        cfg.capacityMinBillableW = (int) num("capacityMinBillableW", cfg.capacityMinBillableW);
        return cfg;
    }

    /**
     * @param fileValue what the Thing configuration says right now, so a later file edit can be
     *            told apart from the value the override was made against
     */
    public void put(String key, Object value, @Nullable Object fileValue) {
        overrides.put(key, new Override(value, fileValue));
        save();
    }

    public boolean isEmpty() {
        return overrides.isEmpty();
    }

    public Map<String, Object> asMap() {
        Map<String, Object> out = new java.util.HashMap<>();
        overrides.forEach((k, o) -> out.put(k, o.value()));
        return Map.copyOf(out);
    }

    private boolean bool(String key, boolean fileValue) {
        Override o = live(key, fileValue);
        return o != null && o.value() instanceof Boolean b ? b : fileValue;
    }

    private double num(String key, double fileValue) {
        Override o = live(key, fileValue);
        return o != null && o.value() instanceof Number n ? n.doubleValue() : fileValue;
    }

    /** The override for {@code key} if the file still matches it; otherwise it is retired. */
    private @Nullable Override live(String key, Object fileValue) {
        Override o = overrides.get(key);
        if (o == null) {
            return null;
        }
        Object base = o.fileValue();
        if (base == null) {
            // legacy entry with no baseline: adopt the current file value as its baseline
            Override adopted = new Override(o.value(), fileValue);
            overrides.put(key, adopted);
            save();
            return adopted;
        }
        if (sameValue(base, fileValue)) {
            return o;
        }
        overrides.remove(key);
        save();
        LOGGER.info("EMS setting {} changed in the Thing configuration; the dashboard override is dropped", key);
        return null;
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Math.abs(x.doubleValue() - y.doubleValue()) < 1e-9;
        }
        return a.equals(b);
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
                if (el.isJsonObject()) {
                    JsonObject entry = el.getAsJsonObject();
                    Object value = primitive(entry.get("value"));
                    if (value != null) {
                        overrides.put(key, new Override(value, primitive(entry.get("fileValue"))));
                    }
                } else {
                    Object value = primitive(el);
                    if (value != null) {
                        overrides.put(key, new Override(value, null));
                    }
                }
            }
            LOGGER.info("EMS settings overrides loaded: {}", asMap());
        } catch (Throwable t) {
            LOGGER.warn("EMS settings unreadable, falling back to Thing configuration: {}", t.getMessage());
        }
    }

    private static @Nullable Object primitive(com.google.gson.@Nullable JsonElement el) {
        if (el == null || !el.isJsonPrimitive()) {
            return null;
        }
        if (el.getAsJsonPrimitive().isBoolean()) {
            return el.getAsBoolean();
        }
        if (el.getAsJsonPrimitive().isNumber()) {
            return el.getAsDouble();
        }
        return null;
    }

    private void save() {
        try {
            Path path = CachePaths.cacheFile(CACHE_FILE);
            Files.createDirectories(path.getParent());
            JsonObject o = new JsonObject();
            overrides.forEach((k, ov) -> {
                JsonObject entry = new JsonObject();
                add(entry, "value", ov.value());
                add(entry, "fileValue", ov.fileValue());
                o.add(k, entry);
            });
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(o), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            LOGGER.warn("EMS settings could not be saved, change will not survive a restart: {}", t.getMessage());
        }
    }

    private static void add(JsonObject o, String key, @Nullable Object v) {
        if (v instanceof Boolean b) {
            o.addProperty(key, b);
        } else if (v instanceof Number n) {
            o.addProperty(key, n);
        }
    }
}

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
package org.openhab.binding.emsmanager.internal.anomaly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Per-device anomaly state — last-N kWh totals grouped by day-of-week,
 * + last-alert timestamps for cooldown.
 *
 * <p>
 * Persists to {@code /var/lib/openhab/cache/emsmanager-anomaly-<id>.json}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class AnomalyState {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnomalyState.class);
    private static final Path CACHE_DIR = Path.of("/var/lib/openhab/cache");
    private static final int HISTORY_PER_DOW = 4;
    private static final Gson GSON = new Gson();

    public final String deviceId;
    /** day-of-week (1=Monday..7=Sunday) → recent N kWh values. */
    public final Map<Integer, List<Double>> historyByDow = new HashMap<>();
    public long lastAlertMs;

    private AnomalyState(String deviceId) {
        this.deviceId = deviceId;
    }

    public static AnomalyState load(String deviceId) {
        Path p = CACHE_DIR.resolve("emsmanager-anomaly-" + deviceId + ".json");
        AnomalyState st = new AnomalyState(deviceId);
        if (!Files.exists(p)) {
            return st;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            if (obj.has("lastAlertMs")) {
                st.lastAlertMs = obj.get("lastAlertMs").getAsLong();
            }
            if (obj.has("historyByDow")) {
                JsonObject map = obj.getAsJsonObject("historyByDow");
                for (var e : map.entrySet()) {
                    List<Double> list = new ArrayList<>();
                    var arr = e.getValue().getAsJsonArray();
                    for (var v : arr) {
                        list.add(v.getAsDouble());
                    }
                    st.historyByDow.put(Integer.parseInt(e.getKey()), list);
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("AnomalyState.load[{}]: {}", deviceId, t.toString());
        }
        return st;
    }

    public void save() {
        try {
            Files.createDirectories(CACHE_DIR);
            JsonObject obj = new JsonObject();
            obj.addProperty("lastAlertMs", lastAlertMs);
            obj.add("historyByDow", GSON.toJsonTree(historyByDow));
            Files.writeString(CACHE_DIR.resolve("emsmanager-anomaly-" + deviceId + ".json"), GSON.toJson(obj),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            LOGGER.debug("AnomalyState.save[{}]: {}", deviceId, e.toString());
        }
    }

    /** Get the history list for a DoW, returning a clone (defensive). */
    public double[] historyFor(int dow) {
        List<Double> list = historyByDow.get(dow);
        if (list == null) {
            return new double[0];
        }
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    /** Append today's value to that DoW's history; bound to {@link #HISTORY_PER_DOW}. */
    public void recordEndOfDay(int dow, double todayKwh) {
        List<Double> list = historyByDow.computeIfAbsent(dow, k -> new ArrayList<>());
        list.add(todayKwh);
        while (list.size() > HISTORY_PER_DOW) {
            list.remove(0);
        }
    }
}

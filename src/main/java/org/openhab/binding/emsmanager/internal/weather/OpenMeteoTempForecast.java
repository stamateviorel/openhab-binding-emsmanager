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
package org.openhab.binding.emsmanager.internal.weather;

import java.time.Instant;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Hourly outdoor-temperature forecast from Open-Meteo (free, no API key).
 *
 * <p>
 * Feeds the heat-pump {@code ThermalPlanner} a real 24-hour outdoor-temp
 * curve instead of holding the current temperature constant. The forecast
 * quality directly improves pre-heat timing: knowing it'll be −5 °C at 06:00
 * lets the planner pre-heat in a cheap overnight window rather than reacting
 * at dawn.
 *
 * <p>
 * Endpoint:
 * {@code https://api.open-meteo.com/v1/forecast?latitude=..&longitude=..&hourly=temperature_2m&forecast_days=2&timeformat=unixtime}
 * Response shape:
 * 
 * <pre>
 * { "hourly": { "time": [unixSeconds...], "temperature_2m": [°C...] } }
 * </pre>
 *
 * <p>
 * Cached + refreshed at most hourly (forecast doesn't move intraday enough
 * to matter). On fetch failure the last good forecast is kept and the next
 * attempt waits {@link #RETRY_SPACING_MS} - this runs inside the tick, and
 * retrying every tick through an outage stalls every controller for the HTTP
 * timeout each time.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class OpenMeteoTempForecast {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenMeteoTempForecast.class);
    private static final long REFRESH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(60);
    static final long RETRY_SPACING_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int HTTP_TIMEOUT_MS = 10_000;

    private final HttpClient httpClient;
    private final double lat;
    private final double lon;
    private final LongSupplier clock;
    private final AtomicReference<@Nullable Cached> cache = new AtomicReference<>();
    private volatile long lastAttemptMs = 0L;

    private record Cached(TreeMap<Instant, Double> byHour, long fetchedAtMs) {
    }

    public OpenMeteoTempForecast(HttpClient httpClient, double lat, double lon) {
        this(httpClient, lat, lon, System::currentTimeMillis);
    }

    OpenMeteoTempForecast(HttpClient httpClient, double lat, double lon, LongSupplier clock) {
        this.httpClient = httpClient;
        this.lat = lat;
        this.lon = lon;
        this.clock = clock;
    }

    /**
     * Returns {@code count} hourly outdoor temps starting at the hour containing
     * {@code start}. Missing hours are filled with the nearest known value, or
     * the array is empty if no forecast is available at all.
     */
    public double[] hourlyFrom(Instant start, int count) {
        refreshIfStale();
        Cached c = cache.get();
        if (c == null || c.byHour().isEmpty()) {
            return new double[0];
        }
        double[] out = new double[count];
        long hourMs = 3_600_000L;
        long base = (start.toEpochMilli() / hourMs) * hourMs;
        for (int i = 0; i < count; i++) {
            Instant h = Instant.ofEpochMilli(base + (long) i * hourMs);
            Double v = c.byHour().get(h);
            if (v == null) {
                var floor = c.byHour().floorEntry(h);
                var ceil = c.byHour().ceilingEntry(h);
                if (floor != null) {
                    v = floor.getValue();
                } else if (ceil != null) {
                    v = ceil.getValue();
                }
            }
            out[i] = (v == null) ? Double.NaN : v;
        }
        return out;
    }

    private void refreshIfStale() {
        Cached c = cache.get();
        long now = clock.getAsLong();
        if (c != null && (now - c.fetchedAtMs()) < REFRESH_INTERVAL_MS) {
            return;
        }
        if (lastAttemptMs != 0L && (now - lastAttemptMs) < RETRY_SPACING_MS) {
            return;
        }
        lastAttemptMs = now;
        String url = String.format(java.util.Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&hourly=temperature_2m&forecast_days=2&timeformat=unixtime",
                lat, lon);
        try {
            ContentResponse resp = httpClient.newRequest(url).timeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS).send();
            if (resp.getStatus() != 200) {
                LOGGER.debug("OpenMeteo: HTTP {} from {}", resp.getStatus(), url);
                return;
            }
            TreeMap<Instant, Double> parsed = parse(resp.getContentAsString());
            if (!parsed.isEmpty()) {
                cache.set(new Cached(parsed, now));
                LOGGER.info("OpenMeteo: outdoor-temp forecast refreshed ({} hourly points, now {}°C)", parsed.size(),
                        String.format("%.1f", parsed.firstEntry().getValue()));
            }
        } catch (Throwable t) {
            LOGGER.debug("OpenMeteo fetch failed: {}", t.toString());
        }
    }

    /** Pure JSON parser — testable without HTTP. Maps each unixtime hour → temperature_2m. */
    public static TreeMap<Instant, Double> parse(String json) {
        TreeMap<Instant, Double> out = new TreeMap<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("hourly")) {
                return out;
            }
            JsonObject hourly = root.getAsJsonObject("hourly");
            JsonArray times = hourly.getAsJsonArray("time");
            JsonArray temps = hourly.getAsJsonArray("temperature_2m");
            if (times == null || temps == null) {
                return out;
            }
            int n = Math.min(times.size(), temps.size());
            for (int i = 0; i < n; i++) {
                if (temps.get(i).isJsonNull()) {
                    continue;
                }
                long epochSec = times.get(i).getAsLong();
                double t = temps.get(i).getAsDouble();
                out.put(Instant.ofEpochSecond(epochSec), t);
            }
        } catch (Throwable t) {
            // malformed payload → empty map
        }
        return out;
    }
}

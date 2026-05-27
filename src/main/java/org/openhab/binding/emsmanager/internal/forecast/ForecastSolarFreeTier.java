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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.emsmanager.internal.config.ForecastSolarConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * forecast.solar public free tier. URL pattern:
 * {@code <base>/estimate/<lat>/<lon>/<declination>/<azimuth>/<kwp>}.
 *
 * <p>
 * Returns a payload with three time-keyed maps under {@code result}:
 * {@code watts} (instantaneous power forecast per hour),
 * {@code watt_hours_period} (energy per hour bucket), and
 * {@code watt_hours_day} (daily totals keyed by ISO date).
 *
 * <p>
 * Free tier: 12 calls/hour, single plane, no API key. Rate-limit info
 * carried under {@code message.ratelimit.remaining}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ForecastSolarFreeTier implements SolarForecastProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(ForecastSolarFreeTier.class);
    private static final int HTTP_TIMEOUT_MS = 15_000;
    private static final DateTimeFormatter API_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter API_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final HttpClient httpClient;
    private final ForecastSolarConfig config;

    public ForecastSolarFreeTier(HttpClient httpClient, ForecastSolarConfig config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    @Override
    public String kind() {
        return "forecast-solar-free";
    }

    @Override
    public ForecastSnapshot fetch() {
        if (Double.isNaN(config.lat) || Double.isNaN(config.lon)) {
            return withError("missing lat/lon in Thing config");
        }
        String base = config.apiBaseUrl == null || config.apiBaseUrl.isBlank() ? "https://api.forecast.solar"
                : config.apiBaseUrl;
        String url = String.format(java.util.Locale.ROOT, "%s/estimate/%.4f/%.4f/%.0f/%.0f/%.2f", base, config.lat,
                config.lon, config.declination, config.azimuth, config.kwp);

        try {
            ContentResponse resp = httpClient.newRequest(url)
                    .timeout(HTTP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS).send();
            if (resp.getStatus() != HttpStatus.OK_200) {
                return withError("HTTP " + resp.getStatus() + " from " + url);
            }
            JsonElement root = JsonParser.parseString(resp.getContentAsString());
            return parse(root.getAsJsonObject());
        } catch (Throwable t) {
            LOGGER.warn("Forecast.Solar fetch failed for {}: {}", url, t.toString());
            String msg = t.getMessage();
            return withError(msg == null ? t.getClass().getSimpleName() : msg);
        }
    }

    private ForecastSnapshot parse(JsonObject root) {
        JsonObject result = optObject(root, "result");
        if (result == null) {
            return withError("missing 'result' in response");
        }

        ZoneId zone = ZoneId.systemDefault();
        Instant now = Instant.now();
        LocalDateTime nowLdt = LocalDateTime.ofInstant(now, zone);

        // watts: { "2026-05-24 06:00:00": 0, "2026-05-24 07:00:00": 245, ... }
        TreeMap<LocalDateTime, Double> watts = parseTimeKeyedMap(optObject(result, "watts"));
        TreeMap<LocalDateTime, Double> wattHoursPeriod = parseTimeKeyedMap(optObject(result, "watt_hours_period"));
        Map<String, Double> wattHoursDay = parseDateKeyedMap(optObject(result, "watt_hours_day"));

        double nowW = interpolateInstant(watts, nowLdt);

        double next1hWh = sumWindow(wattHoursPeriod, nowLdt, nowLdt.plusHours(1));
        double next3hWh = sumWindow(wattHoursPeriod, nowLdt, nowLdt.plusHours(3));
        double next6hWh = sumWindow(wattHoursPeriod, nowLdt, nowLdt.plusHours(6));

        LocalDate today = nowLdt.toLocalDate();
        double todayWh = wattHoursDay.getOrDefault(today.format(API_DATE), Double.NaN);
        double tomorrowWh = wattHoursDay.getOrDefault(today.plusDays(1).format(API_DATE), Double.NaN);
        double todayKwh = Double.isNaN(todayWh) ? Double.NaN : todayWh / 1000.0;
        double tomorrowKwh = Double.isNaN(tomorrowWh) ? Double.NaN : tomorrowWh / 1000.0;

        Instant peakTodayAt = peakOf(watts, today, zone);

        // Hourly CSV for today's forecast (parsed by the dashboard chart).
        StringBuilder csv = new StringBuilder();
        LocalDateTime startToday = today.atStartOfDay();
        LocalDateTime endToday = today.plusDays(1).atStartOfDay();
        for (Map.Entry<LocalDateTime, Double> e : watts.subMap(startToday, true, endToday, false).entrySet()) {
            if (csv.length() > 0) {
                csv.append(',');
            }
            csv.append(String.format(java.util.Locale.ROOT, "%02d:%02d=%.0f", e.getKey().getHour(),
                    e.getKey().getMinute(), e.getValue()));
        }
        String hourlyTodayCsv = csv.toString();

        Integer rateLimitRemaining = null;
        JsonObject message = optObject(root, "message");
        if (message != null) {
            JsonObject ratelimit = optObject(message, "ratelimit");
            if (ratelimit != null && ratelimit.has("remaining") && !ratelimit.get("remaining").isJsonNull()) {
                try {
                    rateLimitRemaining = ratelimit.get("remaining").getAsInt();
                } catch (Exception ignore) {
                    // upstream sometimes returns a string; skip
                }
            }
        }

        return new ForecastSnapshot(now, nowW, next1hWh, next3hWh, next6hWh, todayKwh, tomorrowKwh, peakTodayAt,
                rateLimitRemaining, null, hourlyTodayCsv);
    }

    private ForecastSnapshot withError(String msg) {
        LOGGER.debug("ForecastSolarFreeTier: returning empty snapshot — {}", msg);
        return new ForecastSnapshot(Instant.now(), Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, null, null, msg, "");
    }

    private static @Nullable JsonObject optObject(JsonObject parent, String key) {
        if (!parent.has(key) || parent.get(key).isJsonNull()) {
            return null;
        }
        JsonElement e = parent.get(key);
        return e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static TreeMap<LocalDateTime, Double> parseTimeKeyedMap(@Nullable JsonObject obj) {
        TreeMap<LocalDateTime, Double> out = new TreeMap<>();
        if (obj == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(e.getKey(), API_TS);
                out.put(ldt, e.getValue().getAsDouble());
            } catch (Exception ignore) {
                // skip malformed entry
            }
        }
        return out;
    }

    private static Map<String, Double> parseDateKeyedMap(@Nullable JsonObject obj) {
        Map<String, Double> out = new java.util.HashMap<>();
        if (obj == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            try {
                out.put(e.getKey(), e.getValue().getAsDouble());
            } catch (Exception ignore) {
                // skip
            }
        }
        return out;
    }

    /** Watts at the current instant — uses the closest hour key ≤ now. */
    private static double interpolateInstant(TreeMap<LocalDateTime, Double> watts, LocalDateTime now) {
        if (watts.isEmpty()) {
            return Double.NaN;
        }
        Map.Entry<LocalDateTime, Double> floor = watts.floorEntry(now);
        if (floor == null) {
            return 0.0; // before first forecast key — assume 0
        }
        return floor.getValue();
    }

    /** Sum watt-hour-period entries in [from, to). */
    private static double sumWindow(TreeMap<LocalDateTime, Double> whPeriod, LocalDateTime from, LocalDateTime to) {
        double sum = 0.0;
        for (Map.Entry<LocalDateTime, Double> e : whPeriod.subMap(from, true, to, false).entrySet()) {
            sum += e.getValue();
        }
        return sum;
    }

    /** Returns the hour timestamp with max watts for the given calendar date, or null. */
    private static @Nullable Instant peakOf(TreeMap<LocalDateTime, Double> watts, LocalDate date, ZoneId zone) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        LocalDateTime bestKey = null;
        double bestVal = -1.0;
        for (Map.Entry<LocalDateTime, Double> e : watts.subMap(start, true, end, false).entrySet()) {
            if (e.getValue() > bestVal) {
                bestVal = e.getValue();
                bestKey = e.getKey();
            }
        }
        return bestKey == null ? null : bestKey.atZone(zone).toInstant();
    }
}

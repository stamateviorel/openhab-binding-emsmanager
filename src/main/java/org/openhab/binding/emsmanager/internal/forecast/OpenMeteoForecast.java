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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Open-Meteo solar forecast provider (keyless, free, non-commercial).
 *
 * <p>
 * Unlike forecast.solar (whose free public endpoint drops ~half of all
 * connections under load), Open-Meteo's free tier is reliable and needs no
 * account or API key. It returns irradiance rather than PV output, so this
 * provider fetches {@code global_tilted_irradiance} (GTI, W/m²) for the panel's
 * tilt/azimuth and converts it to AC power using the array rating and a
 * performance ratio:
 *
 * <pre>
 *   P_ac(W) = GTI(W/m²) × kWp × performanceRatio
 * </pre>
 *
 * (kWp is defined at STC = 1000 W/m², so kW per W/m² = kWp/1000; ×1000 to get W
 * cancels to GTI × kWp × PR.) Hourly power is integrated to daily kWh, mirroring
 * the {@link ForecastSnapshot} contract of {@link ForecastSolarFreeTier} so all
 * downstream channels/items/charts keep working unchanged.
 *
 * <p>
 * Azimuth convention matches the config (0° = south, −90° = east, +90° = west),
 * verified against Open-Meteo. Defensive: retries a few times and never throws —
 * returns a snapshot with {@code lastError} on failure.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class OpenMeteoForecast implements SolarForecastProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenMeteoForecast.class);
    private static final int HTTP_TIMEOUT_MS = 15_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final String DEFAULT_BASE = "https://api.open-meteo.com";

    private final HttpClient httpClient;
    private final ForecastSolarConfig config;

    public OpenMeteoForecast(HttpClient httpClient, ForecastSolarConfig config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    @Override
    public String kind() {
        return "open-meteo";
    }

    @Override
    public ForecastSnapshot fetch() {
        if (Double.isNaN(config.lat) || Double.isNaN(config.lon)) {
            return withError("missing lat/lon in Thing config");
        }
        // The shared config defaults apiBaseUrl to forecast.solar; for this
        // provider fall back to the Open-Meteo base unless explicitly overridden.
        String base = config.apiBaseUrl;
        if (base == null || base.isBlank() || base.contains("forecast.solar")) {
            base = DEFAULT_BASE;
        }
        double pr = config.performanceRatio > 0 && config.performanceRatio <= 1.0 ? config.performanceRatio : 0.85;
        double capW = config.inverterAcKw > 0 ? config.inverterAcKw * 1000.0 : 0.0;
        String url = String.format(java.util.Locale.ROOT,
                "%s/v1/forecast?latitude=%.4f&longitude=%.4f&hourly=global_tilted_irradiance"
                        + "&tilt=%.0f&azimuth=%.0f&forecast_days=2&timezone=auto",
                base, config.lat, config.lon, config.declination, config.azimuth);

        String lastErr = "no attempt";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ContentResponse resp = httpClient.newRequest(url)
                        .timeout(HTTP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS).send();
                if (resp.getStatus() != HttpStatus.OK_200) {
                    lastErr = "HTTP " + resp.getStatus() + " from Open-Meteo";
                    continue;
                }
                JsonElement root = JsonParser.parseString(resp.getContentAsString());
                return parse(root.getAsJsonObject(), pr, capW);
            } catch (Throwable t) {
                String m = t.getMessage();
                lastErr = m == null ? t.getClass().getSimpleName() : m;
                LOGGER.debug("Open-Meteo fetch attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, lastErr);
            }
        }
        LOGGER.warn("Open-Meteo fetch failed after {} attempts for {}: {}", MAX_ATTEMPTS, url, lastErr);
        return withError(lastErr);
    }

    private ForecastSnapshot parse(JsonObject root, double pr, double capW) {
        JsonObject hourly = optObject(root, "hourly");
        if (hourly == null) {
            return withError("missing 'hourly' in Open-Meteo response");
        }
        JsonArray times = optArray(hourly, "time");
        JsonArray gti = optArray(hourly, "global_tilted_irradiance");
        if (times == null || gti == null || times.size() == 0 || times.size() != gti.size()) {
            return withError("malformed hourly arrays in Open-Meteo response");
        }

        ZoneId zone = ZoneId.systemDefault();
        Instant now = Instant.now();
        LocalDateTime nowLdt = LocalDateTime.ofInstant(now, zone);

        // Build a per-hour AC-power map (W): P = GTI * kWp * PR. Open-Meteo returns
        // location-local wall-clock times (timezone=auto), parsed as LocalDateTime.
        TreeMap<LocalDateTime, Double> watts = new TreeMap<>();
        for (int i = 0; i < times.size(); i++) {
            JsonElement g = gti.get(i);
            if (g.isJsonNull()) {
                continue;
            }
            try {
                LocalDateTime ldt = LocalDateTime.parse(times.get(i).getAsString());
                watts.put(ldt, acWatts(g.getAsDouble(), config.kwp, pr, capW));
            } catch (Exception ignore) {
                // skip malformed entry
            }
        }
        if (watts.isEmpty()) {
            return withError("no usable GTI samples from Open-Meteo");
        }

        double nowW = interpolateInstant(watts, nowLdt);
        // Hourly buckets are 1 h wide, so each bucket's Wh equals its W value.
        double next1hWh = sumWindow(watts, nowLdt, nowLdt.plusHours(1));
        double next3hWh = sumWindow(watts, nowLdt, nowLdt.plusHours(3));
        double next6hWh = sumWindow(watts, nowLdt, nowLdt.plusHours(6));

        LocalDate today = nowLdt.toLocalDate();
        double todayKwh = dayKwh(watts, today);
        double tomorrowKwh = dayKwh(watts, today.plusDays(1));

        Instant peakTodayAt = peakOf(watts, today, zone);

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

        return new ForecastSnapshot(now, nowW, next1hWh, next3hWh, next6hWh, todayKwh, tomorrowKwh, peakTodayAt, null,
                null, csv.toString(), ForecastSnapshot.toEpochSeriesCsv(watts, zone));
    }

    private ForecastSnapshot withError(String msg) {
        LOGGER.debug("OpenMeteoForecast: returning empty snapshot — {}", msg);
        return new ForecastSnapshot(Instant.now(), Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, null, null, msg, "", "");
    }

    /**
     * GTI (W/m²) → AC power (W): {@code P = GTI × kWp × PR}, clipped at the
     * inverter ceiling. {@code capW ≤ 0} means uncapped. Clipping models inverter
     * saturation on bright days, where the DC array out-produces the AC inverter.
     */
    static double acWatts(double gti, double kwp, double pr, double capW) {
        double w = Math.max(0.0, gti) * kwp * pr;
        return capW > 0 && w > capW ? capW : w;
    }

    /** Sum a calendar day's hourly power (W) and convert to kWh (1 h buckets). */
    private static double dayKwh(TreeMap<LocalDateTime, Double> watts, LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        Map<LocalDateTime, Double> day = watts.subMap(start, true, end, false);
        if (day.isEmpty()) {
            return Double.NaN;
        }
        double wh = 0.0;
        for (double w : day.values()) {
            wh += w; // each bucket is 1 h → Wh == W
        }
        return wh / 1000.0;
    }

    private static @Nullable JsonObject optObject(JsonObject parent, String key) {
        if (!parent.has(key) || parent.get(key).isJsonNull()) {
            return null;
        }
        JsonElement e = parent.get(key);
        return e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static @Nullable JsonArray optArray(JsonObject parent, String key) {
        if (!parent.has(key) || parent.get(key).isJsonNull()) {
            return null;
        }
        JsonElement e = parent.get(key);
        return e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static double interpolateInstant(TreeMap<LocalDateTime, Double> watts, LocalDateTime now) {
        if (watts.isEmpty()) {
            return Double.NaN;
        }
        Map.Entry<LocalDateTime, Double> floor = watts.floorEntry(now);
        if (floor == null) {
            return 0.0;
        }
        return floor.getValue();
    }

    private static double sumWindow(TreeMap<LocalDateTime, Double> watts, LocalDateTime from, LocalDateTime to) {
        double sum = 0.0;
        for (double w : watts.subMap(from, true, to, false).values()) {
            sum += w;
        }
        return sum;
    }

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

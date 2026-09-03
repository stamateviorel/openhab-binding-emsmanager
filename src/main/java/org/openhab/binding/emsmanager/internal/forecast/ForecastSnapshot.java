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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Immutable forecast snapshot returned by a {@link SolarForecastProvider}.
 * Any field can be {@link Double#NaN} or {@code null} when the upstream API
 * doesn't supply it (or when a fetch failed and we're returning the last
 * known good snapshot).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record ForecastSnapshot(Instant refreshedAt, double nowW, double next1hWh, double next3hWh, double next6hWh,
        double todayKwh, double tomorrowKwh, @Nullable Instant peakTodayAt, @Nullable Integer rateLimitRemaining,
        @Nullable String lastError,
        // Hourly forecast as "HH:MM=W,HH:MM=W,…" for today (00..23).
        // Used by the forecast-vs-actual chart.
        String hourlyTodayCsv,
        // Full-horizon hourly forecast as "epochSecond=W,…" (today + tomorrow + …). The handler
        // republishes this as TimeSeries future states — the provider-profile idea from #3478.
        String hourlySeriesCsv) {

    public static final ForecastSnapshot EMPTY = new ForecastSnapshot(Instant.EPOCH, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, null, null, null, "", "");

    /** Past this the "today" figures describe some other day. */
    public static final java.time.Duration MAX_PRESENTABLE_AGE = java.time.Duration.ofHours(36);

    /**
     * This snapshot as it may be shown at {@code now}. A snapshot older than
     * {@link #MAX_PRESENTABLE_AGE} - one restored from the cache after days off, or the last good
     * fetch during a long outage - keeps only what is still true: the series points that lie in the
     * future. The day figures are dropped rather than presented as today's.
     */
    public ForecastSnapshot presentableAt(Instant now) {
        if (refreshedAt.equals(Instant.EPOCH) || !refreshedAt.plus(MAX_PRESENTABLE_AGE).isBefore(now)) {
            return this;
        }
        return new ForecastSnapshot(refreshedAt, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                null, rateLimitRemaining, lastError, "", futurePointsOf(hourlySeriesCsv, now));
    }

    private static String futurePointsOf(String seriesCsv, Instant now) {
        StringBuilder sb = new StringBuilder();
        for (String tok : seriesCsv.split(",")) {
            int eq = tok.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                if (Long.parseLong(tok.substring(0, eq).trim()) <= now.getEpochSecond()) {
                    continue;
                }
            } catch (NumberFormatException e) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(tok.trim());
        }
        return sb.toString();
    }

    /**
     * One day of the series as {@code HH:MM=W} for its 24 local hours, zeros where the series has
     * nothing, so a dashboard can draw tomorrow with the same code it draws today.
     */
    public static String hourlyCsvFor(String seriesCsv, java.time.LocalDate day, java.time.ZoneId zone) {
        double[] watts = new double[24];
        boolean any = false;
        for (String tok : seriesCsv.split(",")) {
            int eq = tok.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                java.time.ZonedDateTime at = Instant.ofEpochSecond(Long.parseLong(tok.substring(0, eq).trim()))
                        .atZone(zone);
                if (at.toLocalDate().equals(day)) {
                    watts[at.getHour()] = Double.parseDouble(tok.substring(eq + 1).trim());
                    any = true;
                }
            } catch (NumberFormatException e) {
                continue;
            }
        }
        if (!any) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int h = 0; h < 24; h++) {
            if (h > 0) {
                sb.append(',');
            }
            sb.append(String.format(java.util.Locale.ROOT, "%02d:00=%.0f", h, watts[h]));
        }
        return sb.toString();
    }

    /**
     * Serialize an hourly power map to {@code "epochSecond=W,…"} over its full horizon in
     * ascending time order — the cache/wire form behind {@link #hourlySeriesCsv()}.
     */
    public static String toEpochSeriesCsv(java.util.SortedMap<java.time.LocalDateTime, Double> watts,
            java.time.ZoneId zone) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<java.time.LocalDateTime, Double> e : watts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey().atZone(zone).toEpochSecond()).append('=')
                    .append(String.format(java.util.Locale.ROOT, "%.0f", e.getValue()));
        }
        return sb.toString();
    }
}

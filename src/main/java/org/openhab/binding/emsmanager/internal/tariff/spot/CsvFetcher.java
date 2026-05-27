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
package org.openhab.binding.emsmanager.internal.tariff.spot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic CSV-fetching client for suppliers without public APIs. Pulls
 * either an HTTP(S) URL or a local file path. Expects 24 or 48 hourly
 * prices, one per line, in €/kWh (already including any taxes). Markup is
 * added on top — set 0 if the CSV is already end-customer-final.
 *
 * <p>
 * Designed for Belgian dynamic-tariff suppliers (Engie Dynamic, Bolt,
 * Mega Flow, TotalEnergies Dynamic) that publish daily CSV exports via
 * customer portals.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class CsvFetcher implements SpotPriceClient {

    public static final String KEY = "csv-upload";

    private static final int HTTP_TIMEOUT_MS = 15_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(CsvFetcher.class);

    private final HttpClient http;
    private final String urlOrPath;
    private final double markupEurPerKWh;

    public CsvFetcher(HttpClient http, String urlOrPath, double markupEurPerKWh) {
        this.http = http;
        this.urlOrPath = urlOrPath;
        this.markupEurPerKWh = markupEurPerKWh;
    }

    @Override
    public String subProvider() {
        return KEY;
    }

    @Override
    public HourlyPrices fetch() {
        if (urlOrPath.isBlank()) {
            return HourlyPrices.empty("CSV url/path not configured");
        }
        try {
            String content;
            if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
                ContentResponse resp = http.newRequest(urlOrPath).timeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .send();
                if (resp.getStatus() != HttpStatus.OK_200) {
                    return HourlyPrices.empty("CSV HTTP " + resp.getStatus());
                }
                content = resp.getContentAsString();
            } else {
                // Local file path.
                content = Files.readString(Path.of(urlOrPath));
            }
            return parse(content);
        } catch (Throwable t) {
            LOGGER.debug("CSV fetch failed", t);
            String msg = t.getMessage();
            return HourlyPrices.empty(msg == null ? t.getClass().getSimpleName() : msg);
        }
    }

    /**
     * Parse the CSV. Two accepted shapes:
     * <ul>
     * <li>{@code price\nprice\n...} — 24 or 48 prices, one per line,
     * starting from today's 00:00 local time.</li>
     * <li>{@code timestamp,price\n...} — ISO-8601 timestamp + price pairs.
     * Lines starting with {@code #} are treated as comments.</li>
     * </ul>
     */
    private HourlyPrices parse(String csv) {
        try {
            ZoneId zone = ZoneId.systemDefault();
            ZonedDateTime startOfToday = LocalDate.now(zone).atStartOfDay(zone);
            TreeMap<Instant, Double> out = new TreeMap<>();

            String[] lines = csv.split("\\R");
            int hourIdx = 0;
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] cols = line.split(",");
                try {
                    if (cols.length == 1) {
                        // Single-column: one price per hour
                        double price = Double.parseDouble(cols[0].trim()) + markupEurPerKWh;
                        out.put(startOfToday.plusHours(hourIdx).toInstant(), price);
                        hourIdx++;
                    } else {
                        // Two-column: timestamp,price
                        Instant at = Instant.parse(cols[0].trim());
                        double price = Double.parseDouble(cols[1].trim()) + markupEurPerKWh;
                        out.put(at, price);
                    }
                } catch (Exception ignore) {
                    // Skip malformed lines silently
                }
            }
            if (out.isEmpty()) {
                return HourlyPrices.empty("CSV: no prices parsed");
            }
            return new HourlyPrices(out, Instant.now(), null);
        } catch (Throwable t) {
            return HourlyPrices.empty("CSV parse: " + t.getMessage());
        }
    }
}

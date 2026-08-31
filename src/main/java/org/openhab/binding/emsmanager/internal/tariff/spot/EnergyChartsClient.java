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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Day-ahead prices from energy-charts.info (Fraunhofer ISE), which needs no API key.
 *
 * Useful both on its own and as a second opinion when a key-based feed is unavailable. The series
 * is published per market time unit, which is 15 minutes in several zones since 2025; the hourly
 * prices this binding works in are the mean of the quarters actually present, so a partially
 * published hour still yields a usable number rather than a gap.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class EnergyChartsClient implements SpotPriceClient {

    public static final String KEY = "energy-charts";

    private static final String API_BASE = "https://api.energy-charts.info/price";
    private static final int HTTP_TIMEOUT_MS = 15_000;
    private static final long SECONDS_PER_HOUR = 3600L;

    private static final Logger LOGGER = LoggerFactory.getLogger(EnergyChartsClient.class);
    private static final Gson GSON = new Gson();

    private final HttpClient http;
    private final String biddingZone;
    private final double markupEurPerKWh;

    public EnergyChartsClient(HttpClient http, String biddingZone, double markupEurPerKWh) {
        this.http = http;
        this.biddingZone = biddingZone.isBlank() ? "BE" : biddingZone;
        this.markupEurPerKWh = markupEurPerKWh;
    }

    @Override
    public String subProvider() {
        return KEY;
    }

    @Override
    public HourlyPrices fetch() {
        String url = API_BASE + "?bzn=" + biddingZone;
        try {
            ContentResponse resp = http.newRequest(url).timeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS).send();
            if (resp.getStatus() != HttpStatus.OK_200) {
                return HourlyPrices.empty("energy-charts HTTP " + resp.getStatus());
            }
            return parse(resp.getContentAsString());
        } catch (Throwable t) {
            LOGGER.debug("energy-charts fetch failed", t);
            String msg = t.getMessage();
            return HourlyPrices.empty(msg == null ? t.getClass().getSimpleName() : msg);
        }
    }

    /** Parse the {unix_seconds, price} arrays into hourly EUR/kWh, markup included. */
    HourlyPrices parse(String json) {
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj == null || !obj.has("unix_seconds") || !obj.has("price")) {
                return HourlyPrices.empty("energy-charts: unexpected response for zone " + biddingZone);
            }
            JsonArray seconds = obj.getAsJsonArray("unix_seconds");
            JsonArray prices = obj.getAsJsonArray("price");

            TreeMap<Long, List<Double>> quartersByHour = new TreeMap<>();
            int n = Math.min(seconds.size(), prices.size());
            for (int i = 0; i < n; i++) {
                JsonElement price = prices.get(i);
                if (price.isJsonNull()) {
                    continue; // a slot the market has not published yet
                }
                long epochSec = seconds.get(i).getAsLong();
                long hourStart = epochSec - Math.floorMod(epochSec, SECONDS_PER_HOUR);
                quartersByHour.computeIfAbsent(hourStart, k -> new ArrayList<>()).add(price.getAsDouble());
            }
            if (quartersByHour.isEmpty()) {
                return HourlyPrices.empty("energy-charts: no prices for zone " + biddingZone);
            }

            TreeMap<Instant, Double> out = new TreeMap<>();
            for (var e : quartersByHour.entrySet()) {
                double sum = 0.0;
                for (double v : e.getValue()) {
                    sum += v;
                }
                double eurPerMWh = sum / e.getValue().size();
                out.put(Instant.ofEpochSecond(e.getKey()), eurPerMWh / 1000.0 + markupEurPerKWh);
            }
            return new HourlyPrices(out, Instant.now(), null);
        } catch (Throwable t) {
            return HourlyPrices.empty("energy-charts parse: " + t.getMessage());
        }
    }
}

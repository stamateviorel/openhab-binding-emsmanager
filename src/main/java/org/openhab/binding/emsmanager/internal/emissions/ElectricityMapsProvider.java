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
package org.openhab.binding.emsmanager.internal.emissions;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Live grid-mix emissions via api.electricitymap.org.
 *
 * <p>
 * Endpoint: {@code GET /v3/carbon-intensity/latest?zone=BE} returns
 * {@code {"zone": "BE", "carbonIntensity": 142, "datetime": "..."}}. Auth
 * via {@code auth-token} header.
 *
 * <p>
 * Free tier — 50 calls/day, so we poll every 30 min (48/day). On any
 * fetch error, we keep the last known good value rather than going to NaN
 * (so CO₂ counting never gaps).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ElectricityMapsProvider implements EmissionsTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElectricityMapsProvider.class);
    private static final String API = "https://api.electricitymap.org/v3/carbon-intensity/latest?zone=";
    private static final long REFRESH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(30);
    private static final int HTTP_TIMEOUT_MS = 10_000;

    private final HttpClient httpClient;
    private final String apiKey;
    private final String zone;
    private final double injectionOffset;
    private final AtomicReference<@Nullable Snapshot> last = new AtomicReference<>();

    private record Snapshot(double gridGramsPerKWh, long fetchedAtMs) {
    }

    public ElectricityMapsProvider(HttpClient httpClient, String apiKey, String zone,
            double injectionOffsetGramsPerKWh) {
        this.httpClient = httpClient;
        this.apiKey = apiKey;
        this.zone = zone == null || zone.isBlank() ? "BE" : zone;
        this.injectionOffset = injectionOffsetGramsPerKWh;
    }

    @Override
    public double currentGridGramsPerKWh() {
        Snapshot s = last.get();
        long now = System.currentTimeMillis();
        if (s == null || (now - s.fetchedAtMs()) > REFRESH_INTERVAL_MS) {
            tryFetch(now);
            s = last.get();
        }
        return s == null ? Double.NaN : s.gridGramsPerKWh();
    }

    @Override
    public double currentInjectionOffsetGramsPerKWh() {
        return injectionOffset;
    }

    @Override
    public String name() {
        return "electricitymaps:" + zone;
    }

    private void tryFetch(long now) {
        if (apiKey == null || apiKey.isBlank()) {
            LOGGER.debug("ElectricityMapsProvider: no apiKey, skipping fetch");
            return;
        }
        try {
            ContentResponse resp = httpClient.newRequest(API + zone).header("auth-token", apiKey)
                    .timeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS).send();
            if (resp.getStatus() != 200) {
                LOGGER.debug("ElectricityMapsProvider: HTTP {} from {}", resp.getStatus(), API + zone);
                return;
            }
            JsonObject obj = JsonParser.parseString(resp.getContentAsString()).getAsJsonObject();
            if (!obj.has("carbonIntensity") || obj.get("carbonIntensity").isJsonNull()) {
                LOGGER.debug("ElectricityMapsProvider: missing carbonIntensity in response");
                return;
            }
            double value = obj.get("carbonIntensity").getAsDouble();
            last.set(new Snapshot(value, now));
            LOGGER.info("ElectricityMaps[{}]: live grid intensity = {} gCO₂/kWh", zone, value);
        } catch (Throwable t) {
            LOGGER.debug("ElectricityMapsProvider fetch failed: {}", t.toString());
        }
    }
}

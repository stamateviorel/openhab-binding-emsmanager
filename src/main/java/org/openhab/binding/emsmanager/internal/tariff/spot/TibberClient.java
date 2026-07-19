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
import java.time.OffsetDateTime;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tibber dynamic-price client. GraphQL POST to
 * {@code https://api.tibber.com/v1-beta/gql} with Bearer auth.
 *
 * <p>
 * The {@code total} field on each priceInfo entry already includes VAT
 * and distribution costs (it's "what you pay"), so we apply NO additional
 * markup in this client — even if the user has configured one. If the user
 * wants a markup on top of Tibber prices, they should keep `markupEurPerKWh=0`
 * since Tibber's quote is end-customer-final.
 *
 * <p>
 * Personal access token from <a href="https://developer.tibber.com/">developer.tibber.com</a>.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class TibberClient implements SpotPriceClient {

    public static final String KEY = "tibber";

    private static final String API_URL = "https://api.tibber.com/v1-beta/gql";
    private static final int HTTP_TIMEOUT_MS = 15_000;

    private static final String QUERY = "{ viewer { homes { currentSubscription { priceInfo { "
            + "today { startsAt total } tomorrow { startsAt total } } } } } }";

    private static final Logger LOGGER = LoggerFactory.getLogger(TibberClient.class);

    private final HttpClient http;
    private final String apiKey;

    public TibberClient(HttpClient http, String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    @Override
    public String subProvider() {
        return KEY;
    }

    @Override
    public HourlyPrices fetch() {
        if (apiKey.isBlank()) {
            return HourlyPrices.empty("Tibber API key not configured");
        }
        String body = "{\"query\":\"" + QUERY.replace("\"", "\\\"") + "\"}";
        try {
            ContentResponse resp = http.newRequest(API_URL).method(HttpMethod.POST)
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .content(new StringContentProvider(body)).timeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS).send();
            if (resp.getStatus() != HttpStatus.OK_200) {
                return HourlyPrices.empty("Tibber HTTP " + resp.getStatus());
            }
            return parse(resp.getContentAsString());
        } catch (Throwable t) {
            LOGGER.debug("Tibber fetch failed", t);
            String msg = t.getMessage();
            return HourlyPrices.empty(msg == null ? t.getClass().getSimpleName() : msg);
        }
    }

    private HourlyPrices parse(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonObject viewer = root.getAsJsonObject().getAsJsonObject("data").getAsJsonObject("viewer");
            JsonArray homes = viewer.getAsJsonArray("homes");
            if (homes.size() == 0) {
                return HourlyPrices.empty("Tibber: no homes returned");
            }
            JsonObject priceInfo = homes.get(0).getAsJsonObject().getAsJsonObject("currentSubscription")
                    .getAsJsonObject("priceInfo");
            TreeMap<Instant, Double> out = new TreeMap<>();
            addAll(out, priceInfo.getAsJsonArray("today"));
            if (priceInfo.has("tomorrow")) {
                addAll(out, priceInfo.getAsJsonArray("tomorrow"));
            }
            if (out.isEmpty()) {
                return HourlyPrices.empty("Tibber: empty priceInfo");
            }
            return new HourlyPrices(out, Instant.now(), null);
        } catch (Throwable t) {
            return HourlyPrices.empty("Tibber parse: " + t.getMessage());
        }
    }

    private static void addAll(TreeMap<Instant, Double> out, JsonArray arr) {
        if (arr == null) {
            return;
        }
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            String startsAt = o.get("startsAt").getAsString();
            double total = o.get("total").getAsDouble();
            Instant at = OffsetDateTime.parse(startsAt).toInstant();
            out.put(at, total);
        }
    }
}

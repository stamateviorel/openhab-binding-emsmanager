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

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * ENTSO-E Transparency Platform day-ahead spot prices.
 *
 * <p>
 * API: {@code https://web-api.tp.entsoe.eu/api}
 *
 * <p>
 * Belgian bidding zone EIC code: {@code 10YBE----------2}. Document type
 * A44 = "Price document". The platform returns hourly day-ahead prices in
 * €/MWh; this client divides by 1000 and adds the configured markup so the
 * output is €/kWh "as billed".
 *
 * <p>
 * Free registration at <a href="https://transparency.entsoe.eu/">transparency.entsoe.eu</a>
 * is required to get a personal API token.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class EntsoeBeClient implements SpotPriceClient {

    public static final String KEY = "entsoe-be";

    private static final String API_BASE = "https://web-api.tp.entsoe.eu/api";
    private static final String BE_BIDDING_ZONE = "10YBE----------2";
    private static final String DOC_TYPE_DAY_AHEAD_PRICES = "A44";
    private static final int HTTP_TIMEOUT_MS = 15_000;
    private static final DateTimeFormatter API_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private static final Logger LOGGER = LoggerFactory.getLogger(EntsoeBeClient.class);

    private final HttpClient http;
    private final String apiKey;
    private final double markupEurPerKWh;

    public EntsoeBeClient(HttpClient http, String apiKey, double markupEurPerKWh) {
        this.http = http;
        this.apiKey = apiKey;
        this.markupEurPerKWh = markupEurPerKWh;
    }

    @Override
    public String subProvider() {
        return KEY;
    }

    @Override
    public HourlyPrices fetch() {
        if (apiKey.isBlank()) {
            return HourlyPrices.empty("ENTSO-E API key not configured");
        }
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime startOfToday = LocalDate.now(zone).atStartOfDay(zone);
        ZonedDateTime endOfTomorrow = startOfToday.plusDays(2);
        // ENTSO-E expects UTC in yyyyMMddHHmm form.
        String periodStart = startOfToday.withZoneSameInstant(ZoneId.of("UTC")).format(API_TS);
        String periodEnd = endOfTomorrow.withZoneSameInstant(ZoneId.of("UTC")).format(API_TS);

        String url = String.format(java.util.Locale.ROOT,
                "%s?securityToken=%s&documentType=%s&in_Domain=%s&out_Domain=%s&periodStart=%s&periodEnd=%s", API_BASE,
                apiKey, DOC_TYPE_DAY_AHEAD_PRICES, BE_BIDDING_ZONE, BE_BIDDING_ZONE, periodStart, periodEnd);

        try {
            ContentResponse resp = http.newRequest(url).timeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS).send();
            if (resp.getStatus() != HttpStatus.OK_200) {
                return HourlyPrices.empty("ENTSO-E HTTP " + resp.getStatus());
            }
            return parse(resp.getContent());
        } catch (Throwable t) {
            LOGGER.debug("ENTSO-E fetch failed", t);
            String msg = t.getMessage();
            return HourlyPrices.empty(msg == null ? t.getClass().getSimpleName() : msg);
        }
    }

    /** Parse the Publication_MarketDocument XML and return hourly prices in €/kWh (post-markup). */
    private HourlyPrices parse(byte[] xmlBytes) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(xmlBytes));

            TreeMap<Instant, Double> out = new TreeMap<>();
            NodeList timeSeriesList = doc.getElementsByTagName("TimeSeries");
            for (int t = 0; t < timeSeriesList.getLength(); t++) {
                Element ts = (Element) timeSeriesList.item(t);
                NodeList periodList = ts.getElementsByTagName("Period");
                for (int p = 0; p < periodList.getLength(); p++) {
                    Element period = (Element) periodList.item(p);
                    @Nullable
                    String startIso = textOfFirst(period, "timeInterval", "start");
                    if (startIso == null) {
                        continue;
                    }
                    Instant periodStart = Instant.parse(startIso);
                    NodeList points = period.getElementsByTagName("Point");
                    for (int i = 0; i < points.getLength(); i++) {
                        Element pt = (Element) points.item(i);
                        String posStr = textOf(pt, "position");
                        String priceStr = textOf(pt, "price.amount");
                        if (posStr == null || priceStr == null) {
                            continue;
                        }
                        int posHour = Integer.parseInt(posStr) - 1; // 1-based positions
                        double eurPerMWh = Double.parseDouble(priceStr);
                        double eurPerKwhWithMarkup = eurPerMWh / 1000.0 + markupEurPerKWh;
                        out.put(periodStart.plusSeconds(posHour * 3600L), eurPerKwhWithMarkup);
                    }
                }
            }
            if (out.isEmpty()) {
                return HourlyPrices.empty("ENTSO-E: no prices in document");
            }
            return new HourlyPrices(out, Instant.now(), null);
        } catch (Throwable t) {
            return HourlyPrices.empty("ENTSO-E parse: " + t.getMessage());
        }
    }

    private static @Nullable String textOfFirst(Element parent, String childTag, String grandChildTag) {
        NodeList children = parent.getElementsByTagName(childTag);
        if (children.getLength() == 0) {
            return null;
        }
        return textOf((Element) children.item(0), grandChildTag);
    }

    private static @Nullable String textOf(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) {
            return null;
        }
        Node n = nl.item(0);
        return n.getTextContent() == null ? null : n.getTextContent().trim();
    }
}

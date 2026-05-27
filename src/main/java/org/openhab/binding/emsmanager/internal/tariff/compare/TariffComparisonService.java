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
package org.openhab.binding.emsmanager.internal.tariff.compare;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.StringType;
import org.openhab.core.persistence.FilterCriteria;
import org.openhab.core.persistence.HistoricItem;
import org.openhab.core.persistence.PersistenceService;
import org.openhab.core.persistence.PersistenceServiceRegistry;
import org.openhab.core.persistence.QueryablePersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tariff comparison "what-if" service.
 *
 * <p>
 * Aggregates the site's historical net-import (house consumption minus solar
 * production, floored at 0) into a 24-bin hour-of-day profile, then evaluates
 * each candidate tariff's price curve to produce a ranked cost comparison.
 *
 * <p>
 * Formula-based providers (flat, day/night, ToU) are exact. Market-based
 * providers (e.g. ENTSO-E / Tibber / aWATTar) use the live tariff Thing's
 * published 24-hour schedule as a representative spot-price day — an
 * approximation. The <em>ranking</em> is robust even if the absolute numbers
 * are estimates.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class TariffComparisonService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TariffComparisonService.class);
    private static final long RESAMPLE_MS = 60L * 60L * 1000L; // hourly
    // Heap-safety: one resample bucket per query, small page cap → flat heap
    // regardless of lookback. See BatterySizingService for the rationale.
    private static final long CHUNK_MS = RESAMPLE_MS;
    private static final int PAGE_SIZE = 360;
    private static final double BE_VAT = 0.21;

    private final EventPublisher eventPublisher;
    private final ItemRegistry itemRegistry;
    private final PersistenceServiceRegistry persistenceRegistry;

    public TariffComparisonService(EventPublisher eventPublisher, ItemRegistry itemRegistry,
            PersistenceServiceRegistry persistenceRegistry) {
        this.eventPublisher = eventPublisher;
        this.itemRegistry = itemRegistry;
        this.persistenceRegistry = persistenceRegistry;
    }

    /**
     * Run the comparison. Heavy; call off the tick thread.
     *
     * @param flatPrice user's current flat price (for the "flat" baseline)
     * @param lookbackDays window to aggregate net-import over
     * @return ranking CSV ("provider=eur,provider=eur,...") cheapest-first; null on error
     */
    public @Nullable String compute(double flatPrice, int lookbackDays, String solarItem, String houseItem) {
        QueryablePersistenceService persistence = getQueryablePersistence();
        if (persistence == null) {
            LOGGER.warn("TariffComparison: no queryable persistence service");
            return null;
        }
        Instant from = Instant.now().minus(lookbackDays, ChronoUnit.DAYS);
        Instant to = Instant.now();

        double[] houseByHour = hourOfDayProfile(persistence, houseItem, from, to);
        double[] solarByHour = hourOfDayProfile(persistence, solarItem, from, to);
        if (houseByHour == null) {
            LOGGER.warn("TariffComparison: no {} data", houseItem);
            return null;
        }
        // Net import profile (kWh per hour-of-day): max(0, house − solar). Profile values are
        // average W per hour-of-day → × 1 h / 1000 = kWh.
        double[] netImportKwh = new double[24];
        for (int h = 0; h < 24; h++) {
            double houseW = houseByHour[h];
            double solarW = (solarByHour != null) ? solarByHour[h] : 0;
            double netW = Math.max(0.0, houseW - solarW);
            netImportKwh[h] = netW / 1000.0; // 1-hour bin
        }

        // Representative spot-price day from the live tariff Thing schedule.
        double[] spot = TariffComparisonCalculator.csvCurve(readString("EMS_Tariff_Schedule24h_CSV"));

        Map<String, double[]> curves = new LinkedHashMap<>();
        curves.put("Vast (huidig)", TariffComparisonCalculator.flatCurve(flatPrice));
        curves.put("Dag/nacht", TariffComparisonCalculator.dayNightCurve(0.32, 0.18, 7, 22));
        if (spot != null) {
            curves.put("ENTSO-E spot", spot);
            curves.put("Tibber", TariffComparisonCalculator.retailOnSpotCurve(spot, BE_VAT, 0.045));
            curves.put("aWATTar", TariffComparisonCalculator.retailOnSpotCurve(spot, BE_VAT, 0.015));
            curves.put("Engie Dynamic", TariffComparisonCalculator.engieDynamicCurve(spot));
        }

        List<TariffComparisonCalculator.ProviderResult> ranked = TariffComparisonCalculator.compare(netImportKwh,
                curves, lookbackDays);

        StringBuilder csv = new StringBuilder();
        StringBuilder human = new StringBuilder();
        for (var r : ranked) {
            if (csv.length() > 0) {
                csv.append(',');
            }
            csv.append(String.format(java.util.Locale.ROOT, "%s=%.2f", r.provider(), r.periodCostEur()));
            if (human.length() > 0) {
                human.append(" · ");
            }
            human.append(String.format(java.util.Locale.ROOT, "%s €%.0f", r.provider(), r.periodCostEur()));
        }

        publish("EMS_TariffComparison_RankingCsv", new StringType(csv.toString()));
        publish("EMS_TariffComparison_Summary", new StringType(human.toString()));
        publish("EMS_TariffComparison_Cheapest", new StringType(ranked.isEmpty() ? "—" : ranked.get(0).provider()));
        publish("EMS_TariffComparison_LastRunAt", new StringType(ZonedDateTime.now().toString()));

        LOGGER.info("TariffComparison ({} d): {}", lookbackDays, human);
        return csv.toString();
    }

    private @Nullable QueryablePersistenceService getQueryablePersistence() {
        PersistenceService svc = persistenceRegistry.get("influxdb");
        if (svc == null) {
            svc = persistenceRegistry.getDefault();
        }
        return (svc instanceof QueryablePersistenceService q) ? q : null;
    }

    /** Average W per hour-of-day (0..23) across the window. Null if item missing/empty. */
    private double @Nullable [] hourOfDayProfile(QueryablePersistenceService persistence, String itemName, Instant from,
            Instant to) {
        try {
            itemRegistry.getItem(itemName);
        } catch (ItemNotFoundException e) {
            return null;
        }
        double[] sum = new double[24];
        long[] count = new long[24];
        Instant cursor = from;
        ZoneId zone = ZoneId.systemDefault();
        while (cursor.isBefore(to)) {
            Instant chunkEnd = cursor.plusMillis(CHUNK_MS);
            if (chunkEnd.isAfter(to)) {
                chunkEnd = to;
            }
            try {
                FilterCriteria f = new FilterCriteria().setItemName(itemName)
                        .setBeginDate(ZonedDateTime.ofInstant(cursor, zone))
                        .setEndDate(ZonedDateTime.ofInstant(chunkEnd, zone)).setPageSize(PAGE_SIZE)
                        .setOrdering(FilterCriteria.Ordering.ASCENDING);
                for (HistoricItem hi : persistence.query(f)) {
                    double v = parseNumeric(hi.getState().toString());
                    if (Double.isNaN(v)) {
                        continue;
                    }
                    int hod = hi.getTimestamp().withZoneSameInstant(zone).getHour();
                    sum[hod] += v;
                    count[hod]++;
                }
            } catch (Throwable t) {
                LOGGER.debug("TariffComparison: chunk query failed {} [{}..{}]: {}", itemName, cursor, chunkEnd,
                        t.toString());
            }
            cursor = chunkEnd;
        }
        double[] profile = new double[24];
        boolean any = false;
        for (int h = 0; h < 24; h++) {
            profile[h] = count[h] > 0 ? sum[h] / count[h] : 0;
            if (count[h] > 0) {
                any = true;
            }
        }
        return any ? profile : null;
    }

    private String readString(String name) {
        try {
            Item item = itemRegistry.getItem(name);
            return item.getState().toString();
        } catch (ItemNotFoundException e) {
            return "";
        }
    }

    private double parseNumeric(@Nullable String s) {
        if (s == null || s.isEmpty() || "NULL".equals(s) || "UNDEF".equals(s)) {
            return Double.NaN;
        }
        try {
            int sp = s.indexOf(' ');
            return Double.parseDouble(sp > 0 ? s.substring(0, sp) : s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private void publish(String name, org.openhab.core.types.State value) {
        try {
            itemRegistry.getItem(name);
            eventPublisher.post(ItemEventFactory.createStateEvent(name, value, null));
        } catch (Throwable t) {
            // item missing — skip
        }
    }
}

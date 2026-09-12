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
package org.openhab.binding.emsmanager.internal.ledger;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.persistence.FilterCriteria;
import org.openhab.core.persistence.HistoricItem;
import org.openhab.core.persistence.PersistenceService;
import org.openhab.core.persistence.PersistenceServiceRegistry;
import org.openhab.core.persistence.QueryablePersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The days the binding's own 40-day ring no longer reaches, read back from the statistics rollup.
 *
 * <p>
 * This is the ONLY thing in the Past tab that touches persistence, and it is deliberately confined to
 * the {@code EMS_Stat_*} tier — items the binding writes once a day at 23:58, so a whole year is some
 * 365 points. The dense counters next to them hold hundreds of thousands of points a day, openHAB
 * materialises a query's whole range in heap before paging, and asking for a year of one of those is
 * what took this server down on 2026-09-12. Nothing here may be pointed at an item outside the map
 * below.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class DailySeriesSource {

    /** No query may ever span more than this, whatever a caller asks for. */
    public static final int MAX_DAYS = 400;

    /** The rollup tier, and the whole of what this class is allowed to read. */
    private static final Map<String, String> STAT_ITEM = Map.of("EMS_SelfConsumption_kWh",
            "EMS_Stat_SelfConsumption_kWh", "EMS_Supply_kWh", "EMS_Stat_Supply_kWh", "EMS_FeedIn_kWh",
            "EMS_Stat_FeedIn_kWh", "EMS_Cost_EUR", "EMS_Stat_Cost_EUR", "EMS_Savings_EUR", "EMS_Stat_Savings_EUR");

    /** A query set does not change within a day; ten minutes is plenty to pick up a new rollup. */
    private static final long TTL_MS = 10L * 60L * 1000L;

    private static final Logger LOGGER = LoggerFactory.getLogger(DailySeriesSource.class);

    private final PersistenceServiceRegistry registry;
    private final ZoneId zone;
    private final LongSupplier clock;

    private final Map<String, Map<LocalDate, Double>> cache = new HashMap<>();
    private String cachedWindow = "";
    private long cachedAt;

    public DailySeriesSource(PersistenceServiceRegistry registry, ZoneId zone) {
        this(registry, zone, System::currentTimeMillis);
    }

    /** Visible for testing: lets a test own the clock. */
    public DailySeriesSource(PersistenceServiceRegistry registry, ZoneId zone, LongSupplier clock) {
        this.registry = registry;
        this.zone = zone;
        this.clock = clock;
    }

    /**
     * Make sure the window is loaded. Cheap to call every tick: a repeat of the same window inside the
     * TTL does nothing at all.
     */
    public void load(LocalDate from, LocalDate to) {
        LocalDate first = from.isBefore(to.minusDays(MAX_DAYS)) ? to.minusDays(MAX_DAYS) : from;
        String key = first + ".." + to;
        long now = clock.getAsLong();
        if (key.equals(cachedWindow) && now - cachedAt < TTL_MS) {
            return;
        }
        Map<String, Map<LocalDate, Double>> loaded = new HashMap<>();
        QueryablePersistenceService service = queryable();
        if (service != null) {
            for (Map.Entry<String, String> metric : STAT_ITEM.entrySet()) {
                loaded.put(metric.getKey(), readDaily(service, metric.getValue(), first, to));
            }
        }
        cache.clear();
        cache.putAll(loaded);
        cachedWindow = key;
        cachedAt = now;
    }

    /** The rollup for one day, or null where the database has none. */
    public @Nullable Double valueOn(String metric, LocalDate day) {
        Map<LocalDate, Double> series = cache.get(metric);
        return series == null ? null : series.get(day);
    }

    private Map<LocalDate, Double> readDaily(QueryablePersistenceService service, String itemName, LocalDate from,
            LocalDate to) {
        Map<LocalDate, Double> byDay = new HashMap<>();
        try {
            FilterCriteria filter = new FilterCriteria().setItemName(itemName)
                    .setBeginDate(from.atStartOfDay(zone).minusDays(1))
                    .setEndDate(ZonedDateTime.of(to.atTime(23, 59, 59), zone))
                    // one point per day plus room for a restart writing a second: never a flood
                    .setPageSize(MAX_DAYS + 64).setOrdering(FilterCriteria.Ordering.ASCENDING);
            for (HistoricItem point : service.query(filter)) {
                double value = numeric(point.getState().toString());
                if (Double.isNaN(value)) {
                    continue;
                }
                // the rollup is written at 23:58 local, so a point's own date is the day it totals
                byDay.put(point.getTimestamp().withZoneSameInstant(zone).toLocalDate(), value);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Ledger: could not read {} from persistence: {}", itemName, e.getMessage());
        }
        return byDay;
    }

    private static double numeric(String state) {
        try {
            int space = state.indexOf(' ');
            return Double.parseDouble(space > 0 ? state.substring(0, space) : state);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private @Nullable QueryablePersistenceService queryable() {
        PersistenceService service = registry.get("influxdb");
        if (service == null) {
            service = registry.getDefault();
        }
        return service instanceof QueryablePersistenceService q ? q : null;
    }
}

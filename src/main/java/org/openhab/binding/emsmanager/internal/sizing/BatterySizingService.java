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
package org.openhab.binding.emsmanager.internal.sizing;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.persistence.FilterCriteria;
import org.openhab.core.persistence.HistoricItem;
import org.openhab.core.persistence.PersistenceService;
import org.openhab.core.persistence.PersistenceServiceRegistry;
import org.openhab.core.persistence.QueryablePersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Battery sizing simulator.
 *
 * <p>
 * Loads the configured solar + house power historical samples from
 * the configured persistence service, replays them through
 * {@link BatterySimulator} across a sweep of capacities (0 → 30 kWh in
 * 0.5 kWh steps), and publishes:
 * <ul>
 * <li>{@code EMS_BatterySizing_OptimalKwh}</li>
 * <li>{@code EMS_BatterySizing_PaybackYears}</li>
 * <li>{@code EMS_BatterySizing_CurveCsv} ("0=−0,2.0=−15.20,…")</li>
 * <li>{@code EMS_BatterySizing_LastRunAt}</li>
 * </ul>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class BatterySizingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatterySizingService.class);

    private static final double CAPACITY_STEP_KWH = 0.5;
    private static final double CAPACITY_MAX_KWH = 30.0;
    // Hourly resampling — anything finer is overkill and bloats the simulator.
    private static final long RESAMPLE_MS = 60L * 60L * 1000L;

    private final EventPublisher eventPublisher;
    private final ItemRegistry itemRegistry;
    private final PersistenceServiceRegistry persistenceRegistry;

    public BatterySizingService(EventPublisher eventPublisher, ItemRegistry itemRegistry,
            PersistenceServiceRegistry persistenceRegistry) {
        this.eventPublisher = eventPublisher;
        this.itemRegistry = itemRegistry;
        this.persistenceRegistry = persistenceRegistry;
    }

    /**
     * Runs the sizing analysis. Heavy — do not call from the per-tick loop.
     * Returns the curve CSV; null on error.
     */
    public @Nullable String compute(double tariffEurPerKwh, double injectionEurPerKwh, double batteryCostEurPerKwh,
            int paybackYearsHorizon, int lookbackDays, String solarItem, String houseItem) {
        QueryablePersistenceService persistence = getQueryablePersistence();
        if (persistence == null) {
            LOGGER.warn("BatterySizing: no queryable persistence service available");
            return null;
        }

        Instant from = Instant.now().minus(lookbackDays, ChronoUnit.DAYS);
        Instant to = Instant.now();

        TreeMap<Long, Double> solar = bucket(persistence, solarItem, from, to);
        TreeMap<Long, Double> house = bucket(persistence, houseItem, from, to);
        if (solar.isEmpty() || house.isEmpty()) {
            LOGGER.warn("BatterySizing: missing or empty data for {} / {} ({} / {} buckets)", solarItem, houseItem,
                    solar.size(), house.size());
            return null;
        }

        // Build aligned sample series at hourly resolution.
        List<BatterySimulator.Sample> samples = new ArrayList<>();
        for (long t : solar.keySet()) {
            Double s = solar.get(t);
            Double h = house.get(t);
            if (s == null || h == null) {
                continue;
            }
            samples.add(new BatterySimulator.Sample(t, Math.max(0.0, s), Math.max(0.0, h), tariffEurPerKwh,
                    injectionEurPerKwh));
        }
        if (samples.size() < 24) {
            LOGGER.warn("BatterySizing: insufficient aligned samples ({} found, need ≥24)", samples.size());
            return null;
        }

        // Sweep capacity, compute effective net cost (with residual SoC credit).
        StringBuilder csv = new StringBuilder();
        double bestEff = Double.POSITIVE_INFINITY;
        double bestCap = 0;
        double zeroNet = Double.NaN;
        for (double cap = 0.0; cap <= CAPACITY_MAX_KWH + 1e-9; cap += CAPACITY_STEP_KWH) {
            var result = BatterySimulator.simulate(samples, BatterySimulator.Params.defaultFor(cap));
            // Effective annual cost: net cost minus residual SoC value.
            // Scale period to annual: lookbackDays → 365.25.
            double scale = 365.25 / Math.max(1, lookbackDays);
            double effPeriod = result.netCostEur() - result.residualSocKwh() * tariffEurPerKwh;
            double effAnnual = effPeriod * scale;
            if (cap == 0) {
                zeroNet = effAnnual;
            }
            // Objective: annual savings vs no-battery, minus amortised battery cost over horizon.
            double annualSavings = zeroNet - effAnnual;
            double amortised = cap * batteryCostEurPerKwh / Math.max(1, paybackYearsHorizon);
            double objective = annualSavings - amortised;
            if (csv.length() > 0) {
                csv.append(',');
            }
            csv.append(String.format(java.util.Locale.ROOT, "%.1f=%.2f", cap, annualSavings));
            if (-objective < bestEff) {
                bestEff = -objective;
                bestCap = cap;
            }
        }

        double annualSavingsAtBest = compositeSavings(bestCap, samples, tariffEurPerKwh, injectionEurPerKwh,
                lookbackDays);
        double payback = annualSavingsAtBest > 0 ? (bestCap * batteryCostEurPerKwh / annualSavingsAtBest)
                : Double.POSITIVE_INFINITY;

        publish("EMS_BatterySizing_OptimalKwh", new DecimalType(bestCap));
        publish("EMS_BatterySizing_PaybackYears",
                Double.isFinite(payback) ? new DecimalType(Math.round(payback * 10.0) / 10.0) : new DecimalType(99));
        publish("EMS_BatterySizing_CurveCsv", new StringType(csv.toString()));
        publish("EMS_BatterySizing_LastRunAt", new StringType(ZonedDateTime.now().toString()));

        LOGGER.info("BatterySizing: optimal {} kWh, payback {} yr, samples={}, lookback={} d", bestCap,
                Double.isFinite(payback) ? String.format("%.1f", payback) : "∞", samples.size(), lookbackDays);
        return csv.toString();
    }

    private double compositeSavings(double cap, List<BatterySimulator.Sample> samples, double tariff, double injection,
            int lookbackDays) {
        var resultZero = BatterySimulator.simulate(samples, BatterySimulator.Params.defaultFor(0));
        var resultCap = BatterySimulator.simulate(samples, BatterySimulator.Params.defaultFor(cap));
        double scale = 365.25 / Math.max(1, lookbackDays);
        double effZero = resultZero.netCostEur() - resultZero.residualSocKwh() * tariff;
        double effCap = resultCap.netCostEur() - resultCap.residualSocKwh() * tariff;
        return (effZero - effCap) * scale;
    }

    private @Nullable QueryablePersistenceService getQueryablePersistence() {
        PersistenceService svc = persistenceRegistry.get("influxdb");
        if (svc == null) {
            svc = persistenceRegistry.getDefault();
        }
        if (svc instanceof QueryablePersistenceService q) {
            return q;
        }
        return null;
    }

    /** Resample to hourly buckets. Streams the query in 24-hour chunks to bound JVM heap. */
    private TreeMap<Long, Double> bucket(QueryablePersistenceService persistence, String itemName, Instant from,
            Instant to) {
        TreeMap<Long, double[]> acc = new TreeMap<>(); // bucketMs → [sum, count]
        try {
            itemRegistry.getItem(itemName);
        } catch (ItemNotFoundException e) {
            LOGGER.warn("BatterySizing: item not found {}", itemName);
            return new TreeMap<>();
        }
        // Items persisted on everyChange (e.g. a solar power item) can have hundreds of
        // thousands of points per day. We chunk by 3 h AND cap each chunk with a
        // page size so a single query can't exhaust the heap. Per-chunk failures
        // are caught — partial data still yields a usable estimate.
        // Heap-safety: query one resample bucket (1 h) at a time, capped at a small
        // page size. Bounding each query to ≤PAGE_SIZE objects keeps heap flat
        // regardless of total lookback (objects GC'd between chunks). Chunk == bucket
        // so an ASCENDING page is a fair sample of the whole bucket (no truncation bias).
        final long chunkMs = RESAMPLE_MS;
        final int pageSize = 360; // ≈1 sample/10s/hour — ample for an hourly average
        Instant cursor = from;
        while (cursor.isBefore(to)) {
            Instant chunkEnd = cursor.plusMillis(chunkMs);
            if (chunkEnd.isAfter(to)) {
                chunkEnd = to;
            }
            try {
                FilterCriteria f = new FilterCriteria().setItemName(itemName)
                        .setBeginDate(java.time.ZonedDateTime.ofInstant(cursor, java.time.ZoneId.systemDefault()))
                        .setEndDate(java.time.ZonedDateTime.ofInstant(chunkEnd, java.time.ZoneId.systemDefault()))
                        .setPageSize(pageSize).setOrdering(FilterCriteria.Ordering.ASCENDING);
                Iterable<HistoricItem> series = persistence.query(f);
                for (HistoricItem hi : series) {
                    double v = parseNumeric(hi.getState().toString());
                    if (Double.isNaN(v)) {
                        continue;
                    }
                    long t = hi.getTimestamp().toInstant().toEpochMilli();
                    long bucket = (t / RESAMPLE_MS) * RESAMPLE_MS;
                    acc.computeIfAbsent(bucket, k -> new double[] { 0, 0 });
                    double[] sc = acc.get(bucket);
                    if (sc != null) {
                        sc[0] += v;
                        sc[1] += 1;
                    }
                }
            } catch (Throwable t) {
                LOGGER.debug("BatterySizing: chunk query failed for {} [{} .. {}]: {}", itemName, cursor, chunkEnd,
                        t.toString());
                // continue with next chunk; partial data is still useful
            }
            cursor = chunkEnd;
        }
        TreeMap<Long, Double> result = new TreeMap<>();
        for (Map.Entry<Long, double[]> e : acc.entrySet()) {
            double[] sc = e.getValue();
            if (sc[1] > 0) {
                result.put(e.getKey(), sc[0] / sc[1]);
            }
        }
        return result;
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
            // item missing — silently skip
        }
    }
}

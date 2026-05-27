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
package org.openhab.binding.emsmanager.internal.tariff;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.tariff.spot.HourlyPrices;
import org.openhab.binding.emsmanager.internal.tariff.spot.SpotPriceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pluggable dynamic-spot tariff provider. Wraps a {@link SpotPriceClient}
 * and caches its result for {@code refreshIntervalMin} minutes (day-ahead
 * prices don't change intraday — refreshing once an hour or once a day is
 * fine and friendly to upstream APIs).
 *
 * <p>
 * The handler still calls {@link #snapshot(Instant)} every minute;
 * this class returns the cached schedule unless the cache has expired or
 * is empty.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class DynamicSpotTariff implements TariffProvider {

    public static final String KIND = "dynamic-spot";

    private static final Logger LOGGER = LoggerFactory.getLogger(DynamicSpotTariff.class);

    private final SpotPriceClient client;
    private final long refreshIntervalMs;

    private volatile @Nullable HourlyPrices cachedPrices;
    private volatile long lastFetchMs = 0L;

    public DynamicSpotTariff(SpotPriceClient client, int refreshIntervalMin) {
        this.client = client;
        this.refreshIntervalMs = Math.max(5, refreshIntervalMin) * 60L * 1000L;
        // Try to seed from disk cache so a bundle restart doesn't burn an API call.
        HourlyPrices cached = TariffCache.load();
        if (cached != null && !cached.prices().isEmpty()) {
            this.cachedPrices = cached;
            this.lastFetchMs = cached.fetchedAt().toEpochMilli();
            LOGGER.info("DynamicSpotTariff: loaded {} cached prices from disk (age {}s)", cached.prices().size(),
                    (System.currentTimeMillis() - this.lastFetchMs) / 1000L);
        }
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public TariffSnapshot snapshot(Instant now) {
        long nowMs = System.currentTimeMillis();
        HourlyPrices prices = cachedPrices;
        if (prices == null || prices.prices().isEmpty() || (nowMs - lastFetchMs) > refreshIntervalMs) {
            HourlyPrices fresh = client.fetch();
            if (fresh.lastError() != null) {
                // Keep returning the stale cache (if any) but flag the error.
                if (prices != null && !prices.prices().isEmpty()) {
                    return toSnapshot(prices, now, fresh.lastError());
                }
                return new TariffSnapshot(now, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, null, null,
                        new double[0], new double[0], fresh.lastError());
            }
            cachedPrices = fresh;
            lastFetchMs = nowMs;
            TariffCache.save(fresh);
            prices = fresh;
            LOGGER.info("DynamicSpotTariff[{}]: fetched {} hourly prices", client.subProvider(), fresh.prices().size());
        }
        return toSnapshot(prices, now, null);
    }

    private TariffSnapshot toSnapshot(HourlyPrices prices, Instant now, @Nullable String lastError) {
        ZoneId zone = ZoneId.systemDefault();
        Instant startOfToday = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        double[] sched24 = prices.today24h(startOfToday);
        double[] sched48 = prices.schedule48h(startOfToday);

        double nowPrice = prices.priceAt(now);
        double next1hPrice = prices.priceAt(now.plusSeconds(3600));

        double min = Double.NaN, max = Double.NaN, sum = 0.0;
        int n = 0, minIdx = -1, maxIdx = -1;
        for (int i = 0; i < sched24.length; i++) {
            double v = sched24[i];
            if (Double.isNaN(v)) {
                continue;
            }
            if (Double.isNaN(min) || v < min) {
                min = v;
                minIdx = i;
            }
            if (Double.isNaN(max) || v > max) {
                max = v;
                maxIdx = i;
            }
            sum += v;
            n++;
        }
        double avg = n > 0 ? sum / n : Double.NaN;
        Instant cheapest = minIdx >= 0 ? startOfToday.plusSeconds(minIdx * 3600L) : null;
        Instant mostExpensive = maxIdx >= 0 ? startOfToday.plusSeconds(maxIdx * 3600L) : null;

        return new TariffSnapshot(now, nowPrice, next1hPrice, min, max, avg, cheapest, mostExpensive, sched24, sched48,
                lastError);
    }
}

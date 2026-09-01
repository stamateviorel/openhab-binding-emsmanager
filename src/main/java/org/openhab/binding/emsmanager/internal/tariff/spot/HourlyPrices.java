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
import java.util.TreeMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * A 24/48-hour price schedule returned by spot-price clients. Each entry
 * is keyed by the hour-start instant; price is in €/kWh after any markup
 * has been applied.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record HourlyPrices(TreeMap<Instant, Double> prices, Instant fetchedAt, @Nullable String lastError) {

    public static HourlyPrices empty(String error) {
        return new HourlyPrices(new TreeMap<>(), Instant.now(), error);
    }

    /** Each entry prices exactly one hour. */
    static final long HOUR_SECONDS = 3600L;

    /** Find the price for the hour containing {@code at}, or NaN if that hour was never published. */
    public double priceAt(Instant at) {
        var entry = prices.floorEntry(at);
        if (entry == null) {
            return Double.NaN;
        }
        // floorEntry also answers for any instant past the LAST published hour, which would price
        // every hour after 23:00 (and every day of a feed outage) at that one stale value.
        if (at.getEpochSecond() - entry.getKey().getEpochSecond() >= HOUR_SECONDS) {
            return Double.NaN;
        }
        return entry.getValue();
    }

    /** Whether a price was published for the hour containing {@code at}. */
    public boolean covers(Instant at) {
        return !Double.isNaN(priceAt(at));
    }

    /** Return today's 24 prices starting from start-of-today local. */
    public double[] today24h(Instant startOfTodayLocal) {
        return slice(startOfTodayLocal, 24);
    }

    public double[] schedule48h(Instant startOfTodayLocal) {
        return slice(startOfTodayLocal, 48);
    }

    private double[] slice(Instant startLocal, int hours) {
        double[] out = new double[hours];
        for (int i = 0; i < hours; i++) {
            out[i] = priceAt(startLocal.plusSeconds(i * 3600L));
        }
        return out;
    }
}

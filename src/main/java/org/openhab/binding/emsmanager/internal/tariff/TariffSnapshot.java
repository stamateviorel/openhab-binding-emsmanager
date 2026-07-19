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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Immutable tariff snapshot. {@code schedule24h}/{@code schedule48h} are
 * arrays of hourly prices in €/kWh, indexed [0..23] / [0..47] starting from
 * the top of the local-time hour containing {@code refreshedAt}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record TariffSnapshot(Instant refreshedAt, double nowPriceEurPerKWh, double next1hPriceEurPerKWh,
        double todayMinPrice, double todayMaxPrice, double todayAvgPrice, @Nullable Instant cheapestHourStart,
        @Nullable Instant mostExpensiveHourStart, double[] schedule24h, double[] schedule48h,
        @Nullable String lastError) {

    public static final TariffSnapshot EMPTY = new TariffSnapshot(Instant.EPOCH, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, null, null, new double[0], new double[0], "not initialized");
}

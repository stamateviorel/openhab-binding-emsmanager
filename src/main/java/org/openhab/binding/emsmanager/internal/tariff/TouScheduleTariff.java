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
 * User-defined hourly schedule: 24 prices, one per hour of day, supplied as
 * a comma-separated string in the Thing config.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class TouScheduleTariff implements TariffProvider {

    public static final String KIND = "tou-schedule";

    private final double[] hourlyPrices; // length 24
    private final @Nullable String parseError;

    public TouScheduleTariff(String hourlyPricesCsv) {
        double[] parsed = new double[24];
        String err = null;
        try {
            String[] parts = hourlyPricesCsv.split(",");
            if (parts.length != 24) {
                err = "expected 24 comma-separated prices, got " + parts.length;
            } else {
                for (int i = 0; i < 24; i++) {
                    parsed[i] = Double.parseDouble(parts[i].trim());
                }
            }
        } catch (Exception e) {
            err = e.getMessage();
        }
        this.hourlyPrices = parsed;
        this.parseError = err;
    }

    @Override
    public String kind() {
        return KIND;
    }

    public @Nullable String parseError() {
        return parseError;
    }

    @Override
    public TariffSnapshot snapshot(Instant now) {
        TariffSnapshot base = TariffMath.buildSnapshotStatic(now, h -> hourlyPrices[h]);
        if (parseError == null) {
            return base;
        }
        return new TariffSnapshot(base.refreshedAt(), base.nowPriceEurPerKWh(), base.next1hPriceEurPerKWh(),
                base.todayMinPrice(), base.todayMaxPrice(), base.todayAvgPrice(), base.cheapestHourStart(),
                base.mostExpensiveHourStart(), base.schedule24h(), base.schedule48h(), parseError);
    }
}

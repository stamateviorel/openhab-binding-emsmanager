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
package org.openhab.binding.emsmanager.internal.forecast;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Immutable forecast snapshot returned by a {@link SolarForecastProvider}.
 * Any field can be {@link Double#NaN} or {@code null} when the upstream API
 * doesn't supply it (or when a fetch failed and we're returning the last
 * known good snapshot).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record ForecastSnapshot(Instant refreshedAt, double nowW, double next1hWh, double next3hWh, double next6hWh,
        double todayKwh, double tomorrowKwh, @Nullable Instant peakTodayAt, @Nullable Integer rateLimitRemaining,
        @Nullable String lastError,
        // Hourly forecast as "HH:MM=W,HH:MM=W,…" for today (00..23).
        // Used by the forecast-vs-actual chart.
        String hourlyTodayCsv) {

    public static final ForecastSnapshot EMPTY = new ForecastSnapshot(Instant.EPOCH, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, null, null, null, "");
}

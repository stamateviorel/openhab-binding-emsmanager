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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Helpers shared by every tariff provider: build a {@link TariffSnapshot}
 * from a 24-hour price function (one price per hour-of-day).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class TariffMath {

    private TariffMath() {
    }

    /**
     * Build a snapshot whose 24h schedule starts at today's 00:00 in the
     * system zone. {@code priceForHourOfDay} returns the price for hours
     * 0..23 — useful for static, day-based providers (Flat / DayNight /
     * TouSchedule). For dynamic providers that vary day-to-day, use
     * {@link #buildSnapshotMultiDay}.
     */
    public static TariffSnapshot buildSnapshotStatic(Instant now,
            java.util.function.IntToDoubleFunction priceForHourOfDay) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime nowZ = ZonedDateTime.ofInstant(now, zone);
        ZonedDateTime startOfToday = nowZ.toLocalDate().atStartOfDay(zone);

        double[] sched24 = new double[24];
        double[] sched48 = new double[48];
        for (int h = 0; h < 24; h++) {
            sched24[h] = priceForHourOfDay.applyAsDouble(h);
            sched48[h] = sched24[h];
            sched48[h + 24] = priceForHourOfDay.applyAsDouble(h);
        }

        int hourNow = nowZ.getHour();
        double nowPrice = sched24[hourNow];
        double next1hPrice = sched48[hourNow + 1];

        double min = sched24[0], max = sched24[0], sum = 0.0;
        int minIdx = 0, maxIdx = 0;
        for (int i = 0; i < 24; i++) {
            if (sched24[i] < min) {
                min = sched24[i];
                minIdx = i;
            }
            if (sched24[i] > max) {
                max = sched24[i];
                maxIdx = i;
            }
            sum += sched24[i];
        }
        double avg = sum / 24.0;

        Instant cheapest = startOfToday.plusHours(minIdx).toInstant();
        Instant mostExpensive = startOfToday.plusHours(maxIdx).toInstant();

        return new TariffSnapshot(now, nowPrice, next1hPrice, min, max, avg, cheapest, mostExpensive, sched24, sched48,
                null);
    }

    /** Day/Night helper — is the given local time within [start, end) modulo 24? */
    public static boolean inDayWindow(int hourOfDay, int dayStart, int dayEnd) {
        if (dayStart <= dayEnd) {
            return hourOfDay >= dayStart && hourOfDay < dayEnd;
        }
        // wraps midnight: e.g. dayStart=22, dayEnd=6 means "day" is 22:00-06:00
        return hourOfDay >= dayStart || hourOfDay < dayEnd;
    }

    /** Convenience: build today's start-of-day instant. */
    public static Instant startOfToday(Instant now) {
        return LocalDate.ofInstant(now, ZoneId.systemDefault()).atTime(LocalTime.MIDNIGHT)
                .atZone(ZoneId.systemDefault()).toInstant();
    }

    public static @Nullable Instant nullableInstant(@Nullable Instant value) {
        return value;
    }
}

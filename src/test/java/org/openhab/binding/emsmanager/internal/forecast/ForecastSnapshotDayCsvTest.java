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

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tomorrow's hourly forecast is cut from the same series today's comes from.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class ForecastSnapshotDayCsvTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Brussels");

    private static String series(LocalDate day, int hour, double w) {
        return ZonedDateTime.of(day.atTime(hour, 0), ZONE).toEpochSecond() + "=" + w;
    }

    @Test
    void onlyTheAskedDayIsReturnedWithAllTwentyFourHours() {
        LocalDate today = LocalDate.of(2026, 9, 3);
        String csv = series(today, 12, 8000) + "," + series(today.plusDays(1), 12, 5000) + ","
                + series(today.plusDays(1), 13, 5200);

        String tomorrow = ForecastSnapshot.hourlyCsvFor(csv, today.plusDays(1), ZONE);

        String[] parts = tomorrow.split(",");
        assertEquals(24, parts.length);
        assertEquals("12:00=5000", parts[12]);
        assertEquals("13:00=5200", parts[13]);
        assertEquals("00:00=0", parts[0], "hours the series lacks are zero, not missing");
        assertFalse(tomorrow.contains("8000"), "today's value must not leak into tomorrow");
    }

    @Test
    void aDayTheSeriesDoesNotCoverIsEmptyNotZeros() {
        LocalDate today = LocalDate.of(2026, 9, 3);
        assertEquals("", ForecastSnapshot.hourlyCsvFor(series(today, 12, 8000), today.plusDays(2), ZONE));
    }
}

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

import java.time.Duration;
import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * The handler published whatever the cache held on init - todayKwh, nowW and peakTodayAt from the
 * day the cache was written - and kept the last good snapshot on the channels for as long as the
 * provider stayed down. After a few days either way the dashboard showed a confident forecast for a
 * day that was long gone.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class ForecastSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");

    private static ForecastSnapshot fetchedAt(Instant refreshedAt) {
        long past = NOW.minus(Duration.ofHours(1)).getEpochSecond();
        long future = NOW.plus(Duration.ofHours(1)).getEpochSecond();
        return new ForecastSnapshot(refreshedAt, 2500.0, 2000.0, 5000.0, 9000.0, 22.0, 18.0,
                NOW.minus(Duration.ofHours(2)), 10, null, "10:00=2500,11:00=3000", past + "=1500," + future + "=3000");
    }

    @Test
    void aRecentSnapshotIsPresentedAsItIs() {
        ForecastSnapshot snap = fetchedAt(NOW.minus(Duration.ofHours(2)));

        assertSame(snap, snap.presentableAt(NOW));
    }

    @Test
    void aSnapshotFromTwoDaysAgoDoesNotPretendToBeToday() {
        ForecastSnapshot aged = fetchedAt(NOW.minus(Duration.ofHours(40))).presentableAt(NOW);

        assertTrue(Double.isNaN(aged.todayKwh()), "22 kWh was some other day's forecast");
        assertTrue(Double.isNaN(aged.nowW()));
        assertTrue(Double.isNaN(aged.tomorrowKwh()));
        assertNull(aged.peakTodayAt());
        assertEquals("", aged.hourlyTodayCsv());
    }

    @Test
    void onlyTheSeriesPointsStillAheadSurvive() {
        ForecastSnapshot aged = fetchedAt(NOW.minus(Duration.ofHours(40))).presentableAt(NOW);

        long future = NOW.plus(Duration.ofHours(1)).getEpochSecond();
        assertEquals(future + "=3000", aged.hourlySeriesCsv(), "a point in the past is no forecast");
    }

    @Test
    void theEmptySnapshotIsLeftAlone() {
        assertSame(ForecastSnapshot.EMPTY, ForecastSnapshot.EMPTY.presentableAt(NOW));
    }
}

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

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TariffMath} — the price arithmetic every money figure and every "charge later" decision is built
 * on, and which had no tests at all.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class TariffMathTest {

    private Instant at(int hourOfDay) {
        ZonedDateTime day = ZonedDateTime.of(2026, 1, 11, hourOfDay, 0, 0, 0, ZoneId.systemDefault());
        return day.toInstant();
    }

    private int hourOf(Instant instant) {
        return ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()).getHour();
    }

    @Test
    void aFlatDayIsItsOwnMinimumMaximumAndAverage() {
        TariffSnapshot snap = TariffMath.buildSnapshotStatic(at(12), hour -> 0.30);

        assertEquals(0.30, snap.todayMinPrice(), 1e-9);
        assertEquals(0.30, snap.todayMaxPrice(), 1e-9);
        assertEquals(0.30, snap.todayAvgPrice(), 1e-9);
        assertEquals(0.30, snap.nowPriceEurPerKWh(), 1e-9);
    }

    @Test
    void theCheapestAndDearestHoursAreFound() {
        // cheapest at 03:00, dearest at 19:00
        TariffSnapshot snap = TariffMath.buildSnapshotStatic(at(12),
                hour -> hour == 3 ? 0.10 : hour == 19 ? 0.90 : 0.30);

        assertEquals(0.10, snap.todayMinPrice(), 1e-9);
        assertEquals(0.90, snap.todayMaxPrice(), 1e-9);
        assertEquals(3, hourOf(assertNonNull(snap.cheapestHourStart())));
        assertEquals(19, hourOf(assertNonNull(snap.mostExpensiveHourStart())));
    }

    /**
     * Two hours at the same cheapest price is the ordinary case on a flat or day/night tariff, and the earlier one
     * has to win — otherwise "the cheapest hour" moves around between ticks for no reason a user could explain.
     */
    @Test
    void theEarliestOfEquallyCheapHoursWins() {
        TariffSnapshot snap = TariffMath.buildSnapshotStatic(at(12), hour -> hour >= 2 && hour <= 5 ? 0.10 : 0.30);

        assertEquals(2, hourOf(assertNonNull(snap.cheapestHourStart())), "the first of the cheap hours must win");
    }

    @Test
    void thePriceNowIsThePriceOfTheCurrentHour() {
        TariffSnapshot snap = TariffMath.buildSnapshotStatic(at(7), hour -> hour * 0.01);

        assertEquals(0.07, snap.nowPriceEurPerKWh(), 1e-9);
    }

    @Test
    void theNextHourLooksOneHourAhead() {
        TariffSnapshot snap = TariffMath.buildSnapshotStatic(at(7), hour -> hour * 0.01);

        assertEquals(0.08, snap.next1hPriceEurPerKWh(), 1e-9,
                "the next-hour price must be the following hour, not this one");
    }

    /** The last hour of the day has to look into tomorrow, which is what the 48-hour schedule exists for. */
    @Test
    void theNextHourAtMidnightRollsIntoTomorrow() {
        TariffSnapshot snap = TariffMath.buildSnapshotStatic(at(23), hour -> hour == 0 ? 0.05 : 0.30);

        assertEquals(0.05, snap.next1hPriceEurPerKWh(), 1e-9,
                "23:00 must see tomorrow's 00:00 rather than falling over");
    }

    @Test
    void theScheduleCoversTwentyFourHoursTodayAndFortyEightAcrossTwoDays() {
        TariffSnapshot snap = TariffMath.buildSnapshotStatic(at(12), hour -> hour * 0.01);

        assertEquals(24, snap.schedule24h().length);
        assertEquals(48, snap.schedule48h().length);
        assertEquals(snap.schedule24h()[5], snap.schedule48h()[5], 1e-9);
        assertEquals(snap.schedule24h()[5], snap.schedule48h()[29], 1e-9, "tomorrow repeats a static tariff");
    }

    @Test
    void theAverageIsOverTheWholeDayNotTheHoursSoFar() {
        // half the day at 0.20, half at 0.40
        TariffSnapshot snap = TariffMath.buildSnapshotStatic(at(2), hour -> hour < 12 ? 0.20 : 0.40);

        assertEquals(0.30, snap.todayAvgPrice(), 1e-9);
    }

    @Test
    void aDayWindowInsideOneDayIsInclusiveOfItsStart() {
        assertTrue(TariffMath.inDayWindow(7, 7, 22));
        assertTrue(TariffMath.inDayWindow(21, 7, 22));
        assertFalse(TariffMath.inDayWindow(6, 7, 22));
        assertFalse(TariffMath.inDayWindow(22, 7, 22), "the end hour is where the day rate stops");
    }

    /** A window that wraps midnight is the night rate, and it is the one people get wrong. */
    @Test
    void aDayWindowThatWrapsMidnightIsHandled() {
        assertTrue(TariffMath.inDayWindow(23, 22, 6));
        assertTrue(TariffMath.inDayWindow(2, 22, 6));
        assertFalse(TariffMath.inDayWindow(12, 22, 6));
    }

    @Test
    void startOfTodayIsMidnightInTheSystemZone() {
        Instant start = TariffMath.startOfToday(at(15));

        assertEquals(0, hourOf(start));
    }

    private <T> T assertNonNull(T value) {
        assertNotNull(value);
        return value;
    }
}

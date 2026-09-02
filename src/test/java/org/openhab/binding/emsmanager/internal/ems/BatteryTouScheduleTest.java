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
package org.openhab.binding.emsmanager.internal.ems;

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * The battery time-of-use schedule: what it sends, when it lets go, and when it does not bother
 * charging from the grid at all.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class BatteryTouScheduleTest {

    private static ZonedDateTime at(int hour, int minute) {
        return ZonedDateTime.of(2026, 3, 10, hour, minute, 0, 0, ZoneId.systemDefault());
    }

    @Test
    void chargesAtNightAndReleasesWithAnExplicitZeroAtSix() {
        BatteryTouSchedule s = new BatteryTouSchedule();

        assertEquals(-2000.0, s.setpointW(at(3, 0), false, 40, Double.NaN));
        assertEquals(-2000.0, s.setpointW(at(5, 59), false, 60, Double.NaN));
        assertEquals(0.0, s.setpointW(at(6, 0), false, 70, Double.NaN),
                "leaving the window must hand the inverter an explicit neutral, not silence");
        assertEquals("leaving ToU window → neutral 0 W", s.lastReason());
        assertNull(s.setpointW(at(6, 0), false, 70, Double.NaN), "and only once");
        assertNull(s.setpointW(at(12, 0), false, 70, Double.NaN));
    }

    @Test
    void dischargesInTheEveningAndReleasesAtNine() {
        BatteryTouSchedule s = new BatteryTouSchedule();

        assertEquals(2000.0, s.setpointW(at(18, 0), false, 80, Double.NaN));
        assertEquals(0.0, s.setpointW(at(21, 0), false, 60, Double.NaN));
        assertNull(s.setpointW(at(21, 0), false, 60, Double.NaN));
    }

    /** Dropping to the reserve mid-peak is leaving the window too - the discharge must stop. */
    @Test
    void reachingTheReserveMidPeakStopsTheDischarge() {
        BatteryTouSchedule s = new BatteryTouSchedule();
        s.setpointW(at(18, 0), false, 35, Double.NaN);

        assertEquals(0.0, s.setpointW(at(18, 30), true, 30, Double.NaN));
    }

    @Test
    void outsideAnyWindowWithNothingSentBeforeStaysSilent() {
        assertNull(new BatteryTouSchedule().setpointW(at(12, 0), false, 50, Double.NaN));
    }

    @Test
    void doesNotGridChargeANearlyFullBattery() {
        assertNull(new BatteryTouSchedule().setpointW(at(3, 0), false, 92, Double.NaN));
    }

    @Test
    void doesNotGridChargeAheadOfASunnyDay() {
        assertNull(new BatteryTouSchedule().setpointW(at(3, 0), false, 40, 25.0));
    }

    @Test
    void chargesWhenTheForecastIsUnknownOrDull() {
        assertEquals(-2000.0, new BatteryTouSchedule().setpointW(at(3, 0), false, 40, Double.NaN));
        assertEquals(-2000.0, new BatteryTouSchedule().setpointW(at(3, 0), false, 40, 8.0));
    }

    /** Once the battery is full the night is over: dipping back to 89 % must not start a new top-up. */
    @Test
    void hittingFullMidNightReleasesAndStaysOffForTheRestOfTheNight() {
        BatteryTouSchedule s = new BatteryTouSchedule();
        assertEquals(-2000.0, s.setpointW(at(3, 0), false, 85, Double.NaN));

        assertEquals(0.0, s.setpointW(at(4, 0), false, 90, Double.NaN), "full → release");
        assertNull(s.setpointW(at(4, 30), false, 89, Double.NaN), "no top-up flapping around the threshold");
        assertNull(s.setpointW(at(5, 30), false, 80, Double.NaN));
    }
}

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
package org.openhab.binding.emsmanager.internal.controller.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * A schedule is typed by hand, so parsing it has to be forgiving - but a typo used to drop its
 * entry without a word, leaving half a schedule running and no way to tell.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class BoilerScheduleParseTest {

    private static Map<DayOfWeek, ? extends List<?>> parse(String csv) {
        return BoilerScheduleController.parseForTest(csv);
    }

    @Test
    void aWellFormedScheduleParses() {
        var windows = parse("MON:06:00-08:00,MON:18:00-21:00,SAT:09:00-11:00");

        assertEquals(2, windows.get(DayOfWeek.MONDAY).size());
        assertEquals(1, windows.get(DayOfWeek.SATURDAY).size());
    }

    @Test
    void aMalformedEntryDoesNotTakeTheGoodOnesWithIt() {
        var windows = parse("MON:06:00-08:00,MON:25:00-99:00,MON:18:00-21:00");

        assertEquals(2, windows.get(DayOfWeek.MONDAY).size(), "one bad entry must not cost the schedule its good ones");
    }

    @Test
    void anEntryWithNoRangeIsSkipped() {
        var windows = parse("MON:06:00-08:00,TUE:no-times");

        assertEquals(1, windows.get(DayOfWeek.MONDAY).size());
        assertNull(windows.get(DayOfWeek.TUESDAY), "an entry with no times cannot become a window");
    }

    @Test
    void anEmptyScheduleIsEmptyRatherThanAFailure() {
        assertTrue(parse("").isEmpty());
        assertTrue(parse("   ").isEmpty());
    }

    @Test
    void anEntirelyUnparseableScheduleYieldsNothingRatherThanSomethingWrong() {
        assertTrue(parse("nonsense,more nonsense").isEmpty(), "a schedule nobody can read must not half-apply");
    }
}

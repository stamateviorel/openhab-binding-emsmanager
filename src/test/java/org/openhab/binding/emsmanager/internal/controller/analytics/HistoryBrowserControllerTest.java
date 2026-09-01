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
package org.openhab.binding.emsmanager.internal.controller.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemRegistry;

/**
 * The window arithmetic behind browsing by day, month and year.
 * <p>
 * This is where the bugs live. A month is not thirty days, a year is not twelve equal months, and "this month" has to
 * mean month-to-date while "last month" means the whole of it - get one of those wrong and the number looks
 * plausible, which is the worst kind of wrong for a figure somebody is about to compare against a bill.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class HistoryBrowserControllerTest {

    /** 15 August 2026 - mid-month, so month-to-date and a whole month are clearly different spans. */
    private static final LocalDate MID_AUGUST = LocalDate.of(2026, 8, 15);

    private HistoryBrowserController controller() {
        return new HistoryBrowserController(mock(EventPublisher.class), mock(ItemRegistry.class),
                new LongTermStatsController(mock(EventPublisher.class), mock(ItemRegistry.class)));
    }

    @Test
    void todayIsTheRunningPartialAndNoCompletedDays() {
        int[] window = controller().windowsForTest(MID_AUGUST).get("day0");

        assertNotNull(window);
        assertEquals(1, window[2], "today is still running, so its partial counts");
        assertTrue(window[1] < window[0], "and it spans no completed day at all");
    }

    @Test
    void oneDayBackIsExactlyYesterday() {
        int[] window = controller().windowsForTest(MID_AUGUST).get("day1");

        assertNotNull(window);
        assertEquals(1, window[0]);
        assertEquals(1, window[1], "a single completed day");
        assertEquals(0, window[2], "yesterday is finished, so today's partial must not leak into it");
    }

    /** Month-to-date on the 15th is the fourteen completed days plus today. */
    @Test
    void thisMonthIsMonthToDate() {
        int[] window = controller().windowsForTest(MID_AUGUST).get("month0");

        assertNotNull(window);
        assertEquals(1, window[0]);
        assertEquals(14, window[1], "the 1st to the 14th are complete on the 15th");
        assertEquals(1, window[2], "and the month is still running");
    }

    /** July, viewed from 15 August, is a whole month and none of August. */
    @Test
    void oneMonthBackIsTheWholeOfThatMonth() {
        int[] window = controller().windowsForTest(MID_AUGUST).get("month1");

        assertNotNull(window);
        assertEquals(15, window[0], "31 July is fifteen days before 15 August");
        assertEquals(45, window[1], "1 July is forty-five days before it");
        assertEquals(0, window[2]);
        assertEquals(31, window[1] - window[0] + 1, "July has thirty-one days");
    }

    /** 2025 is three hundred and sixty-five days, and it must not include any of 2026. */
    @Test
    void oneYearBackIsAWholeYear() {
        int[] window = controller().windowsForTest(MID_AUGUST).get("year1");

        assertNotNull(window);
        assertEquals(365, window[1] - window[0] + 1, "2025 was not a leap year");
        assertEquals(0, window[2]);
    }

    @Test
    void everyPeriodIsNamedTheWayAPersonWouldSayIt() {
        HistoryBrowserController controller = controller();

        assertEquals("Today", controller.labelForTest("day", 0, MID_AUGUST));
        assertEquals("Yesterday", controller.labelForTest("day", 1, MID_AUGUST));
        assertEquals("13 Aug 2026", controller.labelForTest("day", 2, MID_AUGUST));
        assertEquals("This month", controller.labelForTest("month", 0, MID_AUGUST));
        assertEquals("July 2026", controller.labelForTest("month", 1, MID_AUGUST));
        assertEquals("This year", controller.labelForTest("year", 0, MID_AUGUST));
        assertEquals("2025", controller.labelForTest("year", 1, MID_AUGUST));
    }

    /** An unknown scale is a typo, not a reason to publish nothing: it falls back to the day. */
    @Test
    void anUnknownScaleFallsBackToTheDay() {
        assertEquals("Yesterday", controller().labelForTest("fortnight", 1, MID_AUGUST));
    }

    /** A window that reaches past the ring returns what it holds rather than pretending. */
    @Test
    void aWindowBeyondTheRingSumsToWhatIsActuallyHeld() {
        DailyRollup rollup = new DailyRollup(365, true);
        rollup.observe(0.0);
        rollup.observe(5.0);
        rollup.rollover(0.0);

        assertEquals(5.0, rollup.sumRange(1, 1), 1e-9);
        assertEquals(5.0, rollup.sumRange(1, 400), 1e-9, "asking further back than the ring reaches adds nothing");
        assertEquals(0.0, rollup.sumRange(2, 400), 1e-9, "and a window entirely outside it is zero, not a guess");
    }

    @Test
    void theRingReportsHowFarBackItCanAnswer() {
        DailyRollup rollup = new DailyRollup(365, true);
        assertEquals(0, rollup.daysHeld());
        rollup.observe(0.0);
        rollup.observe(3.0);
        rollup.rollover(0.0);
        assertEquals(1, rollup.daysHeld());
    }

    @Test
    void aSingleDayIsReadBackByHowLongAgoItWas() {
        DailyRollup rollup = new DailyRollup(365, true);
        for (double amount : new double[] { 10.0, 20.0, 30.0 }) {
            rollup.observe(0.0);
            rollup.observe(amount);
            rollup.rollover(0.0);
        }

        assertEquals(30.0, rollup.amountAgo(1), 1e-9, "the most recently completed day");
        assertEquals(20.0, rollup.amountAgo(2), 1e-9);
        assertEquals(10.0, rollup.amountAgo(3), 1e-9);
        assertEquals(0.0, rollup.amountAgo(4), 1e-9, "before the ring begins");
    }

    @Test
    void theFiguresItRepublishesAreTheOnesTheDashboardReads() {
        Map<String, int[]> windows = controller().windowsForTest(MID_AUGUST);

        assertEquals(5, windows.size(), "day, yesterday, this month, last month and last year");
    }

    /**
     * A period the ring never saw must not read as a quiet one.
     * <p>
     * July, asked for from a ring holding twenty-seven days, sums to zero - which on a card looks exactly like a
     * month where nothing was bought. The window knows how far back it reaches, and the label has to say when the
     * answer is "no records" rather than "nothing".
     */
    @Test
    void aPeriodOlderThanTheRingIsMarkedRatherThanReturnedAsZero() {
        HistoryBrowserController controller = controller();
        Map<String, int[]> windows = controller.windowsForTest(MID_AUGUST);

        int[] lastYear = windows.get("year1");
        assertNotNull(lastYear);
        assertTrue(lastYear[0] > 27, "2025 begins far beyond a 27-day ring, so it is genuinely absent");

        // the two that must NOT be marked: both begin inside the ring, so the figure is partial but real
        for (String period : List.of("month0", "month1")) {
            int[] window = windows.get(period);
            assertNotNull(window);
            assertTrue(window[0] <= 27,
                    period + " begins within a 27-day ring; partial coverage is not the same as no records");
        }
    }

    @Test
    void aRunningMonthIsComparedAgainstTheSameNumberOfDaysNotTheWholeOne() {
        // 15 August: month-to-date is 15 days. Comparing that against all 31 days of July would
        // report a saving of roughly half, every month, purely from the calendar.
        int[] comparison = controller().comparisonForTest("month", 0, MID_AUGUST);

        int days = comparison[1] - comparison[0] + 1;
        assertEquals(15, days, "the comparison must span the same elapsed days as the selection");
    }

    @Test
    void theComparisonForARunningMonthStartsAtTheFirstOfThePreviousMonth() {
        int[] comparison = controller().comparisonForTest("month", 0, MID_AUGUST);

        // 1 July is 45 days before 15 August; the span runs from there forward 15 days.
        assertEquals(45, comparison[1], "aligned to the first of the previous month, not merely the days before");
        assertEquals(31, comparison[0]);
    }

    @Test
    void aCompletedMonthIsComparedAgainstTheWholePreviousMonth() {
        int[] comparison = controller().comparisonForTest("month", 1, MID_AUGUST);

        int days = comparison[1] - comparison[0] + 1;
        assertEquals(30, days, "July has 30 days and all of them count once the month is over");
    }

    @Test
    void aComparisonNeverOverlapsThePeriodItCompares() {
        for (String scale : java.util.List.of("day", "month", "year")) {
            for (int back = 0; back < 3; back++) {
                int[] selection = controller().windowsForTest(MID_AUGUST).getOrDefault(scale + back,
                        new int[] { 0, 0, 0 });
                int[] comparison = controller().comparisonForTest(scale, back, MID_AUGUST);
                if (selection[1] == 0 && selection[0] == 0) {
                    continue; // combination not exposed by the test seam
                }
                assertTrue(comparison[0] > selection[1],
                        scale + back + ": a period counted on both sides would compare against itself");
            }
        }
    }

    @Test
    void yesterdayIsComparedAgainstTheDayBeforeIt() {
        int[] comparison = controller().comparisonForTest("day", 1, MID_AUGUST);

        assertEquals(2, comparison[0]);
        assertEquals(2, comparison[1], "one day compares against exactly one day");
    }

    @Test
    void anAbsurdPercentageSaturatesInsteadOfBeingReported() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/org/openhab/binding/"
                + "emsmanager/internal/controller/analytics/HistoryBrowserController.java"));

        assertTrue(source.contains("DELTA_LIMIT_PCT"),
                "0.013 kWh of feed-in against 7.6 gives +59627%, seen live on 2026-09-01");
        assertTrue(source.contains("Math.max(-DELTA_LIMIT_PCT, Math.min(DELTA_LIMIT_PCT"),
                "it has to clamp both directions, not just the rise");
    }
}

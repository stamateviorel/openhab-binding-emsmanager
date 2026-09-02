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

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Pure per-metric daily-increment rollup. Source items are cumulative counters
 * that reset on a period boundary (the {@code _Day} kWh counters reset at
 * midnight; the {@code _Month} EUR counters reset at month start). This class
 * turns a stream of cumulative readings into correct <em>per-day amounts</em>
 * and keeps a ring buffer of completed days.
 *
 * <p>
 * The day's amount is {@code max(0, lastReading - dayStartBaseline)}:
 * <ul>
 * <li>For a daily-reset counter the baseline is ~0 at the start of the day,
 * so the amount equals the reading.</li>
 * <li>For a monthly counter the baseline is the month-to-date value at the
 * start of the day, so the amount is that day's delta.</li>
 * <li>{@code max(0,…)} clamps the period reset (counter jumps back down).</li>
 * </ul>
 *
 * <p>
 * Crucially the rollover uses the <em>last reading of the previous day</em>
 * (captured before the counter reset on the same tick), not the post-reset
 * value — that was the bug in the original midnight rollover.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class DailyRollup {

    private final int ringDays;
    private final boolean dailyReset;
    private final Deque<Double> ring = new ArrayDeque<>();
    private double dayStartBaseline = Double.NaN; // NaN = not yet initialised
    private double lastReading = Double.NaN;

    /**
     * @param ringDays history horizon
     * @param dailyReset {@code true} if the source counter resets to 0 every day
     *            (the kWh {@code _Day} items) — then the day's amount is simply
     *            the current reading, so a mid-day cold start still reads right.
     *            {@code false} for counters that accumulate across days (the EUR
     *            {@code _Month} items) — then the day's amount is the delta from
     *            the value at the start of the day.
     */
    public DailyRollup(int ringDays, boolean dailyReset) {
        this.ringDays = ringDays;
        this.dailyReset = dailyReset;
    }

    /** Record the current cumulative reading. Call once per tick. */
    public void observe(double current) {
        if (!dailyReset && Double.isNaN(dayStartBaseline)) {
            dayStartBaseline = current; // monthly counter: first reading anchors the (partial) day
        }
        // A monthly counter that has fallen far below its baseline was reset behind our back and
        // has been counting up from 0 since; measuring the day against the old baseline would
        // read 0 until tomorrow. The rollover tick itself is where this happens: the counter's
        // reset and this controller's read of it land on the same tick in either order. Only a
        // drop past half the baseline counts - negative spot hours make cost legitimately dip.
        if (!dailyReset && current < 0.5 * dayStartBaseline) {
            dayStartBaseline = 0.0;
        }
        lastReading = current;
    }

    /**
     * Close the current day and open a new one. Appends the completed day's
     * amount (computed from the last reading <em>before</em> any reset) to the
     * ring and re-baselines to the first reading of the new day.
     *
     * @param firstReadingOfNewDay the counter value now (start of the new day)
     * @return the amount attributed to the day that just ended
     */
    public double rollover(double firstReadingOfNewDay) {
        return rollover(firstReadingOfNewDay, 1);
    }

    /**
     * Close the day that just ended and open a new one.
     * <p>
     * Every reader indexes this ring by position and calls that "days ago", so one entry has to mean
     * one calendar day. A binding that was down across two or more midnights would otherwise append
     * a single entry for the whole absence and shift every figure behind it by the length of the
     * gap - permanently, and with nothing to show that it had happened. Days with no data are
     * recorded as the zero they are.
     *
     * @param daysElapsed calendar days between the last day seen and today; 1 in normal operation
     */
    public double rollover(double firstReadingOfNewDay, int daysElapsed) {
        double amount = dayAmount();
        ring.addLast(amount);
        for (int missed = 1; missed < Math.min(daysElapsed, ringDays); missed++) {
            ring.addLast(0.0);
        }
        while (ring.size() > ringDays) {
            ring.removeFirst();
        }
        dayStartBaseline = dailyReset ? 0.0 : firstReadingOfNewDay;
        lastReading = firstReadingOfNewDay;
        return amount;
    }

    /** Amount accumulated since the start of the current day (the running partial). */
    public double dayAmount() {
        if (Double.isNaN(lastReading)) {
            return 0.0;
        }
        if (dailyReset) {
            // Source resets to 0 daily -> the current reading IS the day's amount.
            return lastReading > 0 ? lastReading : 0.0;
        }
        if (Double.isNaN(dayStartBaseline)) {
            return 0.0;
        }
        double a = lastReading - dayStartBaseline;
        return a > 0 ? a : 0.0;
    }

    /** Sum of the last {@code n} completed days in the ring. */
    public double sumLast(int n) {
        if (n <= 0 || ring.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        int taken = 0;
        Double[] arr = ring.toArray(new Double[0]);
        for (int i = arr.length - 1; i >= 0 && taken < n; i--, taken++) {
            sum += arr[i];
        }
        return sum;
    }

    /** The most recently completed day's amount (the {@code _Yesterday} value). */
    public double yesterday() {
        Double last = ring.peekLast();
        return last == null ? 0.0 : last;
    }

    public int size() {
        return ring.size();
    }

    // --- serialization support ---

    public Deque<Double> ring() {
        return ring;
    }

    public double baseline() {
        return dayStartBaseline;
    }

    public double last() {
        return lastReading;
    }

    public void restore(Collection<Double> values, double baseline, double last) {
        ring.clear();
        ring.addAll(values);
        this.dayStartBaseline = baseline;
        this.lastReading = last;
    }

    /**
     * A single completed day's amount, counting back from the most recent.
     *
     * @param daysAgo 1 is yesterday, 2 the day before, and so on
     * @return the amount, or 0 where the ring does not reach that far back
     */
    public double amountAgo(int daysAgo) {
        if (daysAgo <= 0) {
            return dayAmount();
        }
        Double[] arr = ring.toArray(new Double[0]);
        int index = arr.length - daysAgo;
        return index < 0 || index >= arr.length ? 0.0 : arr[index];
    }

    /**
     * The total over a window of completed days.
     * <p>
     * Both bounds count back from today, and the window includes them: {@code sumRange(1, 7)} is the last seven
     * completed days. Today's running partial is never included - a caller that wants it adds {@link #dayAmount()},
     * which is what makes "this month so far" different from "last month".
     *
     * @param fromDaysAgo the nearer bound, 1 being yesterday
     * @param toDaysAgo the further bound
     * @return the total, or 0 where the window lies outside the ring
     */
    public double sumRange(int fromDaysAgo, int toDaysAgo) {
        if (fromDaysAgo > toDaysAgo || toDaysAgo <= 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int day = Math.max(1, fromDaysAgo); day <= toDaysAgo; day++) {
            sum += amountAgo(day);
        }
        return sum;
    }

    /** How many completed days the ring actually holds, so a caller can say when it is asking beyond its history. */
    public int daysHeld() {
        return ring.size();
    }
}

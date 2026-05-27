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
package org.openhab.binding.emsmanager.internal.core;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Capacity-tariff tracker (e.g. the Belgian capaciteitstarief). Maintains the
 * running average of grid power (signed; − = import) over the current 15-minute slot,
 * tracks the month-to-date peak (most-negative slot average — that's the
 * dimension the supplier bills on), and resets at month rollover.
 *
 * <p>
 * 15-min slots are aligned to :00 / :15 / :30 / :45 — same as the
 * supplier's metering quarters.
 *
 * <p>
 * State is not persisted across bridge re-init in v1; the tracker
 * starts fresh and the MTD peak rebuilds as slots accumulate. JSONDB
 * persistence is a future refinement.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class CapacityTariffTracker {

    public static final long SLOT_MS = 15L * 60L * 1000L;

    private final ZoneId zone;

    // Current slot running stats.
    private long currentSlotStartMs = 0L;
    private double currentSlotSum = 0.0;
    private int currentSlotSamples = 0;

    // Month tracking (reset MTD peak when month changes).
    private int currentMonthNumber = -1;
    private int currentYear = -1;

    /**
     * Most negative slot average observed so far this month (i.e. the worst
     * import peak). Stored as a Watt value with the site convention: − means
     * import. Initialized to 0 (no peak yet).
     */
    private double monthlyPeakW = 0.0;

    public CapacityTariffTracker(ZoneId zone) {
        this.zone = zone;
    }

    /** Snapshot of the tracker state at this instant. */
    public record State(double currentSlotAvgW, double monthlyPeakW, long slotElapsedMs, long slotRemainingMs) {
    }

    public State state(long nowMs) {
        long slotStart = alignToSlotStart(nowMs);
        long elapsed = nowMs - slotStart;
        long remaining = SLOT_MS - elapsed;
        double avg = currentSlotSamples == 0 ? Double.NaN
                : (currentSlotStartMs == slotStart ? currentSlotSum / currentSlotSamples : Double.NaN);
        return new State(avg, monthlyPeakW, elapsed, remaining);
    }

    /** Returns the most-negative-Watt peak (import as positive kW friendly value). */
    public double monthlyPeakW() {
        return monthlyPeakW;
    }

    /**
     * Record a fresh grid-load sample. Handles slot rollover (commits the
     * previous slot's average to the MTD peak) and month rollover (resets
     * MTD peak).
     */
    public void sample(double gridLoadW, long nowMs) {
        if (Double.isNaN(gridLoadW)) {
            return;
        }
        long slotStart = alignToSlotStart(nowMs);

        // Slot rollover — commit previous slot before opening a new one.
        if (currentSlotStartMs != 0L && slotStart != currentSlotStartMs) {
            commitSlot();
            currentSlotStartMs = slotStart;
            currentSlotSum = 0.0;
            currentSlotSamples = 0;
        }
        if (currentSlotStartMs == 0L) {
            currentSlotStartMs = slotStart;
        }

        // Month rollover.
        ZonedDateTime zdt = Instant.ofEpochMilli(nowMs).atZone(zone);
        int month = zdt.getMonthValue();
        int year = zdt.getYear();
        if (month != currentMonthNumber || year != currentYear) {
            currentMonthNumber = month;
            currentYear = year;
            monthlyPeakW = 0.0;
        }

        currentSlotSum += gridLoadW;
        currentSlotSamples += 1;
    }

    private void commitSlot() {
        if (currentSlotSamples == 0) {
            return;
        }
        double slotAvg = currentSlotSum / currentSlotSamples;
        // Most-negative slot avg = highest import — that's what gets billed.
        if (slotAvg < monthlyPeakW) {
            monthlyPeakW = slotAvg;
        }
    }

    /** Align a millisecond timestamp to the start of its 15-minute slot. */
    public static long alignToSlotStart(long nowMs) {
        ZonedDateTime zdt = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault());
        LocalDateTime local = zdt.toLocalDateTime();
        int minute = local.getMinute();
        int slotMinute = (minute / 15) * 15;
        LocalDateTime slotStart = local.withMinute(slotMinute).withSecond(0).withNano(0);
        return slotStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}

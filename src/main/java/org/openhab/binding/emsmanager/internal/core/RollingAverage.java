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

import java.util.ArrayDeque;
import java.util.Deque;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Time-windowed rolling average over the recent grid signal, computed
 * in-memory without depending on a persistence service.
 *
 * <p>
 * Stores (timestamp, value) pairs; drops entries older than the window
 * on every {@link #add(double, long)} call. {@link #average()} returns the
 * arithmetic mean of the surviving entries; if none, returns {@code NaN}.
 *
 * <p>
 * Not thread-safe; one instance per signal, accessed from a single tick
 * thread.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class RollingAverage {

    private final long windowMs;
    private final Deque<long[]> entries = new ArrayDeque<>();
    // entries stores {ts, valueAsBits} pairs as long[2] — we encode double via doubleToRawLongBits.

    public RollingAverage(long windowMs) {
        this.windowMs = Math.max(1L, windowMs);
    }

    public void add(double value, long nowMs) {
        if (Double.isNaN(value)) {
            return;
        }
        entries.addLast(new long[] { nowMs, Double.doubleToRawLongBits(value) });
        long cutoff = nowMs - windowMs;
        while (!entries.isEmpty() && entries.peekFirst()[0] < cutoff) {
            entries.removeFirst();
        }
    }

    public double average() {
        if (entries.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0.0;
        int n = 0;
        for (long[] e : entries) {
            sum += Double.longBitsToDouble(e[1]);
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    public int size() {
        return entries.size();
    }
}

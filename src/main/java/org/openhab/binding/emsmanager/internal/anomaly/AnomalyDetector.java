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
package org.openhab.binding.emsmanager.internal.anomaly;

import java.util.Arrays;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Pure-math anomaly detection via robust z-score (Iglewicz & Hoaglin, 1993).
 *
 * <p>
 * Given a history of recent observations (typically last 4 same-DoW
 * occurrences) and today's value, return whether today is anomalous.
 * Median + MAD-based, so robust to single outlier history points.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class AnomalyDetector {

    private static final double MAD_TO_SIGMA = 0.6745; // erf⁻¹(0.5)·√2
    private static final double DEFAULT_Z_THRESHOLD = 3.5; // Iglewicz–Hoaglin recommended cutoff
    private static final double MAD_FLOOR = 0.05; // protect against div-by-near-zero

    public record Result(boolean anomaly, double zScore, double median, double mad, double delta) {
    }

    /**
     * Detect whether {@code today} is anomalous given {@code history}.
     *
     * @param history recent same-DoW observations (e.g. last 4 Tuesdays' kWh)
     * @param today today's value
     * @param absoluteFloor don't fire if |today − median| ≤ floor (suppresses
     *            noise on near-zero loads)
     * @param zThreshold z-score cutoff (default 3.5)
     */
    public static Result detect(double[] history, double today, double absoluteFloor, double zThreshold) {
        if (history.length == 0) {
            return new Result(false, 0, 0, 0, today);
        }
        double[] sorted = history.clone();
        Arrays.sort(sorted);
        double median = median(sorted);
        double mad = medianAbsoluteDeviation(history, median);
        double delta = today - median;
        double z = MAD_TO_SIGMA * delta / Math.max(mad, MAD_FLOOR);
        boolean anomaly = Math.abs(z) > zThreshold && Math.abs(delta) > absoluteFloor;
        return new Result(anomaly, z, median, mad, delta);
    }

    /** Detect with default thresholds. */
    public static Result detect(double[] history, double today) {
        return detect(history, today, 0.3, DEFAULT_Z_THRESHOLD);
    }

    private static double median(double[] sortedAsc) {
        int n = sortedAsc.length;
        if (n == 0) {
            return 0;
        }
        if (n % 2 == 1) {
            return sortedAsc[n / 2];
        }
        return 0.5 * (sortedAsc[n / 2 - 1] + sortedAsc[n / 2]);
    }

    private static double medianAbsoluteDeviation(double[] data, double median) {
        double[] abs = new double[data.length];
        for (int i = 0; i < data.length; i++) {
            abs[i] = Math.abs(data[i] - median);
        }
        Arrays.sort(abs);
        return median(abs);
    }
}

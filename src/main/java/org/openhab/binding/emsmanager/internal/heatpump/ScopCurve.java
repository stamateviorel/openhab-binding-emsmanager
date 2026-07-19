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
package org.openhab.binding.emsmanager.internal.heatpump;

import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Piecewise-linear SCOP curve indexed by outdoor temperature.
 *
 * <p>
 * Real heat pumps have a COP that falls sharply with outdoor temperature;
 * modelling it as a constant (the default) over-rewards heating in winter and
 * under-rewards summer cooling. Datasheet-derived values give the optimizer
 * realistic cost numbers.
 *
 * <p>
 * CSV format: {@code T1:cop1,T2:cop2,...} with T_n strictly increasing.
 * Below the lowest key → returns the lowest COP (don't extrapolate cold);
 * above the highest → returns the highest. In between → linear interpolation.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ScopCurve {

    private final TreeMap<Double, Double> points = new TreeMap<>();

    public ScopCurve(@Nullable String csv) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        for (String pair : csv.split(",")) {
            int sep = pair.indexOf(':');
            if (sep <= 0) {
                continue;
            }
            try {
                double t = Double.parseDouble(pair.substring(0, sep).trim());
                double cop = Double.parseDouble(pair.substring(sep + 1).trim());
                if (cop > 0) {
                    points.put(t, cop);
                }
            } catch (NumberFormatException ignored) {
                // skip malformed entry
            }
        }
    }

    /** Returns interpolated COP at the given outdoor temperature. NaN if curve is empty. */
    public double copAt(double tempOut) {
        if (points.isEmpty()) {
            return Double.NaN;
        }
        if (Double.isNaN(tempOut)) {
            return Double.NaN;
        }
        Map.Entry<Double, Double> floor = points.floorEntry(tempOut);
        Map.Entry<Double, Double> ceil = points.ceilingEntry(tempOut);
        if (floor == null) {
            return points.firstEntry().getValue(); // below lowest key
        }
        if (ceil == null) {
            return points.lastEntry().getValue(); // above highest key
        }
        if (floor.getKey().equals(ceil.getKey())) {
            return floor.getValue();
        }
        double t1 = floor.getKey(), t2 = ceil.getKey();
        double c1 = floor.getValue(), c2 = ceil.getValue();
        double frac = (tempOut - t1) / (t2 - t1);
        return c1 + frac * (c2 - c1);
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }
}

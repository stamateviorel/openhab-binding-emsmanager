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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Time-aware exponentially-weighted moving average. Used to smooth the grid
 * signal feeding the EV coordinator and energy-mode derivation.
 *
 * <p>
 * alpha = dt / (dt + tau), so brief spikes are damped while persistent
 * trends still propagate within roughly the tau time constant.
 *
 * <p>
 * Thread-safety: not safe; one instance per signal, called from a single
 * tick thread.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class EwmaFilter {

    private final long tauMs;
    private double current = Double.NaN;
    private long lastTs = 0L;

    public EwmaFilter(long tauMs) {
        this.tauMs = Math.max(1L, tauMs);
    }

    /** Returns the new smoothed value (also retrievable via {@link #value()}). */
    public double update(double raw, long nowMs) {
        if (Double.isNaN(raw)) {
            return current;
        }
        if (Double.isNaN(current) || lastTs == 0L) {
            current = raw;
            lastTs = nowMs;
            return current;
        }
        long dt = Math.max(1L, nowMs - lastTs);
        double alpha = (double) dt / (double) (dt + tauMs);
        current = current * (1.0 - alpha) + raw * alpha;
        lastTs = nowMs;
        return current;
    }

    public double value() {
        return current;
    }

    public boolean hasValue() {
        return !Double.isNaN(current);
    }
}

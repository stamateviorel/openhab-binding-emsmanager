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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The site energy-level's price windows, in hours out of a 24 h day-ahead schedule: the
 * {@code cheapestHours} cheapest hours grade "maximum", the {@code cheapHours} cheapest
 * "encouraged", and the {@code expensiveHours} most expensive "restricted" — storm.house's
 * configurable percentile windows (M. Storm's production EMS), which the fixed top-4 windows
 * of the earlier reference were a simplification of.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record LevelWindows(int cheapestHours, int cheapHours, int expensiveHours) {

    /** The reference's original fixed windows: top 4 cheapest / 8 cheap / 4 expensive. */
    public static final LevelWindows DEFAULT = new LevelWindows(4, 8, 4);

    /** Clamp each window to a sane 0..24 and keep cheapHours >= cheapestHours. */
    public LevelWindows sanitized() {
        int cheapest = Math.max(0, Math.min(24, cheapestHours));
        int cheap = Math.max(cheapest, Math.min(24, cheapHours));
        int expensive = Math.max(0, Math.min(24, expensiveHours));
        return new LevelWindows(cheapest, cheap, expensive);
    }

    /**
     * storm.house's seasonal auto-profiles, approximated: its docs anchor the "restricted"
     * (expensive-block) window at max 4 h in winter and 8 h in the transition seasons, and say
     * the cheap windows widen in winter and shrink in summer (heating shifts the demand); the
     * exact in-between values here are this reference's interpolation and a site can always
     * bypass the auto-profile with fixed configured windows.
     *
     * @param month calendar month 1..12
     */
    public static LevelWindows seasonal(int month) {
        int m = Math.floorMod(month - 1, 12) + 1;
        if (m == 11 || m == 12 || m == 1 || m == 2) {
            return new LevelWindows(8, 12, 4); // winter: wide cheap windows, short block
        }
        if (m >= 6 && m <= 8) {
            return new LevelWindows(4, 8, 8); // summer: PV carries the day, price matters less
        }
        return new LevelWindows(6, 10, 8); // transition
    }
}

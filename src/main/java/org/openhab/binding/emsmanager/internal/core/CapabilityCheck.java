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
 * Capability-check helpers. Asset handlers call into these before applying
 * a setpoint request — so safety rules (breaker headroom, min/max amps,
 * cable state) are enforced once, regardless of which controller asked.
 *
 * <p>
 * Provides the breaker constants plus per-phase breaker math and
 * measurement-staleness gating.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class CapabilityCheck {

    /**
     * Main breaker, amps — DEFAULTS. The per-site value is threaded in from the bridge
     * config ({@code mainBreakerAmpsPerPhase}) via the {@code effectiveLimitA} overloads.
     */
    public static final int BREAKER_LIMIT_A = 63;
    public static final int BREAKER_HEADROOM_A = 10;
    public static final int EFFECTIVE_LIMIT_A = BREAKER_LIMIT_A - BREAKER_HEADROOM_A; // 53

    public static final int MIN_CHARGING_CURRENT_A = 6;
    public static final int MAX_CHARGING_CURRENT_A = 32;

    private CapabilityCheck() {
        // not instantiable
    }

    /** Clamp an EV amps setpoint into the legal [MIN, MAX] window. */
    public static int clampAmps(double desired) {
        if (desired < MIN_CHARGING_CURRENT_A) {
            return MIN_CHARGING_CURRENT_A;
        }
        if (desired > MAX_CHARGING_CURRENT_A) {
            return MAX_CHARGING_CURRENT_A;
        }
        return (int) Math.floor(desired);
    }

    /**
     * Returns the max amps this charger may draw so no phase exceeds the
     * effective breaker limit. Other chargers' draw is subtracted out before
     * the headroom is computed.
     */
    public static int breakerHeadroomA(double thisChargerL1, double thisChargerL2, double thisChargerL3, double totalL1,
            double totalL2, double totalL3) {
        return breakerHeadroomA(thisChargerL1, thisChargerL2, thisChargerL3, totalL1, totalL2, totalL3,
                EFFECTIVE_LIMIT_A);
    }

    /** As above, with the site's effective per-phase limit (breaker minus safety headroom) threaded in. */
    public static int breakerHeadroomA(double thisChargerL1, double thisChargerL2, double thisChargerL3, double totalL1,
            double totalL2, double totalL3, double effectiveLimitA) {
        return breakerHeadroomA(thisChargerL1, thisChargerL2, thisChargerL3, totalL1, totalL2, totalL3, effectiveLimitA,
                MAX_CHARGING_CURRENT_A);
    }

    /** As above, additionally capping the result at the site's maximum charging current. */
    public static int breakerHeadroomA(double thisChargerL1, double thisChargerL2, double thisChargerL3, double totalL1,
            double totalL2, double totalL3, double effectiveLimitA, int maxCapA) {
        double h1 = effectiveLimitA - (totalL1 - thisChargerL1);
        double h2 = effectiveLimitA - (totalL2 - thisChargerL2);
        double h3 = effectiveLimitA - (totalL3 - thisChargerL3);
        double headroom = Math.floor(Math.min(h1, Math.min(h2, h3)));
        if (headroom < 0.0) {
            return 0;
        }
        if (headroom > maxCapA) {
            return maxCapA;
        }
        return (int) headroom;
    }
}

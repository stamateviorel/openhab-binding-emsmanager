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
import org.openhab.binding.emsmanager.internal.capability.Evse;

/**
 * Per-tick read of one car (charger) — what controllers need to make
 * decisions. Immutable. Implements the {@link Evse} capability so it can be
 * consumed generically (see {@code EnergyContext#evses()}).
 *
 * <p>
 * Live power is read per car from whichever source is configured: chargers
 * without an internal meter take their kW from an external power item
 * (e.g. a CT-clamp reading, sign-stripped); metered chargers take their W
 * from the charger's own OCPP MeterValues item.
 *
 * <p>
 * {@code ampsL1/L2/L3} come from the per-car current measurements and are
 * the source of truth for breaker-headroom math.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record CarSnapshot(String carKey, // "car1", "car2", "car3", "car4"
        Mode mode, boolean cableConnected, String ocppStatus, // raw OCPP status (Available / Preparing / Charging /
                                                              // SuspendedEVSE / ...)
        double ampsL1, double ampsL2, double ampsL3, double liveDrawW, double currentLimitA,
        boolean paused) implements Evse {

    public enum Mode {
        ECO,
        SNEL,
        OFF,
        UNKNOWN
    }

    // --- Evse capability bridge methods (map record components to the generic contract) ---

    @Override
    public String id() {
        return carKey;
    }

    @Override
    public String modeName() {
        return mode.name();
    }

    @Override
    public String status() {
        return ocppStatus;
    }

    @Override
    public double powerW() {
        return liveDrawW;
    }
}

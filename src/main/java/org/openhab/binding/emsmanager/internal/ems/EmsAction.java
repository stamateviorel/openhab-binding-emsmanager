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
import org.eclipse.jdt.annotation.Nullable;

/**
 * One intended action produced by the {@link EnergyManagementService} for a consumer's item,
 * covering the four consumer profile classes: switch a {@link PowerProfile.Simple} load ON/OFF,
 * set a {@link PowerProfile.Controllable} load to a watt value, command a
 * {@link PowerProfile.ModeControllable} load to one of its discrete modes
 * ({@link #stringValue()} carries the mode, {@link #value()} its index), start a
 * {@link PowerProfile.Batch} load (ONOFF 1.0, start-once), or explicitly {@link Kind#HOLD} —
 * "decided to do nothing this tick" (never actuated; distinct from OFF so a running batch
 * program is never interrupted). The service emits these; a thin adapter (or shadow logger)
 * turns them into item commands — keeping the algorithm pure and testable.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record EmsAction(String itemName, Kind kind, double value, @Nullable String stringValue, String reason) {

    /** Convenience constructor for the numeric kinds (no mode string). */
    public EmsAction(String itemName, Kind kind, double value, String reason) {
        this(itemName, kind, value, null, reason);
    }

    public enum Kind {
        /** value 1.0 = ON, 0.0 = OFF. */
        ONOFF,
        /** value = target power in watts. */
        SET_WATTS,
        /** stringValue = the discrete mode to command; value = its index in the profile's mode list. */
        SET_MODE,
        /** Deliberate no-op this tick (e.g. a batch program waiting for its window, or already running). */
        HOLD
    }
}

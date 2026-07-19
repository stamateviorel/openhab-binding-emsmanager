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
 * A controller's request to change one asset's setpoint. The scheduler
 * collects these from every controller per tick, resolves conflicts by
 * priority, and the surviving requests are written via the target asset's
 * handler.
 *
 * <p>
 * {@code reason} is a short human-readable Dutch phrase used for the
 * "would-have-done" log in shadow mode and for the controller's lastAction
 * channel.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record SetpointRequest(String assetId, Kind kind, double value, int priority, String controllerName,
        String reason) {

    /** What kind of setpoint this is — used by the asset handler to dispatch. */
    public enum Kind {
        /** ON/OFF asset (boiler, aircon group). value 1.0 = on, 0.0 = off. */
        ONOFF,
        /** EV charger current limit, amps. */
        AMPS,
        /** Battery setpoint, watts. Positive = discharge, negative = charge. */
        WATTS_BATTERY,
        /** EV pause toggle. 1.0 = paused, 0.0 = resume. */
        PAUSE,
        /** EV charging start — RemoteStart for a fresh transaction. value 1.0 = start. */
        CHARGE_START
    }
}

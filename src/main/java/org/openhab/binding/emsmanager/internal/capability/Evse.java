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
package org.openhab.binding.emsmanager.internal.capability;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * An EV charging point (read side). The capability view of a charger that
 * controllers can reason about without knowing the OCPP/Modbus item layout
 * behind it. {@code core.CarSnapshot} implements this.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface Evse {

    /** Stable identifier (e.g. {@code car1}). */
    String id();

    /** Charging mode name (e.g. ECO / SNEL / OFF / UNKNOWN). */
    String modeName();

    /** Whether a cable is plugged in. */
    boolean cableConnected();

    /** Raw protocol status (Available / Preparing / Charging / SuspendedEVSE / …). */
    String status();

    double ampsL1();

    double ampsL2();

    double ampsL3();

    /** Live charging power in watts. {@code Double.NaN} when unknown. */
    double powerW();

    /** The current limit currently applied, in amps. */
    double currentLimitA();

    /** Whether the EMS has paused this charger. */
    boolean paused();
}

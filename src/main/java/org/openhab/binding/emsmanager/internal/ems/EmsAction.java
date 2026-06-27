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
 * One intended action produced by the {@link EnergyManagementService} for a consumer's item:
 * either switch it ON/OFF (a {@link PowerProfile.Simple} load) or set it to a watt value
 * (a {@link PowerProfile.Controllable} load). The service emits these; a thin adapter (or
 * shadow logger) turns them into item commands — keeping the algorithm pure and testable.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record EmsAction(String itemName, Kind kind, double value, String reason) {

    public enum Kind {
        /** value 1.0 = ON, 0.0 = OFF. */
        ONOFF,
        /** value = target power in watts. */
        SET_WATTS
    }
}

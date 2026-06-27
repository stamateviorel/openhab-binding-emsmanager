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
 * Describes <em>how</em> an {@link EnergyConsumer} draws power, mirroring Kai Kreuzer's
 * {@code PowerProfile} concept from the core energy-management design (openhab-core #3478):
 * <ul>
 * <li>{@link Simple} — a Switch-like load that only reacts to ON/OFF.</li>
 * <li>{@link Controllable} — a Number-like load that is regulated to a commanded power
 * (in watts) within {@code [minW, maxW]}, e.g. a wallbox or a battery.</li>
 * </ul>
 * The {@code itemName} is the item through which the load is actually controlled.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface PowerProfile {

    /** The item this load is controlled through (commanded ON/OFF or to a watt value). */
    String itemName();

    /** A simple on/off load (Kai's {@code SimplePowerProfile}). */
    record Simple(String itemName) implements PowerProfile {
        @Override
        public String itemName() {
            return itemName;
        }
    }

    /** A load regulated to a commanded watt value in {@code [minW, maxW]} (Kai's {@code ControllablePowerProfile}). */
    record Controllable(String itemName, double minW, double maxW) implements PowerProfile {
        @Override
        public String itemName() {
            return itemName;
        }
    }
}

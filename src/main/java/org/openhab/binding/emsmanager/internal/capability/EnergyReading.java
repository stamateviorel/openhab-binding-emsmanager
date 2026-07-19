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
 * A device that reports instantaneous power, in the binding's <em>canonical
 * sign convention</em>: grid {@code + = export}, battery {@code + = charging},
 * loads {@code +} = consumption. This is one of the capability contracts that
 * decouple controllers from where the value came from (an item today, possibly
 * a Thing channel or computed value tomorrow).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface EnergyReading {

    /** Power in watts (canonical sign). {@code Double.NaN} when unavailable. */
    double watts();

    /** True when {@link #watts()} is a real reading. */
    default boolean available() {
        return !Double.isNaN(watts());
    }

    /** Simple immutable reading. */
    record Of(double watts) implements EnergyReading {
        @Override
        public double watts() {
            return watts;
        }
    }
}

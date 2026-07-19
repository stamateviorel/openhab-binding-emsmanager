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
 * A battery capability: power ({@code + = charging}) plus state of charge and
 * the user's reserve floor. Extends {@link EnergyReading} so it can be treated
 * as a generic power source where that is all that matters.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface Battery extends EnergyReading {

    /** State of charge, percent 0..100. {@code Double.NaN} when unavailable. */
    double soc();

    /** Reserve-target floor, percent 0..100. {@code Double.NaN} when unavailable. */
    double reserveTarget();

    /** True when {@link #soc()} is a real reading. */
    default boolean socAvailable() {
        return !Double.isNaN(soc());
    }

    /** Simple immutable battery reading. */
    record Of(double watts, double soc, double reserveTarget) implements Battery {
        @Override
        public double watts() {
            return watts;
        }

        @Override
        public double soc() {
            return soc;
        }

        @Override
        public double reserveTarget() {
            return reserveTarget;
        }
    }
}

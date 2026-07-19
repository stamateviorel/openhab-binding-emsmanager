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
import org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants;

/**
 * The soft production-shave sticky band, as one value: drop the ECO charging cap to
 * {@code lowCapA} when the smoothed grid imports beyond {@code thresholdW} (a large negative);
 * lift it back to {@code normalCapA} once it recovers above {@code recoveryW}; hold in between.
 * Built from the bridge config and shared by the legacy {@code SoftPeakShavingController} and
 * the engine, so both sides always shave with identical parameters.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record SoftShaveBand(double thresholdW, double recoveryW, int lowCapA, int normalCapA) {
    /** The historical constants, for sites/tests that don't configure the band. */
    public static final SoftShaveBand DEFAULT = new SoftShaveBand(EmsManagerBindingConstants.SOFT_THRESHOLD_W,
            EmsManagerBindingConstants.SOFT_RECOVERY_W, EmsManagerBindingConstants.SOFT_ECO_CAP_A,
            EmsManagerBindingConstants.NORMAL_ECO_CAP_A);
}

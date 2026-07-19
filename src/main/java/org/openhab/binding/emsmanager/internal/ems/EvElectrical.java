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
 * The site's EV-charging electrical parameters, as one value: number of phases, phase voltage
 * and the maximum charging current. Built from the bridge config and shared by the EV
 * coordinator, the charging-plan estimator and the engine, so budget→amps math is correct on
 * single-phase and non-230 V sites too (the historical constants assumed EU 3×230 V).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record EvElectrical(int phases, int phaseVoltage, int maxChargeCurrentA) {
    /** The historical EU 3-phase defaults, for sites/tests that don't configure them. */
    public static final EvElectrical DEFAULT = new EvElectrical(3, 230, 32);
}

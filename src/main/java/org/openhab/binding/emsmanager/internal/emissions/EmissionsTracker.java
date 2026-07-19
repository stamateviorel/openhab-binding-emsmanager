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
package org.openhab.binding.emsmanager.internal.emissions;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Provides the current grid carbon-intensity (gCO₂/kWh) and the avoided
 * emissions per exported kWh.
 *
 * <p>
 * Implementations:
 * <ul>
 * <li>{@link FixedEmissionsProvider} — fixed constants (the default).</li>
 * <li>{@link ElectricityMapsProvider} — live grid mix (e.g. via Electricity Maps).</li>
 * </ul>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface EmissionsTracker {

    /** Grid carbon intensity for imports right now, gCO₂/kWh. NaN if unavailable. */
    double currentGridGramsPerKWh();

    /** Marginal generation displaced by your export right now, gCO₂/kWh. NaN if unavailable. */
    double currentInjectionOffsetGramsPerKWh();

    /** Human-readable provider identifier (for logging + UI). */
    String name();
}

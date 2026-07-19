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
package org.openhab.binding.emsmanager.internal.config;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Thing configuration for {@code emsmanager:device-meter}. Each instance
 * tracks one sub-metered device (boiler, EV charger, lighting circuit, …)
 * by integrating an instantaneous-power item over time.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class DeviceMeterConfig {

    /** Human-friendly label shown in UI ("Boiler", "Auto 1 — Poort", "Verlichting beneden", …). */
    public String name = "Device";

    /** Required: item with current power draw in Watts (or kW if {@code powerInKw=true}). */
    public @Nullable String powerItem;

    /** Set true when the source item reports power in kW instead of W (e.g. some inverter brands). */
    public boolean powerInKw = false;

    /**
     * Optional: item with cumulative delivered energy (kWh). If provided, the handler can compute
     * daily totals from absolute kWh deltas instead of W-integration — more accurate.
     */
    public @Nullable String energyItem;

    /** Optional: device category for UI grouping (e.g. "heating", "lighting", "ev", "other"). */
    public String category = "other";

    /** Optional: hex color for the donut chart wedge / progress bar tint (e.g. "#ff9800"). */
    public String color = "#666666";
}

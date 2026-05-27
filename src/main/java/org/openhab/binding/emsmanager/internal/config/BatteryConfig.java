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
 * Thing configuration for {@code emsmanager:asset:battery}. Three modes:
 * <ul>
 * <li>{@code auto} — EMS computes setpoint, writes via {@code setpointItemName}.
 * Requires the inverter to expose a writable W item.</li>
 * <li>{@code fixed} — user picks a static setpoint; EMS respects, doesn't drive.</li>
 * <li>{@code readonly} — telemetry-only; EMS modulates other loads around the battery.</li>
 * </ul>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class BatteryConfig {

    public String controlMode = "readonly"; // safest default

    /** Item the BatteryAssetHandler writes to in auto mode (signed W, + = discharge). */
    public @Nullable String setpointItemName;

    public double fixedSetpointW = 0.0;
    public int minSetpointW = -3000;
    public int maxSetpointW = 3000;

    /** Mirror of the battery reserve-target item — UI surface only in v1. */
    public int reserveTargetPct = 30;
}

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
 * The kind of energy an {@link EnergyProvider} represents — the three sources Kai Kreuzer
 * named in the core energy-management design (openhab-core #3478): the grid (carries a
 * price), photovoltaic production, and a battery (the controllable one).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum ProviderRole {
    GRID,
    PV,
    BATTERY
}

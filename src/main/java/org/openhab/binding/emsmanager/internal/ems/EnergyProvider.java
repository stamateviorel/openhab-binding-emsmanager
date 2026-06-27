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
import org.eclipse.jdt.annotation.Nullable;

/**
 * A source of energy — grid, PV or battery — mirroring Kai Kreuzer's {@code EnergyProvider}
 * from the core design (openhab-core #3478). The {@link #id()} item shows the current power
 * delivery. A provider is {@link #controllable()} when its power can be commanded (typically
 * a battery), in which case {@link #controlItem()} is the setpoint item and the value is
 * clamped to {@code [minW, maxW]}. The grid provider may additionally point at a
 * {@link #priceItem()} carrying the current price (EUR/kWh), e.g. a {@code Number:EnergyPrice},
 * and a {@link #scheduleItem()} carrying a 24-hour price CSV for cheapest-window planning.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record EnergyProvider(String id, ProviderRole role, boolean controllable, @Nullable String controlItem,
        double minW, double maxW, @Nullable String priceItem,
        @Nullable String scheduleItem) implements EnergyParticipant {
    @Override
    public String id() {
        return id;
    }
}

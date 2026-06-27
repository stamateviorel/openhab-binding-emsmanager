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
 * A controllable load with a {@link PowerProfile}, mirroring Kai Kreuzer's
 * {@code EnergyConsumer} (he mused it might better be called {@code DemandDescription}) from
 * the core design (openhab-core #3478). Optionally carries a demand: {@link #demandKwh()}
 * energy to be delivered by {@link #deadlineHour()} (local hour 0..23, or {@code -1} for no
 * deadline) — the input a planner needs to schedule the load into the cheapest/greenest slots.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record EnergyConsumer(String id, PowerProfile profile, double demandKwh,
        int deadlineHour) implements EnergyParticipant {
    @Override
    public String id() {
        return id;
    }
}

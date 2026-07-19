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
 * A controllable load with a {@link PowerProfile}, mirroring Kai Kreuzer's
 * {@code EnergyConsumer} (he mused it might better be called {@code DemandDescription}) from
 * the core design (openhab-core #3478). Optionally carries a demand: {@link #demandKwh()}
 * energy to be delivered by {@link #deadlineHour()} (local hour 0..23, or {@code -1} for no
 * deadline) — the input a planner needs to schedule the load into the cheapest/greenest slots.
 * {@link #measureItem()} optionally names the load's measured-power item — the autonomy principle
 * from the #3478 discussion: devices act on their own, so the planner trusts measured power over
 * commanded state (running-detection, delivered-energy metering).
 * {@link #readyItem()} optionally names a readiness interlock (storm.house's {@code startklar}):
 * while that Switch is not ON the load is never activated. {@link #priority()} orders consumers
 * for surplus allocation (lower = served first, storm.house's {@code Prioritaet}; default 100) —
 * without it, allocation order would depend on item-registry iteration order.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record EnergyConsumer(String id, PowerProfile profile, double demandKwh, int deadlineHour,
        @Nullable String measureItem, @Nullable String readyItem, int priority) implements EnergyParticipant {

    /** Default priority when none is tagged (lower = served first). */
    public static final int DEFAULT_PRIORITY = 100;

    /** Consumer without a measured-power item. */
    public EnergyConsumer(String id, PowerProfile profile, double demandKwh, int deadlineHour) {
        this(id, profile, demandKwh, deadlineHour, null, null, DEFAULT_PRIORITY);
    }

    /** Consumer with a measured-power item only. */
    public EnergyConsumer(String id, PowerProfile profile, double demandKwh, int deadlineHour,
            @Nullable String measureItem) {
        this(id, profile, demandKwh, deadlineHour, measureItem, null, DEFAULT_PRIORITY);
    }

    @Override
    public String id() {
        return id;
    }
}

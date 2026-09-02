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
package org.openhab.binding.emsmanager.internal.controller.dispatch;

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.openhab.binding.emsmanager.internal.ems.BatteryTouSchedule;

/**
 * Time-of-use battery dispatcher over the {@link BatteryTouSchedule}: charge
 * during the night band (02:00-06:00) unless the battery is near full or
 * tomorrow is sunny, discharge during the evening peak (17:00-21:00), an
 * explicit 0 W once when leaving a window, passive otherwise.
 *
 * <p>
 * Always respects {@code Battery_below_reserve}: never asks for
 * discharge when SoC is at or below the user's reserve floor.
 *
 * <p>
 * <b>Two-mode design:</b> this controller emits
 * setpoint requests <i>identically in every battery control mode</i>.
 * The {@code BatteryAssetHandler} is where the mode gates the actual write:
 * <ul>
 * <li><b>auto (writable)</b>: handler clamps to min/max, dedupes, writes
 * to {@code batterySetpointItemName}. Real-time battery control.</li>
 * <li><b>readonly (read-only)</b>: handler logs the would-be decision and
 * rejects the write. Site continues to read battery state; the
 * dispatcher's plan is visible for review but never affects the
 * inverter. Safe default while no writable setpoint is wired.</li>
 * <li><b>fixed</b>: same as readonly — user's static value rules.</li>
 * </ul>
 *
 * <p>
 * This separation keeps the controller pure and the dispatch decision
 * transparent: the same code runs in production and shadow.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class BatteryTouDispatcher implements Controller {

    public static final String NAME = "battery-tou-dispatcher";

    private final boolean shadowMode;
    private final BatteryTouSchedule schedule = new BatteryTouSchedule();

    public BatteryTouDispatcher(boolean shadowMode) {
        this.shadowMode = shadowMode;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_BATTERY_TOU;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean shadowMode() {
        return shadowMode;
    }

    @Override
    public List<SetpointRequest> evaluate(EnergyContext ctx) {
        ZonedDateTime now = ZonedDateTime.ofInstant(ctx.tickAt(), ZoneId.systemDefault());
        Double w = schedule.setpointW(now, ctx.batteryBelowReserve(), ctx.batterySoC(), ctx.forecastTomorrowKwh());
        if (w == null) {
            return List.of();
        }
        return List.of(new SetpointRequest(ASSET_BATTERY, SetpointRequest.Kind.WATTS_BATTERY, w, priority(), NAME,
                schedule.lastReason()));
    }
}

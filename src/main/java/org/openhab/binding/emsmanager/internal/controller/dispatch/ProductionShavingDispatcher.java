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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * Anti-curtailment dispatch — when the battery is full and solar is high,
 * dump surplus into the boiler before the inverter clips.
 *
 * <p>
 * Conditions for "curtailment risk":
 * <ul>
 * <li>SoC ≥ {@value #SOC_FULL_THRESHOLD_PCT} %</li>
 * <li>Solar production ≥ {@value #HIGH_SOLAR_W} W</li>
 * <li>Boiler is currently OFF</li>
 * <li>No user override on the boiler</li>
 * </ul>
 *
 * <p>
 * Priority 100 — runs after SolarSurplusDispatcher (70) so the surplus
 * controller gets first crack with its cloudiness logic. This controller's
 * job is the rarer end-of-production-window edge case.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ProductionShavingDispatcher implements Controller {

    public static final String NAME = "production-shaving";
    public static final double SOC_FULL_THRESHOLD_PCT = 95.0;
    public static final double HIGH_SOLAR_W = 5000.0;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_PRODUCTION_SHAVING;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean shadowMode() {
        return false;
    }

    @Override
    public List<SetpointRequest> evaluate(EnergyContext ctx) {
        if (ctx.boilerUserOverride()) {
            return List.of();
        }
        if (ctx.boilerOn()) {
            return List.of(); // already absorbing
        }
        if (Double.isNaN(ctx.batterySoC()) || ctx.batterySoC() < SOC_FULL_THRESHOLD_PCT) {
            return List.of();
        }
        if (Double.isNaN(ctx.solarLoadW()) || ctx.solarLoadW() < HIGH_SOLAR_W) {
            return List.of();
        }
        return List.of(new SetpointRequest(ASSET_BOILER, SetpointRequest.Kind.ONOFF, 1.0, priority(), NAME,
                String.format(java.util.Locale.ROOT, "anti-curtailment: SoC %.0f%% + solar %.0fW → boiler on",
                        ctx.batterySoC(), ctx.solarLoadW())));
    }
}

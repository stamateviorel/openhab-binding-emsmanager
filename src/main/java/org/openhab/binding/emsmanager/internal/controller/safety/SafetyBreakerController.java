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
package org.openhab.binding.emsmanager.internal.controller.safety;

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.core.CapabilityCheck;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * Per-car breaker-headroom check: for each cable-connected car, compute
 * the headroom on its worst phase (effective 53 A limit minus the other
 * cars' draw on that phase). If headroom &lt; MIN (6 A), emit a PAUSE
 * request.
 *
 * <p>
 * Priority 10 — runs before everything else. Always-on, can't be
 * disabled. Bridges' master shadow flag still suppresses the actual write.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class SafetyBreakerController implements Controller {

    public static final String NAME = "safety-breaker";

    private final boolean shadowMode;
    private final int effectiveLimitA;

    public SafetyBreakerController(boolean shadowMode) {
        this(shadowMode, CapabilityCheck.EFFECTIVE_LIMIT_A);
    }

    /** @param effectiveLimitA the site's per-phase limit (main breaker minus safety headroom) */
    public SafetyBreakerController(boolean shadowMode, int effectiveLimitA) {
        this.shadowMode = shadowMode;
        this.effectiveLimitA = effectiveLimitA;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_SAFETY_BREAKER;
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
        List<SetpointRequest> out = new ArrayList<>();
        if (!ctx.modbusFresh()) {
            // Fallback when per-phase amps are not trustworthy: cap every charger
            // at MIN. We express that as "force CurrentLimit = 6 A" for each
            // cable-connected car.
            for (CarSnapshot car : ctx.cars().values()) {
                if (!car.cableConnected()) {
                    continue;
                }
                out.add(new SetpointRequest(car.carKey(), SetpointRequest.Kind.AMPS,
                        CapabilityCheck.MIN_CHARGING_CURRENT_A, priority(), NAME, "Modbus stale — fail-safe MIN"));
            }
            return out;
        }

        for (CarSnapshot car : ctx.cars().values()) {
            if (!car.cableConnected()) {
                continue;
            }
            int headroom = CapabilityCheck.breakerHeadroomA(car.ampsL1(), car.ampsL2(), car.ampsL3(), ctx.totalAmpsL1(),
                    ctx.totalAmpsL2(), ctx.totalAmpsL3(), effectiveLimitA);
            if (headroom < CapabilityCheck.MIN_CHARGING_CURRENT_A) {
                out.add(new SetpointRequest(car.carKey(), SetpointRequest.Kind.PAUSE, 1.0, priority(), NAME,
                        "Breaker headroom " + headroom + " A < " + CapabilityCheck.MIN_CHARGING_CURRENT_A + " A"));
            }
        }
        return out;
    }

    /**
     * Helper for the bridge channel — minimum headroom across all
     * cable-connected cars. Returns MAX_CHARGING_CURRENT_A if no cars are
     * plugged.
     */
    public static int minHeadroomA(EnergyContext ctx, int effectiveLimitA, int maxCapA) {
        int min = maxCapA;
        boolean anyPlugged = false;
        for (CarSnapshot car : ctx.cars().values()) {
            if (!car.cableConnected()) {
                continue;
            }
            anyPlugged = true;
            int h = CapabilityCheck.breakerHeadroomA(car.ampsL1(), car.ampsL2(), car.ampsL3(), ctx.totalAmpsL1(),
                    ctx.totalAmpsL2(), ctx.totalAmpsL3(), effectiveLimitA, maxCapA);
            if (h < min) {
                min = h;
            }
        }
        return anyPlugged ? min : maxCapA;
    }
}

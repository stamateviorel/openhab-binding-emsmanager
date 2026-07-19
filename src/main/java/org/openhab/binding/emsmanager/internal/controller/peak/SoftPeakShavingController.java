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
package org.openhab.binding.emsmanager.internal.controller.peak;

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * Soft pre-shave on ECO charging using a sticky band: when grid imports more
 * than 5 kW, cap ECO cars at {@code SOFT_ECO_CAP_A} (8 A); when grid recovers
 * above −3 kW, lift the cap back to {@code NORMAL_ECO_CAP_A} (32 A). In the
 * sticky band between, hold whatever the previous cap was — avoids flapping.
 *
 * <p>
 * Priority 50 — runs after safety / hard-peak / modbus-staleness and
 * the future hard-peak-shaving controller. The cap is published as a
 * read-only bridge channel; the future {@code EvCoordinatorController}
 * will consult it in ECO mode.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class SoftPeakShavingController implements Controller {

    public static final String NAME = "peak-shaving-soft";

    private final boolean shadowMode;
    private final org.openhab.binding.emsmanager.internal.ems.SoftShaveBand band;
    private volatile int currentCapA;

    public SoftPeakShavingController(boolean shadowMode) {
        this(shadowMode, org.openhab.binding.emsmanager.internal.ems.SoftShaveBand.DEFAULT);
    }

    public SoftPeakShavingController(boolean shadowMode,
            org.openhab.binding.emsmanager.internal.ems.SoftShaveBand band) {
        this.shadowMode = shadowMode;
        this.band = band;
        this.currentCapA = band.normalCapA();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_SOFT_PEAK_SHAVING;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean shadowMode() {
        return shadowMode;
    }

    /** Latest cap value the controller has settled on (for bridge channel). */
    public int currentCapA() {
        return currentCapA;
    }

    @Override
    public List<SetpointRequest> evaluate(EnergyContext ctx) {
        double grid = ctx.gridLoadSmoothedW();
        if (Double.isNaN(grid)) {
            return List.of();
        }
        int prevCap = currentCapA;
        int newCap = prevCap;
        if (grid < band.thresholdW()) {
            newCap = band.lowCapA();
        } else if (grid > band.recoveryW()) {
            newCap = band.normalCapA();
        }
        // else: sticky band, hold previous.

        if (newCap == prevCap) {
            return List.of();
        }
        currentCapA = newCap;
        // The cap is a policy parameter for the future EvCoordinator. We emit it
        // as a SetpointRequest with assetId="eco-cap-policy" so the dispatcher
        // (phase 4+) can update the bridge channel; in phase 2 the bridge tick
        // also reads currentCapA() directly to update the channel each tick.
        return List.of(new SetpointRequest("eco-cap-policy", SetpointRequest.Kind.AMPS, newCap, priority(), NAME,
                String.format("Grid %.0fW → ECO cap %dA→%dA", grid, prevCap, newCap)));
    }
}

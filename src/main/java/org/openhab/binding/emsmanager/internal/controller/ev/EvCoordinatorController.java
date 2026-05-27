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
package org.openhab.binding.emsmanager.internal.controller.ev;

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.controller.peak.HardPeakShavingController;
import org.openhab.binding.emsmanager.internal.controller.peak.SoftPeakShavingController;
import org.openhab.binding.emsmanager.internal.core.CapabilityCheck;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * Per-car EV charging coordinator. Implements the ECO budget solver with
 * hysteresis + ramp-limit and auto-RemoteStart logic.
 *
 * <p>
 * Priority 60. Runs AFTER the safety/peak controllers so it can defer
 * to them: if hard peak shaving has engaged any tier, this controller
 * emits nothing for the car (peak shaving owns the throttle for the
 * duration). The SafetyBreakerController separately emits PAUSE on
 * breaker-headroom violation; the asset-handler dedupe naturally absorbs
 * the duplicate if both controllers target the same car at the same time.
 *
 * <p>
 * Per-car state: {@code lastSentAmps[carKey]} — drives hysteresis +
 * ramp-limit decisions. Survives across ticks within this controller
 * instance; lost on bridge re-init (acceptable — next tick re-derives).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class EvCoordinatorController implements Controller {

    public static final String NAME = "ev-coordinator";

    private final boolean shadowMode;
    private final HardPeakShavingController hard;
    private final SoftPeakShavingController soft;
    private final int gridSafetyMarginW;

    /** Last AMPS we emitted, per car key. NaN = never emitted. */
    private final Map<String, Double> lastSentAmps = new HashMap<>();

    public EvCoordinatorController(boolean shadowMode, HardPeakShavingController hard, SoftPeakShavingController soft,
            int gridSafetyMarginW) {
        this.shadowMode = shadowMode;
        this.hard = hard;
        this.soft = soft;
        this.gridSafetyMarginW = gridSafetyMarginW;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_EV_COORDINATOR;
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
        // Pre-compute the shared ECO budget once per tick. handleCar's ECO
        // branch divides this across active ECO cars.
        Double ecoBudgetPerCarW = computeEcoBudgetPerCarW(ctx);

        List<SetpointRequest> out = new ArrayList<>();
        for (CarSnapshot car : ctx.cars().values()) {
            handleCar(ctx, car, ecoBudgetPerCarW, out);
        }
        return out;
    }

    private void handleCar(EnergyContext ctx, CarSnapshot car,
            @org.eclipse.jdt.annotation.Nullable Double ecoBudgetPerCarW, List<SetpointRequest> out) {
        if (!car.cableConnected()) {
            return;
        }

        // Modbus fail-safe — cap at MIN. SafetyBreakerController also handles
        // this; we duplicate so the per-car state matches.
        if (!ctx.modbusFresh()) {
            emitAmps(car, CapabilityCheck.MIN_CHARGING_CURRENT_A, "modbus stale → MIN", out);
            return;
        }

        // Breaker protection — primary hardware safety. Always active, ignores
        // PeakShaving. SafetyBreakerController emits this as PAUSE; we add
        // explicit pause here too so the per-car lastSentAmps memo is correct.
        int headroom = CapabilityCheck.breakerHeadroomA(car.ampsL1(), car.ampsL2(), car.ampsL3(), ctx.totalAmpsL1(),
                ctx.totalAmpsL2(), ctx.totalAmpsL3());
        if (headroom < CapabilityCheck.MIN_CHARGING_CURRENT_A) {
            out.add(new SetpointRequest(car.carKey(), SetpointRequest.Kind.PAUSE, 1.0, priority(), NAME,
                    "breaker headroom " + headroom + "A < " + CapabilityCheck.MIN_CHARGING_CURRENT_A + "A"));
            return;
        }

        // PeakShaving coordination — hard tier active → defer entirely.
        if (hard.level() > 0) {
            return;
        }

        // External-pause respect: if the pause item is ON and we didn't set it
        // recently (matched by dedupe), assume external (user / PeakShaving
        // residual / manual) — don't fight it.
        if (car.paused()) {
            Double ours = lastSentAmps.get(car.carKey() + ".pause");
            if (ours == null || ours < 0.5) {
                return;
            }
        }

        // Mode resolution — anything but SNEL/OFF treated as ECO (matches DSC).
        CarSnapshot.Mode mode = car.mode();
        if (mode == CarSnapshot.Mode.OFF) {
            return;
        }
        if (mode != CarSnapshot.Mode.SNEL) {
            mode = CarSnapshot.Mode.ECO;
        }

        // Auto-RemoteStart when no transaction is open. Behaviour varies by
        // charger: some stay "Available" with the cable in, some report
        // "Preparing", and some briefly enter SuspendedEVSE between transactions.
        // Dedupe in the asset handler prevents spam.
        String status = car.ocppStatus();
        if ("Available".equals(status) || "Preparing".equals(status) || "SuspendedEVSE".equals(status)) {
            out.add(new SetpointRequest(car.carKey(), SetpointRequest.Kind.CHARGE_START, 1.0, priority(), NAME,
                    "auto-RemoteStart (status=" + status + ")"));
        }

        if (mode == CarSnapshot.Mode.SNEL) {
            int desired = Math.min(CapabilityCheck.MAX_CHARGING_CURRENT_A, headroom);
            int ramped = rampLimit(car.carKey(), desired);
            int target = applyHysteresis(car.carKey(), ramped);
            emitAmps(car, target, "SNEL → " + target + "A (headroom=" + headroom + "A)", out);
            // Resume any prior pause we set.
            out.add(new SetpointRequest(car.carKey(), SetpointRequest.Kind.PAUSE, 0.0, priority(), NAME,
                    "SNEL — resume"));
            return;
        }

        // mode == ECO
        int desired = CapabilityCheck.MIN_CHARGING_CURRENT_A;
        String budgetNote;
        if (ecoBudgetPerCarW != null && !Double.isNaN(ecoBudgetPerCarW)) {
            int targetAmps = (int) Math.floor(ecoBudgetPerCarW / PHASES / PHASE_VOLTAGE);
            desired = Math.max(CapabilityCheck.MIN_CHARGING_CURRENT_A, targetAmps);
            budgetNote = "budget=" + Math.round(ecoBudgetPerCarW) + "W → " + desired + "A";
        } else {
            budgetNote = "ECO fallback to MIN (no budget)";
        }
        int ecoCap = soft.currentCapA();
        desired = Math.min(desired, Math.min(headroom, ecoCap));
        int ramped = rampLimit(car.carKey(), desired);
        int target = applyHysteresis(car.carKey(), ramped);
        emitAmps(car, target, "ECO " + budgetNote + " (cap=" + ecoCap + "A, headroom=" + headroom + "A)", out);
        // Resume any prior pause we set.
        out.add(new SetpointRequest(car.carKey(), SetpointRequest.Kind.PAUSE, 0.0, priority(), NAME, "ECO — resume"));
    }

    private void emitAmps(CarSnapshot car, int amps, String reason, List<SetpointRequest> out) {
        lastSentAmps.put(car.carKey(), (double) amps);
        out.add(new SetpointRequest(car.carKey(), SetpointRequest.Kind.AMPS, amps, priority(), NAME, reason));
    }

    /**
     * Slew-rate limited target. Allows immediate drops (safety) but caps
     * ramp-ups at {@link EmsManagerBindingConstants#RAMP_UP_STEP_A} per tick.
     */
    private int rampLimit(String carKey, int desired) {
        Double prev = lastSentAmps.get(carKey);
        if (prev == null) {
            return desired;
        }
        if (desired <= prev) {
            return desired;
        }
        return Math.min(desired, (int) Math.floor(prev) + RAMP_UP_STEP_A);
    }

    /**
     * Suppress noisy 1-A flapping under flickering cloud cover. Holds the
     * previous value if the new one is within {@link EmsManagerBindingConstants#HYSTERESIS_A}.
     * Safety-side drops to MIN always pass through.
     */
    private int applyHysteresis(String carKey, int desired) {
        Double prev = lastSentAmps.get(carKey);
        if (prev == null) {
            return desired;
        }
        if (desired == CapabilityCheck.MIN_CHARGING_CURRENT_A) {
            return desired;
        }
        return Math.abs(desired - prev) < HYSTERESIS_A ? (int) Math.round(prev) : desired;
    }

    /**
     * Shared ECO budget across all active ECO cars. Direct port of DSC's
     * {@code computeEcoBudgetPerCarW}: divide (total ECO draw + grid export −
     * safety margin) evenly by the number of active ECO cars.
     */
    private @org.eclipse.jdt.annotation.Nullable Double computeEcoBudgetPerCarW(EnergyContext ctx) {
        double grid = ctx.gridLoadSmoothedW();
        if (Double.isNaN(grid)) {
            return null;
        }
        int active = 0;
        double totalDrawW = 0.0;
        for (CarSnapshot car : ctx.cars().values()) {
            if (!car.cableConnected()) {
                continue;
            }
            if (car.mode() == CarSnapshot.Mode.SNEL || car.mode() == CarSnapshot.Mode.OFF) {
                continue;
            }
            active += 1;
            totalDrawW += car.liveDrawW();
        }
        if (active == 0) {
            return null;
        }
        // Shared ECO budget across all active cars. When grid is importing
        // (negative) the budget can go below zero — clamp to zero so the caller's
        // ramp logic falls back cleanly to MIN_CHARGING_CURRENT.
        double budget = (totalDrawW + grid - gridSafetyMarginW) / active;
        return Math.max(0.0, budget);
    }
}

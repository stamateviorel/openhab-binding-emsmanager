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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOGGER = LoggerFactory.getLogger(EvCoordinatorController.class);

    /**
     * Auto-RemoteStart backoff. A wedged charger (e.g. a CHARX stuck in
     * "Preparing") Rejects every RemoteStart, so the start-state never clears
     * and a naive rule re-sends every ~15 s tick forever. After this many
     * consecutive futile attempts we stop hammering and drop to a slow retry.
     */
    private static final int CHARGE_START_MAX_ATTEMPTS = 5;

    /** Once backed off, retry RemoteStart only once every N ticks (~5 min). */
    private static final int CHARGE_START_SLOW_RETRY_TICKS = 20;

    private final boolean shadowMode;
    private final HardPeakShavingController hard;
    private final SoftPeakShavingController soft;
    private final int gridSafetyMarginW;

    /** Last AMPS we emitted, per car key. NaN = never emitted. */
    private final Map<String, Double> lastSentAmps = new HashMap<>();

    /**
     * Consecutive auto-RemoteStart attempts since the car last left a
     * start-state. Drives the backoff that stops a wedged charger from being
     * hammered every tick. Reset the instant the car charges (status leaves
     * the start-states) or the cable is unplugged.
     */
    private final Map<String, Integer> chargeStartAttempts = new HashMap<>();

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
            // Cable out — clear any backoff so a fresh plug-in earns a full burst.
            chargeStartAttempts.remove(car.carKey());
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

        // Mode resolution — anything but SNEL/OFF treated as ECO (matches DSC).
        CarSnapshot.Mode mode = car.mode();
        if (mode == CarSnapshot.Mode.OFF) {
            return;
        }
        if (mode != CarSnapshot.Mode.SNEL) {
            mode = CarSnapshot.Mode.ECO;
        }

        // External-pause respect — ECO only. Don't fight a pause we didn't set
        // (capacity-tariff residual / soft-shaving / manual): resuming would flap
        // against whoever set it, since capacity-tariff pauses an ECO car once and
        // then goes silent. SNEL is deliberately EXEMPT: capacity-tariff and
        // soft-shaving never touch SNEL cars and hard-shaving is already handled by
        // the deferral above, so a paused SNEL car here only carries a stale pause
        // and MUST be resumed — otherwise flipping ECO→SNEL leaves the wallbox stuck
        // paused with no controller ever waking it.
        if (mode != CarSnapshot.Mode.SNEL && car.paused()) {
            Double ours = lastSentAmps.get(car.carKey() + ".pause");
            if (ours == null || ours < 0.5) {
                return;
            }
        }

        // Auto-RemoteStart when no transaction is open. Behaviour varies by
        // charger: some stay "Available" with the cable in, some report
        // "Preparing", and some briefly enter SuspendedEVSE between transactions.
        //
        // Backoff: a wedged charger (classic CHARX stuck in "Preparing") Rejects
        // every RemoteStart, so a transaction never opens and a naive rule re-sends
        // every tick forever (thousands of pointless CALLs/day). After
        // CHARGE_START_MAX_ATTEMPTS consecutive futile attempts we declare the car
        // wedged: drop to a slow retry and emit NOTHING else for it (no point
        // re-pushing a current limit to a charger that won't start a transaction).
        //
        // The counter is cleared ONLY by a real charge ("Charging") or a cable
        // unplug (top of handleCar) — never by a transient non-start state. A
        // wedged CHARX flickers Preparing/Available/Finishing/SuspendedEV while
        // stuck; resetting on any of those would let it earn a fresh burst every
        // few seconds and defeat the backoff. A physical replug still self-heals
        // (cable-out clears it), and a successful start re-arms the next session.
        String status = car.ocppStatus();
        boolean startState = "Available".equals(status) || "Preparing".equals(status) || "SuspendedEVSE".equals(status);
        if ("Charging".equals(status)) {
            chargeStartAttempts.remove(car.carKey());
        } else if (startState) {
            int attempts = chargeStartAttempts.getOrDefault(car.carKey(), 0) + 1;
            chargeStartAttempts.put(car.carKey(), attempts);
            LOGGER.debug("ev-coordinator[{}]: auto-RemoteStart attempt {} (status={})", car.carKey(), attempts, status);
            if (attempts <= CHARGE_START_MAX_ATTEMPTS) {
                out.add(new SetpointRequest(car.carKey(), SetpointRequest.Kind.CHARGE_START, 1.0, priority(), NAME,
                        "auto-RemoteStart (status=" + status + ")"));
            } else {
                // Backed off — wedged. Slow retry only; nothing else this tick.
                if ((attempts - CHARGE_START_MAX_ATTEMPTS) % CHARGE_START_SLOW_RETRY_TICKS == 0) {
                    out.add(new SetpointRequest(car.carKey(), SetpointRequest.Kind.CHARGE_START, 1.0, priority(), NAME,
                            "auto-RemoteStart trage retry (status=" + status + ", poging " + attempts + ")"));
                }
                return;
            }
        }
        // Any other status (Finishing/Faulted/Unavailable/…): don't emit, and
        // leave the counter intact so a flickering wedged charger stays backed off.

        if (mode == CarSnapshot.Mode.SNEL) {
            int desired = Math.min(CapabilityCheck.MAX_CHARGING_CURRENT_A, headroom);
            int ramped = rampLimit(car.carKey(), desired);
            int target = applyHysteresis(car.carKey(), ramped);
            // A paused / SuspendedEVSE car needs an explicit resume to wake — say so
            // in the reason so the UI shows "resuming" rather than a silent setpoint.
            boolean resuming = car.paused() || "SuspendedEVSE".equals(car.ocppStatus());
            String note = "SNEL → " + target + "A (headroom=" + headroom + "A)"
                    + (resuming ? " — hervat na pauze" : "");
            emitAmps(car, target, note, out);
            // Always clear any prior/residual pause in SNEL — explicit "charge now".
            out.add(new SetpointRequest(car.carKey(), SetpointRequest.Kind.PAUSE, 0.0, priority(), NAME,
                    resuming ? "SNEL — hervatten na pauze" : "SNEL — resume"));
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

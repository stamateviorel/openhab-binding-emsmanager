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
package org.openhab.binding.emsmanager.internal.controller.capacity;

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * Belgian capacity-tariff controller. Watches the current 15-minute slot's
 * running grid-import average; if the projected end-of-slot average would
 * exceed the month-to-date peak (and is bigger than the configured minimum
 * billable demand floor), preempt by emitting pause requests for ECO cars.
 *
 * <p>
 * This controller is meant to run in shadow mode for a couple of full
 * billing months before going live, so it learns what a "normal peak day"
 * looks like first. It defaults to {@code shadowMode=true}; flip it once the
 * data is convincing.
 *
 * <p>
 * Why ECO cars only (v1): ECO charging is the largest deferrable load on
 * a typical Belgian residential install. Boiler + airco shedding are
 * already covered by {@link
 * org.openhab.binding.emsmanager.internal.controller.peak.HardPeakShavingController}
 * for the harder peak-thermal case. SNEL cars are sacrosanct as ever.
 *
 * <p>
 * Projection used here: the current slot's running average — equivalent
 * to "if everything stays at current draw to end-of-slot, this is what
 * we'll be billed on". Crude but defensible; refinements can
 * weight more-recent samples or factor in scheduled loads later.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class CapacityTariffShavingController implements Controller {

    public static final String NAME = "peak-shaving-capacity";

    private final boolean shadowMode;
    private final int minBillableW;

    /** Last computed status string for the bridge channel. */
    private volatile String lastStatus = "No peak control — insufficient data";

    /**
     * Cars this controller has paused itself. Used to issue an explicit release
     * (PAUSE=0.0) when the peak event passes so an ECO car doesn't stay paused
     * indefinitely — the user shouldn't have to flip to SNEL to wake a car the
     * binding silently parked.
     */
    private final Set<String> pausedByMe = new HashSet<>();

    /**
     * Best-effort tracking-restore: on the first tick after init, any paused
     * ECO car is assumed to be one we paused in a previous bridge lifetime so a
     * mid-event restart doesn't leave it stuck forever.
     */
    private boolean firstEvalAfterInit = true;

    public CapacityTariffShavingController(boolean shadowMode, int minBillableW) {
        this.shadowMode = shadowMode;
        this.minBillableW = minBillableW;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_CAPACITY_TARIFF;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean shadowMode() {
        return shadowMode;
    }

    public String lastStatus() {
        return lastStatus;
    }

    /** Convenience for the bridge channel: projected current-quarter avg. */
    public static double projectedQuarterW(EnergyContext ctx) {
        // v1 projection = running slot average.
        return ctx.currentQuarterAvgW();
    }

    /**
     * Convenience for the bridge channel — true when projected end-of-slot
     * average would set a new monthly peak (or exceed the minimum billable floor).
     */
    public static boolean wouldExceedMonthlyPeak(EnergyContext ctx, int minBillableW) {
        double projected = projectedQuarterW(ctx);
        if (Double.isNaN(projected) || projected >= 0) {
            return false; // exporting or no data → no risk
        }
        // Both numbers are signed (− = import). New peak = MORE negative than current peak.
        double mtdPeak = ctx.monthlyPeakW();
        double threshold = Math.min(mtdPeak, -minBillableW);
        return projected < threshold - CAPACITY_SHED_MARGIN_W;
    }

    /** Instance variant using this controller's configured minimum billable demand. */
    public boolean wouldExceedMonthlyPeak(EnergyContext ctx) {
        return wouldExceedMonthlyPeak(ctx, minBillableW);
    }

    @Override
    public List<SetpointRequest> evaluate(EnergyContext ctx) {
        // Need real data this slot — otherwise no decision.
        if (Double.isNaN(ctx.currentQuarterAvgW())) {
            lastStatus = "Waiting for data (slot in progress)";
            return List.of();
        }

        // Restart-resilience: on the very first eval after init, claim any
        // currently-paused ECO car as "possibly ours" so a bridge restart in the
        // middle of a peak event doesn't strand the car when the event passes.
        // The worst case is a one-off release of a car that was paused for some
        // other reason; hard-shaving re-asserts every tick so its pauses win
        // in dispatch, making the heuristic safe.
        if (firstEvalAfterInit) {
            for (CarSnapshot car : ctx.cars().values()) {
                if (car.cableConnected() && car.mode() == CarSnapshot.Mode.ECO && car.paused()) {
                    pausedByMe.add(car.carKey());
                }
            }
            firstEvalAfterInit = false;
        }

        boolean wouldExceed = wouldExceedMonthlyPeak(ctx, minBillableW);
        double projectedKW = -projectedQuarterW(ctx) / 1000.0;
        double mtdPeakKW = -ctx.monthlyPeakW() / 1000.0;

        if (!wouldExceed) {
            // Peak event passed (or never was) — release any cars we paused so
            // they can resume in ECO. Without this, an ECO car paused during a
            // capacity event would sit there forever waiting for someone to
            // unpause it, and users would assume the charger is broken.
            List<SetpointRequest> releases = new ArrayList<>();
            for (String carKey : pausedByMe) {
                CarSnapshot car = ctx.cars().get(carKey);
                if (car != null && car.mode() == CarSnapshot.Mode.ECO && car.cableConnected() && car.paused()) {
                    releases.add(new SetpointRequest(carKey, SetpointRequest.Kind.PAUSE, 0.0, priority(), NAME,
                            String.format(java.util.Locale.ROOT,
                                    "capacity tariff: piek voorbij — kwartier %.1f kW < maandpiek %.1f kW", projectedKW,
                                    mtdPeakKW)));
                }
            }
            pausedByMe.clear();
            lastStatus = String.format(java.util.Locale.ROOT, "Within budget — quarter %.1f kW, monthly peak %.1f kW",
                    projectedKW, mtdPeakKW);
            return releases;
        }

        // Project says we'd set a new monthly peak. Find ECO cars to pause.
        List<SetpointRequest> out = new ArrayList<>();
        int candidates = 0;
        for (CarSnapshot car : ctx.cars().values()) {
            if (!car.cableConnected()) {
                continue;
            }
            if (car.mode() != CarSnapshot.Mode.ECO) {
                continue;
            }
            if (car.paused()) {
                // Already paused (likely by us from an earlier tick) — keep tracking it
                // so we can issue an explicit release later.
                pausedByMe.add(car.carKey());
                continue;
            }
            candidates++;
            out.add(new SetpointRequest(car.carKey(), SetpointRequest.Kind.PAUSE, 1.0, priority(), NAME,
                    String.format(java.util.Locale.ROOT,
                            "capacity tariff: quarter would reach %.1f kW, monthly peak %.1f kW", projectedKW,
                            mtdPeakKW)));
            pausedByMe.add(car.carKey());
        }
        if (candidates == 0) {
            lastStatus = String.format(java.util.Locale.ROOT, "Above monthly peak (%.1f kW) but no ECO cars to pause",
                    projectedKW);
        } else {
            lastStatus = String.format(java.util.Locale.ROOT,
                    "Pausing ECO cars (%d) — quarter %.1f kW > monthly peak %.1f kW", candidates, projectedKW,
                    mtdPeakKW);
        }
        return out;
    }
}

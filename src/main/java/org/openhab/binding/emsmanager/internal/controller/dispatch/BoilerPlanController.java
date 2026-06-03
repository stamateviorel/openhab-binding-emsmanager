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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.controller.peak.HardPeakShavingController;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * Deadline-aware domestic-hot-water (boiler) planner — the boiler analogue of
 * the EV charging plan.
 *
 * <p>
 * Goal: guarantee a daily DHW energy target reaches the boiler by a "ready by"
 * hour, preferring free solar during the day and topping up the remaining gap
 * at the cheapest spot-price hours overnight. This finally puts the
 * cheapest-hour ranking — otherwise only published advisory by
 * {@link SelfConsumptionOptimizer} — to work on an actual load.
 *
 * <p>
 * Design:
 * <ul>
 * <li>Energy delivered is tracked as {@code ratedKw × on-time} within the
 * rolling window that ends at the next {@code readyByHour}; because the window
 * starts at the previous deadline, daytime solar heating counts toward the
 * next morning's target.</li>
 * <li>Grid top-up only runs OVERNIGHT ({@code 00:00..readyByHour}) — the
 * remaining hours are all inside today's price schedule (no midnight-cross /
 * missing-price problem), and daytime is left to solar
 * ({@link SolarSurplusDispatcher}).</li>
 * <li>Defers to hard peak-shaving and the user override; never fights the
 * breaker.</li>
 * <li>Runs at {@link EmsManagerBindingConstants#PRIO_BOILER_PLAN} (before
 * {@code SolarSurplus}) and exposes {@link #wantsBoilerOn()} so the surplus
 * dispatcher can suppress its "off on import" while the plan is actively
 * topping up — otherwise the boiler's own draw (import) would oscillate the
 * relay.</li>
 * </ul>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class BoilerPlanController implements Controller {

    public static final String NAME = "boiler-plan";

    private final boolean shadowMode;
    private final double dailyTargetKwh;
    private final int readyByHour;
    private final double ratedKw;
    private final HardPeakShavingController hard;

    private volatile boolean wantsBoilerOn = false;
    private volatile String lastStatus = "disabled";

    // Rolling-window accounting state (across ticks; lost on bridge re-init).
    private double deliveredKwh = 0.0;
    private @Nullable Instant lastTick = null;
    private @Nullable LocalDate windowKey = null;

    public BoilerPlanController(boolean shadowMode, double dailyTargetKwh, int readyByHour, double ratedKw,
            HardPeakShavingController hard) {
        this.shadowMode = shadowMode;
        this.dailyTargetKwh = dailyTargetKwh;
        this.readyByHour = Math.max(0, Math.min(23, readyByHour));
        this.ratedKw = ratedKw > 0 ? ratedKw : 3.0;
        this.hard = hard;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_BOILER_PLAN;
    }

    @Override
    public boolean enabled() {
        return dailyTargetKwh > 0;
    }

    @Override
    public boolean shadowMode() {
        return shadowMode;
    }

    /** True when the plan is actively forcing the boiler on for a cheap-hour top-up. */
    public boolean wantsBoilerOn() {
        return wantsBoilerOn;
    }

    public String lastStatus() {
        return lastStatus;
    }

    @Override
    public List<SetpointRequest> evaluate(EnergyContext ctx) {
        if (dailyTargetKwh <= 0) {
            wantsBoilerOn = false;
            lastStatus = "disabled";
            return List.of();
        }

        ZonedDateTime now = ctx.tickAt().atZone(ZoneId.systemDefault());
        int hourNow = now.getHour();

        // Deadline-anchored window = the next occurrence of readyByHour. The window
        // starts at the previous deadline, so yesterday's daytime solar counts.
        LocalDate deadlineDate = hourNow < readyByHour ? now.toLocalDate() : now.toLocalDate().plusDays(1);
        if (windowKey == null || !deadlineDate.equals(windowKey)) {
            windowKey = deadlineDate;
            deliveredKwh = 0.0;
        }

        // Integrate boiler energy as rated × on-time (counts solar + grid heating alike).
        Instant nowI = ctx.tickAt();
        Instant prev = lastTick;
        lastTick = nowI;
        if (prev != null) {
            long ms = Duration.between(prev, nowI).toMillis();
            if (ms > 0 && ms <= 600_000L && ctx.boilerOn()) { // ignore gaps > 10 min (restart)
                deliveredKwh += ratedKw * (ms / 3_600_000.0);
            }
        }

        double gap = dailyTargetKwh - deliveredKwh;

        if (gap <= 0.0) {
            wantsBoilerOn = false;
            lastStatus = String.format(Locale.ROOT, "target met: %.1f/%.1f kWh", deliveredKwh, dailyTargetKwh);
            return List.of();
        }
        if (ctx.boilerUserOverride()) {
            wantsBoilerOn = false;
            lastStatus = "user override active";
            return List.of();
        }
        if (hard.level() > 0) {
            wantsBoilerOn = false;
            lastStatus = "deferring to peak-shaving";
            return List.of();
        }
        // Grid top-up only overnight (00:00..readyByHour); daytime is solar's job.
        if (hourNow >= readyByHour) {
            wantsBoilerOn = false;
            lastStatus = String.format(Locale.ROOT, "daytime — solar-first (%.1f/%.1f kWh)", deliveredKwh,
                    dailyTargetKwh);
            return List.of();
        }

        int hoursRemaining = readyByHour - hourNow;
        int hoursNeeded = Math.max(1, Math.min(hoursRemaining, (int) Math.ceil(gap / ratedKw)));
        boolean[] chosen = cheapestHours(ctx.tariffSchedule24h(), hourNow, readyByHour, hoursNeeded);

        if (chosen[hourNow]) {
            wantsBoilerOn = true;
            lastStatus = String.format(Locale.ROOT, "top-up: gap %.1f kWh, %dh of [%02d-%02d), heating now", gap,
                    hoursNeeded, hourNow, readyByHour);
            return List.of(new SetpointRequest(ASSET_BOILER, SetpointRequest.Kind.ONOFF, 1.0, priority(), NAME,
                    "🔆 DHW cheap-hour top-up: " + lastStatus));
        }
        wantsBoilerOn = false;
        lastStatus = String.format(Locale.ROOT, "top-up: gap %.1f kWh, waiting for a cheaper hour before %02d:00", gap,
                readyByHour);
        return List.of();
    }

    /**
     * Pick the cheapest {@code n} hours in {@code [from, to)}. With no price
     * schedule (flat tariff / data missing) fall back to the latest n hours
     * before the deadline (freshest water; on a flat tariff the hour is
     * cost-neutral). Ties prefer later hours.
     */
    private static boolean[] cheapestHours(double[] schedule, int from, int to, int n) {
        boolean[] pick = new boolean[24];
        boolean havePrices = schedule.length >= 24;
        List<Integer> hours = new ArrayList<>();
        for (int h = from; h < to; h++) {
            hours.add(h);
        }
        hours.sort((a, b) -> {
            if (havePrices) {
                int c = Double.compare(schedule[a], schedule[b]);
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(b, a); // tie / flat → prefer later hour (closer to deadline)
        });
        for (int i = 0; i < n && i < hours.size(); i++) {
            pick[hours.get(i)] = true;
        }
        return pick;
    }
}

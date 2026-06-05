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
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.State;

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
 * <li>Delivered energy is measured from the configured boiler power item
 * (rating-independent), falling back to {@code ratedKw × on-time} when no power
 * item is set. Because the window starts at the previous deadline, daytime
 * solar heating counts toward the next morning's target.</li>
 * <li>Delivered + window are persisted to items and restored on (re)init, so a
 * restart mid-window resumes instead of panic-heating from zero.</li>
 * <li>Grid top-up only runs OVERNIGHT ({@code 00:00..readyByHour}); daytime is
 * left to solar ({@link SolarSurplusDispatcher}).</li>
 * <li>Defers to hard peak-shaving and the user override; never fights the
 * breaker. Runs before {@code SolarSurplus} and exposes {@link #wantsBoilerOn()}
 * so the surplus dispatcher won't oscillate the relay during a top-up.</li>
 * </ul>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class BoilerPlanController implements Controller {

    public static final String NAME = "boiler-plan";

    // Durable accounting + status items (persisted/restored across re-init).
    private static final String ITEM_DELIVERED = "EMS_BoilerPlan_Delivered_kWh";
    private static final String ITEM_WINDOW = "EMS_BoilerPlan_Window";
    private static final String ITEM_STATUS = "EMS_BoilerPlan_Status";

    private final boolean shadowMode;
    private final double dailyTargetKwh;
    private final int readyByHour;
    private final double ratedKw;
    private final HardPeakShavingController hard;
    private final @Nullable EventPublisher eventPublisher;
    private final @Nullable ItemRegistry itemRegistry;
    private final String boilerPowerItem;

    private volatile boolean wantsBoilerOn = false;
    private volatile String lastStatus = "disabled";

    private double deliveredKwh = 0.0;
    private @Nullable Instant lastTick = null;
    private @Nullable LocalDate windowKey = null;
    private boolean restoreApplied = false;

    public BoilerPlanController(boolean shadowMode, double dailyTargetKwh, int readyByHour, double ratedKw,
            HardPeakShavingController hard, @Nullable EventPublisher eventPublisher,
            @Nullable ItemRegistry itemRegistry, @Nullable String boilerPowerItem) {
        this.shadowMode = shadowMode;
        this.dailyTargetKwh = dailyTargetKwh;
        this.readyByHour = Math.max(0, Math.min(23, readyByHour));
        this.ratedKw = ratedKw > 0 ? ratedKw : 3.0;
        this.hard = hard;
        this.eventPublisher = eventPublisher;
        this.itemRegistry = itemRegistry;
        this.boilerPowerItem = boilerPowerItem == null ? "" : boilerPowerItem;
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

    /** Energy credited to the current target window, in kWh (exposed for tests). */
    double deliveredKwh() {
        return deliveredKwh;
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
        LocalDate deadlineDate = hourNow < readyByHour ? now.toLocalDate() : now.toLocalDate().plusDays(1);

        // Restore durable accounting once, validated against the current window, so a
        // restart mid-window resumes instead of panic-heating from a zeroed counter.
        if (!restoreApplied) {
            restoreApplied = true;
            String savedWindow = readString(ITEM_WINDOW);
            double savedKwh = readNumber(ITEM_DELIVERED);
            if (savedWindow != null && savedWindow.equals(deadlineDate.toString()) && !Double.isNaN(savedKwh)) {
                deliveredKwh = Math.max(0.0, savedKwh);
                windowKey = deadlineDate;
            }
        }

        if (windowKey == null || !deadlineDate.equals(windowKey)) {
            windowKey = deadlineDate;
            deliveredKwh = 0.0;
        }

        // Integrate delivered energy from the actual boiler meter (rating-independent);
        // fall back to rated × on-time when no power item is configured.
        Instant prev = lastTick;
        Instant nowI = ctx.tickAt();
        lastTick = nowI;
        if (prev != null) {
            long ms = Duration.between(prev, nowI).toMillis();
            if (ms > 0 && ms <= 600_000L) {
                // boilerWatts is in W — convert to kW before integrating over hours → kWh.
                deliveredKwh += (boilerWatts(ctx) / 1000.0) * (ms / 3_600_000.0);
            }
        }

        List<SetpointRequest> out = decide(ctx, hourNow);
        publish(deadlineDate);
        return out;
    }

    private List<SetpointRequest> decide(EnergyContext ctx, int hourNow) {
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

    /** Boiler draw in W: the metered value (preferred) or rated power while on. */
    private double boilerWatts(EnergyContext ctx) {
        if (!boilerPowerItem.isBlank()) {
            double w = readNumber(boilerPowerItem);
            if (!Double.isNaN(w)) {
                return Math.abs(w);
            }
        }
        return ctx.boilerOn() ? ratedKw * 1000.0 : 0.0;
    }

    private void publish(LocalDate deadlineDate) {
        EventPublisher ep = eventPublisher;
        if (ep == null) {
            return;
        }
        ep.post(ItemEventFactory.createStateEvent(ITEM_DELIVERED,
                new DecimalType(Math.round(deliveredKwh * 100.0) / 100.0), null));
        ep.post(ItemEventFactory.createStateEvent(ITEM_WINDOW, new StringType(deadlineDate.toString()), null));
        ep.post(ItemEventFactory.createStateEvent(ITEM_STATUS, new StringType(lastStatus), null));
    }

    private double readNumber(String itemName) {
        State s = stateOf(itemName);
        if (s instanceof DecimalType dt) {
            return dt.doubleValue();
        }
        if (s instanceof QuantityType<?> q) {
            return q.doubleValue();
        }
        return Double.NaN;
    }

    private @Nullable String readString(String itemName) {
        State s = stateOf(itemName);
        return s instanceof StringType ? s.toString() : null;
    }

    private @Nullable State stateOf(String itemName) {
        ItemRegistry reg = itemRegistry;
        if (reg == null || itemName.isBlank()) {
            return null;
        }
        try {
            return reg.getItem(itemName).getState();
        } catch (ItemNotFoundException e) {
            return null;
        }
    }

    /**
     * Pick the cheapest {@code n} hours in {@code [from, to)}. With no price
     * schedule (flat tariff / data missing) fall back to the latest n hours
     * before the deadline. Ties prefer later hours.
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
            return Integer.compare(b, a);
        });
        for (int i = 0; i < n && i < hours.size(); i++) {
            pick[hours.get(i)] = true;
        }
        return pick;
    }
}

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
import java.util.PriorityQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * Pure-observer 24-hour optimizer. Looks at the tariff schedule + solar
 * forecast and identifies the cheapest hours (best for battery charging from
 * grid or scheduled EV charging) and most expensive hours (best for
 * discharging the battery).
 *
 * <p>
 * This controller emits NO setpoint requests — it only publishes a plan to
 * the bridge channels. The actual dispatch lives in
 * {@link BatteryTouDispatcher} (battery) and {@code EvCoordinatorController}
 * (cars). Future iterations can have those controllers consult this plan
 * instead of hardcoded windows.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class SelfConsumptionOptimizer implements Controller {

    public static final String NAME = "self-consumption-optimizer";
    public static final int N_CHARGE_HOURS = 4;
    public static final int N_DISCHARGE_HOURS = 4;

    private volatile String lastPlanCsv = "";
    private volatile int nextChargeHour = -1;
    private volatile int nextDischargeHour = -1;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_SELF_CONSUMPTION;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean shadowMode() {
        return false; // pure observer
    }

    public String planCsv() {
        return lastPlanCsv;
    }

    public int nextChargeHour() {
        return nextChargeHour;
    }

    public int nextDischargeHour() {
        return nextDischargeHour;
    }

    @Override
    public List<SetpointRequest> evaluate(EnergyContext ctx) {
        double[] schedule = ctx.tariffSchedule24h();
        if (schedule.length < 24) {
            lastPlanCsv = "";
            nextChargeHour = -1;
            nextDischargeHour = -1;
            return List.of();
        }

        // Pick N cheapest + N most expensive hours of today (00-23 local).
        // Use indexed priority queues — small N, small data, plenty fast.
        PriorityQueue<int[]> cheap = new PriorityQueue<>((a, b) -> Double.compare(schedule[b[0]], schedule[a[0]]));
        PriorityQueue<int[]> expensive = new PriorityQueue<>((a, b) -> Double.compare(schedule[a[0]], schedule[b[0]]));
        for (int h = 0; h < 24; h++) {
            cheap.offer(new int[] { h });
            expensive.offer(new int[] { h });
            if (cheap.size() > N_CHARGE_HOURS) {
                cheap.poll();
            }
            if (expensive.size() > N_DISCHARGE_HOURS) {
                expensive.poll();
            }
        }

        boolean[] isCharge = new boolean[24];
        boolean[] isDischarge = new boolean[24];
        cheap.forEach(e -> isCharge[e[0]] = true);
        expensive.forEach(e -> isDischarge[e[0]] = true);

        // Avoid the (unlikely-but-possible) overlap where the same hour is in both
        // sets — prefer "discharge" since it's the higher-impact decision.
        for (int h = 0; h < 24; h++) {
            if (isDischarge[h] && isCharge[h]) {
                isCharge[h] = false;
            }
        }

        // Build plan CSV: 24 chars, each '−' / 'c' / 'd' / '·' to make the
        // schedule scannable by humans + widgets.
        StringBuilder sb = new StringBuilder(24);
        for (int h = 0; h < 24; h++) {
            sb.append(isCharge[h] ? 'c' : isDischarge[h] ? 'd' : '.');
        }
        lastPlanCsv = sb.toString();

        // Next-action lookup from the current local hour.
        int hourNow = java.time.ZonedDateTime.ofInstant(ctx.tickAt(), java.time.ZoneId.systemDefault()).getHour();
        nextChargeHour = nextHourAfter(hourNow, isCharge);
        nextDischargeHour = nextHourAfter(hourNow, isDischarge);

        return List.of();
    }

    /** Find the earliest hour at-or-after {@code hourNow} where {@code mask[hour]==true}, or -1. */
    private static int nextHourAfter(int hourNow, boolean[] mask) {
        return IntStream.range(0, 24).map(i -> (hourNow + i) % 24).filter(h -> mask[h]).findFirst().orElse(-1);
    }

    /** For debug: return the price/hour breakdown as a one-liner. */
    public static String formatSchedule(double[] schedule) {
        if (schedule.length != 24) {
            return "(no schedule)";
        }
        return IntStream.range(0, 24).mapToObj(h -> String.format("%02d:%.3f", h, schedule[h]))
                .collect(Collectors.joining(" "));
    }
}

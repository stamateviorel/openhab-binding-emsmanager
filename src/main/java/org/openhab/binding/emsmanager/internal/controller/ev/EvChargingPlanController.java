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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EV charging plan controller (observer).
 *
 * <p>
 * Reads per-car plan inputs (target kWh, departure time, strategy,
 * enabled) and integrates live car power into a "session-since-plan-start"
 * kWh accumulator. Publishes derived outputs so the UI can render a status
 * card per car and prompt the user to switch to SNEL when at risk.
 *
 * <p>
 * Pure observer — emits no SetpointRequests. The user reads
 * {@code Plan_Status} / {@code Plan_Feasible} in the UI and decides whether
 * to flip the EVSE mode item to SNEL. Optional auto-override is a future phase.
 *
 * <p>
 * Strategies:
 * <ul>
 * <li><b>now</b> — always charge at full rate (user already on SNEL).
 * Projected cost uses current tariff price × required kWh.
 * <li><b>cheapest</b> — pick the cheapest hours that satisfy required.
 * Projected cost averages those slots. Needs tariffSchedule24h.
 * <li><b>solar-first</b> — relies on solar; cost = 0 if feasible by
 * solar forecast, otherwise grid cost for the shortfall.
 * </ul>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class EvChargingPlanController implements Controller {

    private static final Logger LOGGER = LoggerFactory.getLogger(EvChargingPlanController.class);
    private static final long FAILURE_LOG_INTERVAL_MS = 60 * 60 * 1000L;

    private final Map<String, Long> lastFailureLogMs = new java.util.concurrent.ConcurrentHashMap<>();

    public static final String NAME = "ev-charging-plan";

    private static final double ECO_REALISTIC_KW_PER_CAR = 3.0; // realistic solar-only sustained kW per car
    private final double gridRealisticKwPerCar; // maxA × phases × phaseVoltage / 1000

    /** Reproduces the {@code EVSE%d_Plan_*} item names of the binding constants. */
    public static final String DEFAULT_PLAN_ITEM_PREFIX_PATTERN = "EVSE%d_Plan_";

    private final EventPublisher eventPublisher;
    private final ItemRegistry itemRegistry;
    private final String planItemPrefixPattern;

    // Per-car runtime state. Survives across ticks; reset on plan-disable or cable-disconnect.
    /** Hours the plan means to charge, indexed from today 00:00 over two days. */
    static final int PLAN_HOURS = 48;
    /** The rate a charging window is planned at, kW - single-phase 32 A, a fair average of the fleet. */
    static final double WINDOW_KW = 7.0;

    private record SessionState(double kwhAccumulated, double fromGridKwh, long lastTickMs, long planStartedMs,
            boolean cableSeen) {
    }

    private final Map<String, SessionState> sessions = new HashMap<>();

    public EvChargingPlanController(EventPublisher eventPublisher, ItemRegistry itemRegistry) {
        this(eventPublisher, itemRegistry, org.openhab.binding.emsmanager.internal.ems.EvElectrical.DEFAULT);
    }

    public EvChargingPlanController(EventPublisher eventPublisher, ItemRegistry itemRegistry,
            org.openhab.binding.emsmanager.internal.ems.EvElectrical elec) {
        this(eventPublisher, itemRegistry, elec, DEFAULT_PLAN_ITEM_PREFIX_PATTERN);
    }

    /**
     * @param carPlanItemPrefixPattern {@code String.format} pattern with one {@code %d} (the car
     *            number) that prefixes every per-car plan item, e.g. {@code EVSE%d_Plan_}
     */
    public EvChargingPlanController(EventPublisher eventPublisher, ItemRegistry itemRegistry,
            org.openhab.binding.emsmanager.internal.ems.EvElectrical elec, String carPlanItemPrefixPattern) {
        this.eventPublisher = eventPublisher;
        this.itemRegistry = itemRegistry;
        this.gridRealisticKwPerCar = elec.maxChargeCurrentA() * elec.phases() * elec.phaseVoltage() / 1000.0;
        this.planItemPrefixPattern = carPlanItemPrefixPattern.isBlank() ? DEFAULT_PLAN_ITEM_PREFIX_PATTERN
                : carPlanItemPrefixPattern;
    }

    private String planItem(int n, String suffix) {
        return String.format(planItemPrefixPattern, n) + suffix;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_EV_CHARGING_PLAN;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean shadowMode() {
        return false; // observer — always safe to run
    }

    /** At most one line per car per hour, so a persistent failure is visible but not a flood. */
    private void reportCarFailure(String carKey, Throwable t, long nowMs) {
        Long last = lastFailureLogMs.get(carKey);
        if (last != null && (nowMs - last) < FAILURE_LOG_INTERVAL_MS) {
            return;
        }
        lastFailureLogMs.put(carKey, nowMs);
        LOGGER.warn("EvChargingPlan[{}]: planning failed, skipping this car: {}", carKey, t.toString());
    }

    @Override
    public List<SetpointRequest> evaluate(EnergyContext ctx) {
        long nowMs = System.currentTimeMillis();
        for (CarSnapshot car : ctx.cars().values()) {
            try {
                evaluateCar(car, ctx, nowMs);
            } catch (Throwable t) {
                // One car must not break the others, but swallowing this outright meant a car that
                // failed on every tick failed invisibly for as long as it lasted. Rate-limited
                // because this runs every few seconds.
                reportCarFailure(car.carKey(), t, nowMs);
            }
        }
        return List.of();
    }

    private void evaluateCar(CarSnapshot car, EnergyContext ctx, long nowMs) {
        int n = Integer.parseInt(car.carKey().substring(3));

        boolean enabledPlan = readSwitch(planItem(n, "Enabled"));
        if (!enabledPlan) {
            sessions.remove(car.carKey());
            publishStatus(n, "Plan off", true, 0.0, 0.0, 0.0);
            post(planItem(n, "Hours"), new StringType(".".repeat(PLAN_HOURS)));
            return;
        }

        double targetKwh = readNumber(planItem(n, "Target_kWh"));
        Instant departure = readInstant(planItem(n, "Departure_At"));
        String strategy = readString(planItem(n, "Strategy"), "now");

        if (Double.isNaN(targetKwh) || targetKwh <= 0.0) {
            publishStatus(n, "No target set", true, 0.0, 0.0, 0.0);
            return;
        }
        if (departure == null) {
            publishStatus(n, "No departure time set", true, targetKwh, 0.0, 0.0);
            return;
        }

        // Maintain session accumulator. Reset on cable disconnect (plan implicitly carries over the cable cycle).
        SessionState s = sessions.get(car.carKey());
        if (s == null) {
            s = new SessionState(0.0, 0.0, nowMs, nowMs, car.cableConnected());
        }
        // Cable cycle: when cable disconnects + later reconnects, reset accumulator.
        if (s.cableSeen && !car.cableConnected()) {
            s = new SessionState(0.0, 0.0, nowMs, nowMs, false);
        } else if (!s.cableSeen && car.cableConnected()) {
            s = new SessionState(0.0, 0.0, nowMs, nowMs, true);
        }

        // Integrate kW × dt → kWh while plugged & drawing.
        long dtMs = nowMs - s.lastTickMs;
        double newKwh = s.kwhAccumulated;
        double fromGrid = s.fromGridKwh;
        if (car.cableConnected() && dtMs > 0 && dtMs < 60_000L && !Double.isNaN(car.liveDrawW())) {
            double drawKw = Math.abs(car.liveDrawW()) / 1000.0;
            double hours = dtMs / 3_600_000.0;
            newKwh += drawKw * hours;
            // What the grid was supplying at the same moment is the part of this draw that was not
            // sun: an approximation, but the one the dashboard can honestly show as "from grid".
            double importKw = Double.isNaN(ctx.gridLoadRawW()) ? 0.0 : Math.max(0.0, -ctx.gridLoadRawW()) / 1000.0;
            fromGrid += Math.min(drawKw, importKw) * hours;
        }
        s = new SessionState(newKwh, fromGrid, nowMs, s.planStartedMs, car.cableConnected());
        sessions.put(car.carKey(), s);
        post(planItem(n, "FromGrid_kWh"), new DecimalType(round2(fromGrid)));

        double required = Math.max(0.0, targetKwh - newKwh);
        double hoursRem = Duration.between(Instant.ofEpochMilli(nowMs), departure).toMillis() / 3_600_000.0;
        if (hoursRem <= 0.0) {
            // Departure passed — keep showing but mark.
            if (required <= 0.01) {
                publishStatus(n, "✅ Target reached", true, 0.0, hoursRem, 0.0);
            } else {
                publishStatus(n, String.format("⚠️ Departure passed — %.1f kWh short", required), false, required,
                        hoursRem, required * Math.max(0.0, ctx.tariffPriceNowEurPerKWh()));
            }
            return;
        }

        if (required <= 0.01) {
            publishStatus(n, String.format("✅ Target reached (in %.1f h)", hoursRem), true, 0.0, hoursRem, 0.0);
            return;
        }

        // Feasibility check:
        // ECO-realistic (≈3 kW/car sustained) can deliver (hoursRem × 3 kW). If required exceeds that
        // and we're solar-first → infeasible without grid. Strategy "now" assumes grid full-rate.
        double requiredAvgKw = required / Math.max(0.1, hoursRem);
        boolean feasibleEco = requiredAvgKw <= ECO_REALISTIC_KW_PER_CAR;
        boolean feasibleGrid = requiredAvgKw <= gridRealisticKwPerCar;

        double tariffNow = ctx.tariffPriceNowEurPerKWh();
        if (Double.isNaN(tariffNow)) {
            tariffNow = 0.0;
        }

        double projectedCost = 0.0;
        String status;
        boolean feasible;
        int hourNow = ZonedDateTime.ofInstant(ctx.tickAt(), ZoneId.systemDefault()).getHour();
        char[] windows = new char[PLAN_HOURS];
        java.util.Arrays.fill(windows, '.');

        switch (strategy) {
            case "cheapest":
                projectedCost = cheapestCostEstimate(ctx.tariffSchedule24h(), hourNow, hoursRem, required, tariffNow);
                for (int offset : cheapestHours(ctx.tariffSchedule24h(), hourNow, hoursRem, required)) {
                    mark(windows, hourNow + offset, 'g');
                }
                feasible = feasibleGrid;
                if (!feasibleGrid) {
                    status = String.format("⚠️ Not achievable: %.1f kWh in %.1f h (>22 kW/car)", required, hoursRem);
                } else if (!feasibleEco) {
                    status = String.format("🔌 Grid needed: %.1f kWh in %.1f h — ~€%.2f", required, hoursRem,
                            projectedCost);
                } else {
                    status = String.format("☀️ Cheap charging: %.1f kWh — ~€%.2f", required, projectedCost);
                }
                break;
            case "solar-first":
                projectedCost = feasibleEco ? 0.0 : (required - (hoursRem * ECO_REALISTIC_KW_PER_CAR)) * tariffNow;
                feasible = feasibleGrid;
                if (!feasibleGrid) {
                    status = String.format("⚠️ Not achievable: %.1f kWh in %.1f h", required, hoursRem);
                } else if (!feasibleEco) {
                    status = String.format("⚠️ Not enough sun: %.1f kWh in %.1f h — switch to SNEL?", required,
                            hoursRem);
                } else {
                    status = String.format("☀️ Solar-first: %.1f kWh needed in %.1f h", required, hoursRem);
                }
                break;
            case "now":
            default:
                projectedCost = required * tariffNow;
                for (int offset = 0; offset < Math.min(hoursRem, Math.ceil(required / WINDOW_KW)); offset++) {
                    mark(windows, hourNow + offset, 'g');
                }
                feasible = feasibleGrid;
                if (!feasibleGrid) {
                    status = String.format("⚠️ Not achievable: %.1f kWh in %.1f h", required, hoursRem);
                } else {
                    status = String.format("⚡ Full speed: %.1f kWh in %.1f h — ~€%.2f", required, hoursRem,
                            projectedCost);
                }
                break;
        }

        publishStatus(n, status, feasible, required, hoursRem, projectedCost);
        post(planItem(n, "Hours"), new StringType(new String(windows)));
    }

    private static void mark(char[] windows, int hourFromTodayMidnight, char what) {
        if (hourFromTodayMidnight >= 0 && hourFromTodayMidnight < windows.length) {
            windows[hourFromTodayMidnight] = what;
        }
    }

    /**
     * Offsets from now of the cheapest hours that cover the need at {@link #WINDOW_KW}, in the
     * order they come. The same hours {@link #cheapestCostEstimate} prices. Visible for testing.
     */
    static int[] cheapestHours(double @Nullable [] sched, int hourNow, double hoursRem, double requiredKwh) {
        if (sched == null || sched.length == 0) {
            return new int[0];
        }
        int hoursAvail = Math.min((int) Math.floor(hoursRem), sched.length);
        if (hoursAvail <= 0 || requiredKwh <= 0) {
            return new int[0];
        }
        Integer[] offsets = new Integer[hoursAvail];
        for (int i = 0; i < hoursAvail; i++) {
            offsets[i] = i;
        }
        java.util.Arrays.sort(offsets,
                (a, b) -> Double.compare(sched[(hourNow + a) % sched.length], sched[(hourNow + b) % sched.length]));
        int hoursNeeded = Math.min((int) Math.ceil(requiredKwh / WINDOW_KW), hoursAvail);
        int[] chosen = new int[hoursNeeded];
        for (int i = 0; i < hoursNeeded; i++) {
            chosen[i] = offsets[i];
        }
        java.util.Arrays.sort(chosen);
        return chosen;
    }

    /**
     * Approximate the cost when the user picks "cheapest" — pick the N cheapest
     * hours between now and departure that satisfy required at 7 kW
     * (single-phase 32 A is a reasonable charge-window-rate estimate).
     * {@code sched} is indexed by hour of today; hours past midnight reuse
     * today's price for the same hour because tomorrow's schedule is not
     * known here, and the window is capped at 24 h. Visible for testing.
     */
    static double cheapestCostEstimate(double @Nullable [] sched, int hourNow, double hoursRem, double requiredKwh,
            double fallbackPrice) {
        if (sched == null || sched.length == 0) {
            return requiredKwh * fallbackPrice;
        }
        int hoursAvail = Math.min((int) Math.floor(hoursRem), sched.length);
        if (hoursAvail <= 0) {
            return requiredKwh * fallbackPrice;
        }
        double[] copy = new double[hoursAvail];
        for (int i = 0; i < hoursAvail; i++) {
            copy[i] = sched[(hourNow + i) % sched.length];
        }
        java.util.Arrays.sort(copy);
        // Number of hours we need at 7 kW.
        double assumedKw = 7.0;
        int hoursNeeded = (int) Math.ceil(requiredKwh / assumedKw);
        hoursNeeded = Math.min(hoursNeeded, copy.length);
        double avgPrice = 0.0;
        for (int i = 0; i < hoursNeeded; i++) {
            avgPrice += copy[i];
        }
        avgPrice /= hoursNeeded;
        return requiredKwh * avgPrice;
    }

    private void publishStatus(int n, String status, boolean feasible, double required, double hoursRem,
            double projectedCost) {
        try {
            post(planItem(n, "Status"), new StringType(status));
            post(planItem(n, "Feasible"), OnOffType.from(feasible));
            post(planItem(n, "Required_kWh"), new DecimalType(round2(required)));
            post(planItem(n, "Hours_Remaining"), new DecimalType(round2(hoursRem)));
            post(planItem(n, "Projected_Cost_EUR"), new DecimalType(round2(projectedCost)));
        } catch (Throwable t) {
            // items might not exist on a fresh install — that's fine
        }
    }

    private void post(String item, org.openhab.core.types.State value) {
        try {
            // Touch registry to ensure item exists; if not, just skip.
            itemRegistry.getItem(item);
            eventPublisher.post(ItemEventFactory.createStateEvent(item, value, null));
        } catch (ItemNotFoundException e) {
            // Item not defined on this installation — skip silently.
        }
    }

    private double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    private boolean readSwitch(String name) {
        try {
            Item item = itemRegistry.getItem(name);
            State state = item.getState();
            if (state instanceof OnOffType o) {
                return o == OnOffType.ON;
            }
            return "ON".equalsIgnoreCase(state.toString());
        } catch (ItemNotFoundException e) {
            return false;
        }
    }

    private double readNumber(String name) {
        try {
            Item item = itemRegistry.getItem(name);
            State state = item.getState();
            if (state instanceof UnDefType) {
                return Double.NaN;
            }
            if (state instanceof DecimalType d) {
                return d.doubleValue();
            }
            if (state instanceof QuantityType q) {
                return q.doubleValue();
            }
            String s = state.toString();
            if (s == null || "NULL".equals(s) || "UNDEF".equals(s) || s.isEmpty()) {
                return Double.NaN;
            }
            int sp = s.indexOf(' ');
            return Double.parseDouble(sp > 0 ? s.substring(0, sp) : s);
        } catch (ItemNotFoundException | NumberFormatException e) {
            return Double.NaN;
        }
    }

    private @org.eclipse.jdt.annotation.Nullable Instant readInstant(String name) {
        try {
            Item item = itemRegistry.getItem(name);
            State state = item.getState();
            if (state instanceof UnDefType) {
                return null;
            }
            if (state instanceof DateTimeType dt) {
                return dt.getInstant();
            }
            String s = state.toString();
            if (s == null || s.isEmpty() || "NULL".equals(s) || "UNDEF".equals(s)) {
                return null;
            }
            // Tolerate ISO local-without-zone too.
            try {
                return ZonedDateTime.parse(s).toInstant();
            } catch (Exception ignored) {
                return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant();
            }
        } catch (ItemNotFoundException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String readString(String name, String fallback) {
        try {
            Item item = itemRegistry.getItem(name);
            State state = item.getState();
            if (state instanceof UnDefType) {
                return fallback;
            }
            if (state instanceof StringType st) {
                return st.toString();
            }
            String s = state.toString();
            return (s == null || s.isEmpty() || "NULL".equals(s) || "UNDEF".equals(s)) ? fallback : s;
        } catch (ItemNotFoundException e) {
            return fallback;
        }
    }
}

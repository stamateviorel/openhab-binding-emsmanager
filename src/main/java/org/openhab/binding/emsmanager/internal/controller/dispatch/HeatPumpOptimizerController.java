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
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.config.HeatPumpConfig;
import org.openhab.binding.emsmanager.internal.controller.peak.HardPeakShavingController;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.openhab.binding.emsmanager.internal.heatpump.HeatPumpAssetHandler;
import org.openhab.binding.emsmanager.internal.heatpump.ScopCurve;
import org.openhab.binding.emsmanager.internal.heatpump.ThermalModelEstimator;
import org.openhab.binding.emsmanager.internal.heatpump.ThermalPlanner;
import org.openhab.binding.emsmanager.internal.weather.OpenMeteoTempForecast;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * SCOP-aware heat-pump optimizer. Observer-only.
 *
 * <p>
 * Each tick, discovers all {@code emsmanager:heatpump} Things via the
 * ThingRegistry and publishes a recommendation per pump:
 * <ul>
 * <li>BOOST when solar excess exceeds the pump's boost threshold AND
 * allowBoostOnSurplus is true</li>
 * <li>BOOST when tariff price &lt; dayAvg × cheapPriceThresholdRatio AND
 * indoor temp &lt; target − deadband</li>
 * <li>OFF when hard-peak shaving has engaged any tier</li>
 * <li>OFF/ECO when tariff price &gt; dayAvg × expensivePriceThresholdRatio
 * AND indoor temp &gt;= target − deadband AND allowDeferOnPeak is true</li>
 * <li>COMFORT otherwise (let the user's thermostat run)</li>
 * </ul>
 *
 * <p>
 * Effective price = tariff_now / SCOP. Daily kWh estimate is a rough
 * heuristic: 24 × averageDrawObservedAtTickTime / SCOP.
 *
 * <p>
 * This controller does not write to the pump itself — it only publishes
 * recommendations on the heatpump Thing's channels. A downstream rule
 * (user-owned) can link the recommended-mode channel to their pump's mode
 * item.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class HeatPumpOptimizerController implements Controller {

    public static final String NAME = "heatpump-optimizer";

    private final ThingRegistry thingRegistry;
    private final ItemRegistry itemRegistry;
    private final HardPeakShavingController hardPeakShaving;
    private final @Nullable OpenMeteoTempForecast tempForecast;

    // Per-pump predictive state.
    private final java.util.Map<String, ThermalModelEstimator> estimators = new java.util.HashMap<>();
    private final java.util.Map<String, double[]> lastSample = new java.util.HashMap<>(); // [tInPrev, tOutPrev, lastMs]
    private final java.util.Map<String, Long> lastPlanMs = new java.util.HashMap<>();
    private static final long REPLAN_INTERVAL_MS = 15 * 60 * 1000L; // re-plan every 15 min

    public HeatPumpOptimizerController(ThingRegistry thingRegistry, ItemRegistry itemRegistry,
            HardPeakShavingController hardPeakShaving) {
        this(thingRegistry, itemRegistry, hardPeakShaving, null);
    }

    public HeatPumpOptimizerController(ThingRegistry thingRegistry, ItemRegistry itemRegistry,
            HardPeakShavingController hardPeakShaving, @Nullable OpenMeteoTempForecast tempForecast) {
        this.thingRegistry = thingRegistry;
        this.itemRegistry = itemRegistry;
        this.hardPeakShaving = hardPeakShaving;
        this.tempForecast = tempForecast;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_HEATPUMP_OPTIMIZER;
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
        // Day-avg tariff for cheap/expensive ratios.
        double[] sched = ctx.tariffSchedule24h();
        double dayAvg = avg(sched);
        double tariffNow = ctx.tariffPriceNowEurPerKWh();
        if (Double.isNaN(tariffNow) || tariffNow <= 0.0) {
            tariffNow = Double.isNaN(dayAvg) ? Double.NaN : dayAvg;
        }

        for (Thing t : thingRegistry.getAll()) {
            if (!THING_TYPE_HEATPUMP.equals(t.getThingTypeUID())) {
                continue;
            }
            org.openhab.core.thing.binding.ThingHandler h = t.getHandler();
            if (!(h instanceof HeatPumpAssetHandler hp)) {
                continue;
            }
            try {
                evaluateOne(hp, ctx, tariffNow, dayAvg);
            } catch (Throwable th) {
                // never let one pump break the controller
            }
        }
        return List.of();
    }

    private void evaluateOne(HeatPumpAssetHandler hp, EnergyContext ctx, double tariffNow, double dayAvg) {
        HeatPumpConfig cfg = hp.getCfg();

        double currentTemp = readNumber(cfg.currentTempItem);
        double targetTemp = readNumber(cfg.targetTempItem);
        double powerW = readNumber(cfg.powerItem);
        double outdoorTemp = readNumber(cfg.outdoorTempItem);

        // SCOP — prefer the piecewise curve evaluated at the real outdoor temp;
        // fall back to the scopCop constant.
        double cop = cfg.scopCop;
        ScopCurve curve = new ScopCurve(cfg.scopCurveCsv);
        double scopTemp = !Double.isNaN(outdoorTemp) ? outdoorTemp
                : (!Double.isNaN(currentTemp) && currentTemp < 10 ? currentTemp : Double.NaN);
        if (!curve.isEmpty() && !Double.isNaN(scopTemp)) {
            double interpolated = curve.copAt(scopTemp);
            if (!Double.isNaN(interpolated) && interpolated > 0) {
                cop = interpolated;
            }
        }
        double effectivePrice = (Double.isNaN(tariffNow) || cop <= 0) ? Double.NaN : tariffNow / cop;
        double dailyKwhEst = Double.isNaN(powerW) ? Double.NaN : (24.0 * Math.abs(powerW) / 1000.0);

        // Feed the thermal model + run the DP planner (publishes model channels).
        // Independent of the reactive mode decision below.
        boolean preheatNow = runPredictive(hp, cfg, ctx, currentTemp, targetTemp, outdoorTemp, powerW, cop, dayAvg);

        String mode;
        String reason;
        boolean optimizerActive;

        // 1) Hard-peak shaving — turn off.
        if (hardPeakShaving.level() > 0) {
            mode = "OFF";
            reason = "🛑 Peak shaving active (tier " + hardPeakShaving.level() + ") — switching off";
            optimizerActive = true;
            hp.publishDecision(mode, reason, effectivePrice, dailyKwhEst, optimizerActive);
            return;
        }

        // 1b) Predictive pre-heat — the DP planner says heat now (cheap window before
        // an upcoming expensive one). Takes precedence over the reactive branches.
        if (preheatNow) {
            mode = "BOOST";
            reason = "🔮 Predictive pre-heating (DP plan ahead of a tariff peak)";
            optimizerActive = true;
            hp.publishDecision(mode, reason, effectivePrice, dailyKwhEst, optimizerActive);
            return;
        }

        // 2) Solar excess > threshold AND allowed → BOOST (pre-heat).
        double excess = ctx.availableExcessW();
        boolean bigSurplus = !Double.isNaN(excess) && excess > cfg.boostSurplusThresholdW;
        boolean canBoostHeat = Double.isNaN(targetTemp) || Double.isNaN(currentTemp)
                || currentTemp < (targetTemp + 2.0); // up to 2 °C overshoot allowed
        if (cfg.allowBoostOnSurplus && bigSurplus && canBoostHeat) {
            mode = "BOOST";
            reason = String.format("☀️ Solar surplus %.1f kW — pre-heating", excess / 1000.0);
            optimizerActive = true;
            hp.publishDecision(mode, reason, effectivePrice, dailyKwhEst, optimizerActive);
            return;
        }

        // 3) Cheap tariff + temp below target → BOOST/ON
        boolean tempBelow = !Double.isNaN(currentTemp) && !Double.isNaN(targetTemp)
                && currentTemp < (targetTemp - cfg.tempDeadbandC);
        if (!Double.isNaN(tariffNow) && !Double.isNaN(dayAvg) && tariffNow < dayAvg * cfg.cheapPriceThresholdRatio
                && tempBelow) {
            mode = "BOOST";
            reason = String.format("💰 Cheap tariff (€%.3f/kWh, avg €%.3f) — pre-heating", tariffNow, dayAvg);
            optimizerActive = true;
            hp.publishDecision(mode, reason, effectivePrice, dailyKwhEst, optimizerActive);
            return;
        }

        // 4) Expensive tariff + temp comfortable → defer (OFF/ECO).
        boolean tempOk = Double.isNaN(currentTemp) || Double.isNaN(targetTemp)
                || currentTemp >= (targetTemp - cfg.tempDeadbandC);
        if (cfg.allowDeferOnPeak && !Double.isNaN(tariffNow) && !Double.isNaN(dayAvg)
                && tariffNow > dayAvg * cfg.expensivePriceThresholdRatio && tempOk) {
            mode = "ECO";
            reason = String.format("📈 Expensive tariff (€%.3f/kWh) — deferring", tariffNow);
            optimizerActive = true;
            hp.publishDecision(mode, reason, effectivePrice, dailyKwhEst, optimizerActive);
            return;
        }

        // 5) Default — normal comfort mode, let thermostat run.
        mode = "COMFORT";
        if (Double.isNaN(tariffNow)) {
            reason = "Comfort mode (no tariff info)";
        } else {
            reason = String.format("Comfort mode (€%.3f/kWh, SCOP %.1f → eff. €%.3f/kWh)", tariffNow, cfg.scopCop,
                    Double.isNaN(effectivePrice) ? 0 : effectivePrice);
        }
        optimizerActive = false;
        hp.publishDecision(mode, reason, effectivePrice, dailyKwhEst, optimizerActive);
    }

    /**
     * Predictive branch. Feeds one sample into the per-pump RLS estimator,
     * publishes the learned R/C/RMSE, and (every {@link #REPLAN_INTERVAL_MS}) runs
     * the 24-h DP planner using the tariff schedule + a flat-T_out forecast (current
     * outdoor temp held constant when no hourly forecast is available). Returns true
     * if the plan's first hour says "heat now".
     */
    private boolean runPredictive(HeatPumpAssetHandler hp, HeatPumpConfig cfg, EnergyContext ctx, double currentTemp,
            double targetTemp, double outdoorTemp, double powerW, double cop, double dayAvg) {
        if (!cfg.enablePredictive || Double.isNaN(outdoorTemp) || Double.isNaN(currentTemp)) {
            return false;
        }
        String id = hp.heatpumpId();
        long nowMs = ctx.tickAt().toEpochMilli();
        ThermalModelEstimator est = estimators.computeIfAbsent(id, k -> new ThermalModelEstimator());

        // Feed a sample if we have a previous reading.
        double[] prev = lastSample.get(id);
        if (prev != null) {
            double dt = (nowMs - (long) prev[2]) / 1000.0;
            // Heat input = electrical power × COP (thermal). Solar/internal folded into Q0≈0 for v1.
            double heatThermalW = (Double.isNaN(powerW) ? 0 : Math.abs(powerW)) * (cop > 0 ? cop : 1);
            est.update(dt, prev[0], currentTemp, prev[1], heatThermalW);
        }
        lastSample.put(id, new double[] { currentTemp, outdoorTemp, nowMs });

        double r = est.r();
        double c = est.c();
        double rmse = Math.abs(est.lastResidual());

        // Need a converged model + a tariff schedule to plan.
        double[] sched = ctx.tariffSchedule24h();
        boolean planValid = est.sampleCount() > 200 && !Double.isNaN(r) && !Double.isNaN(c) && r > 0 && c > 0
                && sched != null && sched.length >= 24 && !Double.isNaN(targetTemp);

        boolean preheatNow = false;
        java.time.ZonedDateTime preheatAt = null;
        double planCost = Double.NaN;
        if (planValid && sched != null && (nowMs - lastPlanMs.getOrDefault(id, 0L)) > REPLAN_INTERVAL_MS) {
            // Real hourly outdoor forecast (e.g. from OpenMeteo) when available; fall
            // back to holding the current temp constant.
            double[] tOutForecast = new double[24];
            java.util.Arrays.fill(tOutForecast, outdoorTemp);
            if (tempForecast != null) {
                double[] fc = tempForecast.hourlyFrom(java.time.Instant.ofEpochMilli(nowMs), 24);
                if (fc.length == 24) {
                    for (int hh = 0; hh < 24; hh++) {
                        if (!Double.isNaN(fc[hh])) {
                            tOutForecast[hh] = fc[hh];
                        }
                    }
                }
            }
            ThermalPlanner.Plan plan = ThermalPlanner.plan(currentTemp, targetTemp, cfg.tempDeadbandC, tOutForecast,
                    sched, r, c, cfg.heatPowerW, cop > 0 ? cop : 1);
            if (plan.action().length > 0) {
                preheatNow = plan.action()[0] == 1;
                planCost = plan.totalCost() >= 1e17 ? Double.NaN : plan.totalCost();
                // First hour where the plan heats → "preheat starts at".
                for (int hh = 0; hh < plan.action().length; hh++) {
                    if (plan.action()[hh] == 1) {
                        preheatAt = java.time.ZonedDateTime.now().plusHours(hh).withMinute(0).withSecond(0).withNano(0);
                        break;
                    }
                }
            }
            lastPlanMs.put(id, nowMs);
        }

        hp.publishModel(r, c, rmse, preheatAt, planCost);
        return preheatNow;
    }

    private static double avg(double @Nullable [] arr) {
        if (arr == null || arr.length == 0) {
            return Double.NaN;
        }
        double sum = 0;
        int count = 0;
        for (double v : arr) {
            if (!Double.isNaN(v)) {
                sum += v;
                count++;
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private double readNumber(@Nullable String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return Double.NaN;
        }
        try {
            Item item = itemRegistry.getItem(itemName);
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
}

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
package org.openhab.binding.emsmanager.internal.ems;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;

/**
 * The energy-management "brain" from Kai Kreuzer's core design (openhab-core #3478) — fed the
 * participant items and producing the actions. Kai noted the algorithm "could possibly then
 * even be as simple as a rule template", so a strategy is deliberately a small, swappable,
 * pure function. This ships the canonical baseline strategy: <b>surplus dispatch</b>.
 *
 * <p>
 * {@link #planSurplusDispatch} greedily soaks the available solar surplus into the consumers
 * in registration order (= priority): a {@link PowerProfile.Controllable} load is set to as
 * much of the remaining surplus as fits within its {@code [minW, maxW]}; a
 * {@link PowerProfile.Simple} load is switched ON once the remaining surplus clears a
 * threshold. Pure and side-effect free, so it is unit-testable without items.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class EnergyManagementService {

    private EnergyManagementService() {
    }

    /**
     * Worst-phase breaker headroom in amps — the safety primitive the legacy
     * {@code SafetyBreakerController} guards on. {@code limitAperPhase} minus the most-loaded
     * phase; a dispatch that would add load must keep this above a margin (typically 6 A). NaN
     * phase readings are treated as 0 (no known load), and a non-positive limit disables the
     * guard (returns +∞).
     */
    public static double minBreakerHeadroomA(double l1, double l2, double l3, double limitAperPhase) {
        if (limitAperPhase <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        double a1 = Double.isNaN(l1) ? 0.0 : l1;
        double a2 = Double.isNaN(l2) ? 0.0 : l2;
        double a3 = Double.isNaN(l3) ? 0.0 : l3;
        return limitAperPhase - Math.max(a1, Math.max(a2, a3));
    }

    /**
     * The surplus implied by a grid net-power reading, in canonical sign ({@code + = export}):
     * exported power is the spare energy available to dispatch. This lets the service derive
     * its own surplus straight from a tagged grid {@link EnergyProvider}, so it depends only on
     * the participant items — as Kai envisioned — rather than on any pre-computed value.
     *
     * @param gridNetW grid net power, watts, {@code + = export / − = import} (NaN → 0)
     * @return spare power in watts ({@code >= 0})
     */
    public static double surplusFromGridNet(double gridNetW) {
        return Double.isNaN(gridNetW) ? 0.0 : Math.max(0.0, gridNetW);
    }

    /**
     * Price + deadline strategy: should a deadline-bound consumer run <em>now</em>? The deadline is
     * a recurring daily "ready by" hour, so the window runs from the current hour up to the next
     * occurrence of {@code deadlineHour}, <b>wrapping past midnight</b> (e.g. 23:00 now, ready by
     * 07:00 → an 8-hour overnight window). True when the current hour is among the cheapest hours
     * in that window needed to deliver the remaining demand at the rated power. With no usable
     * price schedule it falls back to the latest hours before the deadline (ties prefer later);
     * at the deadline hour itself with demand left → run now. Mirrors {@code BoilerPlanController}.
     *
     * @param hourNow current local hour 0..23
     * @param deadlineHour the daily "ready by" local hour 0..23
     * @param remainingKwh energy still to deliver
     * @param ratedKw the load's power draw in kW (used to size how many hours are needed)
     * @param priceSchedule24h 24 hourly prices (any length &lt; 24 disables price ranking)
     */
    public static boolean runNowForDeadline(int hourNow, int deadlineHour, double remainingKwh, double ratedKw,
            double[] priceSchedule24h) {
        if (remainingKwh <= 0) {
            return false;
        }
        int h = Math.floorMod(hourNow, 24);
        int span = Math.floorMod(deadlineHour - h, 24);
        if (span == 0) {
            return true; // the deadline hour itself → must run
        }
        double rated = ratedKw > 0 ? ratedKw : 1.0;
        int hoursNeeded = Math.max(1, Math.min(span, (int) Math.ceil(remainingKwh / rated)));
        boolean havePrices = priceSchedule24h.length >= 24;
        List<Integer> window = new ArrayList<>();
        for (int i = 0; i < span; i++) {
            window.add((h + i) % 24);
        }
        window.sort((a, b) -> {
            if (havePrices) {
                int c = Double.compare(priceSchedule24h[a], priceSchedule24h[b]);
                if (c != 0) {
                    return c;
                }
            }
            // tie / no prices: prefer the hour later in the window (closer to the deadline)
            return Integer.compare(Math.floorMod(b - h, 24), Math.floorMod(a - h, 24));
        });
        for (int i = 0; i < hoursNeeded && i < window.size(); i++) {
            if (window.get(i) == h) {
                return true;
            }
        }
        return false;
    }

    /** Parse a CSV of hourly prices ("0.30,0.28,…") into a {@code double[]}; bad/empty → empty array. */
    public static double[] parseSchedule(@Nullable String csv) {
        if (csv == null || csv.isBlank()) {
            return new double[0];
        }
        String[] parts = csv.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                return new double[0];
            }
        }
        return out;
    }

    /**
     * Battery strategy: charge a controllable battery {@link EnergyProvider} from the available
     * surplus — Kai's "send a command to a controllable provider to request a new power" (#3478).
     * The battery's {@code minW} is its (negative) max-charge setpoint, so charging consumes
     * surplus as a negative watt command clamped to that limit. Returns an idle (0 W) action when
     * the battery is full, there is no surplus, or the SoC is unknown-high; {@code null} when the
     * provider is not a controllable battery.
     *
     * @param surplusW spare power, watts (NaN/≤0 → idle)
     * @param socPct battery state of charge 0..100 (NaN treated as 0 = allow charge)
     * @param battery the candidate provider
     */
    public static @Nullable EmsAction planBatteryCharge(double surplusW, double socPct, EnergyProvider battery) {
        String control = battery.controlItem();
        if (battery.role() != ProviderRole.BATTERY || !battery.controllable() || control == null) {
            return null;
        }
        double soc = Double.isNaN(socPct) ? 0.0 : socPct;
        if (soc >= 100.0 || Double.isNaN(surplusW) || surplusW <= 0.0) {
            return new EmsAction(control, EmsAction.Kind.SET_WATTS, 0.0, "battery: idle (full or no surplus)");
        }
        double maxChargeW = Double.isNaN(battery.minW()) ? -surplusW : battery.minW();
        double cmd = Math.max(maxChargeW, -surplusW); // negative = charge; clamp magnitude to the limit
        return new EmsAction(control, EmsAction.Kind.SET_WATTS, cmd, "battery: charge from surplus");
    }

    /**
     * Hard peak-shave hysteresis (legacy {@code HardPeakShavingController}): becomes active when
     * grid import exceeds the engage threshold ({@code gridW < engageW}, e.g. −15000 W = importing
     * &gt; 15 kW) and stays active until the grid recovers past {@code recoverW} (e.g. −10000 W).
     * The caller holds the active flag across ticks. (The legacy 180 s confirmation window is a
     * de-noising optimisation; the engine engages sooner, which is the safer direction.)
     */
    public static boolean peakShaveActive(double gridW, double engageW, double recoverW, boolean currentlyActive) {
        if (Double.isNaN(gridW)) {
            return currentlyActive;
        }
        return currentlyActive ? gridW <= recoverW : gridW < engageW;
    }

    /**
     * Peak-shave shed gate: while peak-shaving is active, no load may stay on — every positive
     * action is shed to off/0. Like the breaker gate, this is a safety override that economics
     * cannot beat. Returns the list unchanged when not shaving.
     */
    public static List<EmsAction> applyPeakShaveGate(List<EmsAction> actions, boolean active) {
        if (!active) {
            return actions;
        }
        List<EmsAction> shed = new ArrayList<>();
        for (EmsAction a : actions) {
            if (a.value() > 0) {
                shed.add(new EmsAction(a.itemName(), a.kind(), 0.0, "peak-shaving: shedding load (grid import peak)"));
            } else {
                shed.add(a);
            }
        }
        return shed;
    }

    /**
     * Shared ECO charging budget per active car, in watts (legacy {@code EvCoordinatorController}):
     * the ECO cars' current total draw plus the grid export headroom, minus a safety margin, split
     * evenly across the active ECO cars and clamped to {@code >= 0}. NaN grid or no active cars →
     * NaN (no budget — the caller falls back to the minimum).
     */
    public static double ecoBudgetPerCarW(double gridSmoothedW, double totalEcoDrawW, int activeEcoCars,
            double gridSafetyMarginW) {
        if (Double.isNaN(gridSmoothedW) || activeEcoCars <= 0) {
            return Double.NaN;
        }
        return Math.max(0.0, (totalEcoDrawW + gridSmoothedW - gridSafetyMarginW) / activeEcoCars);
    }

    /** How many amps a watt budget supports over the given phases and phase voltage. */
    public static int budgetToAmps(double budgetW, int phases, int phaseVoltage) {
        if (Double.isNaN(budgetW) || phases <= 0 || phaseVoltage <= 0) {
            return 0;
        }
        return (int) Math.floor(budgetW / phases / phaseVoltage);
    }

    /** Ramp-limit an amp target: it may rise at most {@code rampStepA} per tick; drops are immediate. */
    public static int rampLimitAmps(int desiredA, int prevA, int rampStepA) {
        return Math.min(desiredA, prevA + rampStepA);
    }

    /** Hysteresis: keep the previous amps when the requested change is smaller than {@code hysteresisA}. */
    public static int applyHysteresisAmps(int desiredA, int prevA, int hysteresisA) {
        return Math.abs(desiredA - prevA) < hysteresisA ? prevA : desiredA;
    }

    /**
     * Per-car EV target charging current (legacy {@code EvCoordinatorController} core decision,
     * before ramp + hysteresis): OFF → 0; if the car's own breaker headroom is below the minimum it
     * cannot safely charge → 0; SNEL → the breaker-limited maximum; ECO (and any non-SNEL/OFF mode)
     * → the solar budget in amps, clamped to {@code [minA, headroom, maxA]}, or 0 when the budget
     * cannot sustain the minimum.
     */
    public static int evTargetAmps(CarSnapshot.Mode mode, int carHeadroomA, double ecoBudgetA, int minA, int maxA) {
        if (mode == CarSnapshot.Mode.OFF || carHeadroomA < minA) {
            return 0;
        }
        if (mode == CarSnapshot.Mode.SNEL) {
            return Math.min(maxA, carHeadroomA);
        }
        int eco = (int) Math.floor(ecoBudgetA);
        if (eco < minA) {
            return 0;
        }
        return Math.min(Math.min(eco, maxA), carHeadroomA);
    }

    /**
     * Legacy-faithful per-car charging target (pre ramp + hysteresis) for a car already decided to
     * charge — i.e. cable in, not OFF, breaker headroom &ge; {@code minA}, not deferred by
     * peak-shaving. This mirrors {@code EvCoordinatorController.handleCar} amps math exactly and, in
     * contrast to {@link #evTargetAmps}, <b>floors ECO at {@code minA}</b> (an ECO car with a cable
     * plugged never drops below the minimum while charging — the site's "ECO never auto-pauses"
     * invariant) rather than pausing. SNEL → {@code min(maxA, headroom)}; ECO (and any non-SNEL/OFF
     * mode) → {@code max(minA, budgetAmps)} then clamped by both the breaker headroom and the soft
     * ECO cap. A {@code NaN} budget falls back to {@code minA}. Used by the shadow EV parity path.
     */
    public static int evChargeTargetAmps(CarSnapshot.Mode mode, int carHeadroomA, double ecoBudgetW, int ecoCapA,
            int phases, int phaseVoltage, int minA, int maxA) {
        if (mode == CarSnapshot.Mode.SNEL) {
            return Math.min(maxA, carHeadroomA);
        }
        int desired = minA;
        if (!Double.isNaN(ecoBudgetW)) {
            desired = Math.max(minA, budgetToAmps(ecoBudgetW, phases, phaseVoltage));
        }
        return Math.min(desired, Math.min(carHeadroomA, ecoCapA));
    }

    /** Outcome of the auto-RemoteStart backoff for one car this tick (legacy EvCoordinatorController). */
    public record RemoteStartDecision(boolean emitStart, int newAttempts, boolean wedged, boolean suppressAmps) {
    }

    /**
     * Auto-RemoteStart backoff (legacy {@code EvCoordinatorController}): a car that is not
     * {@code Charging} but is in a start-capable status ({@code Available} / {@code Preparing} /
     * {@code SuspendedEVSE}) is sent a RemoteStart — but only {@code maxAttempts} ticks in a row.
     * Past that it is declared <b>wedged</b>: RemoteStart is retried only every {@code slowRetryTicks}
     * and <b>nothing else</b> is emitted for it that tick ({@code suppressAmps}), so a charger that
     * Rejects every start does not get spammed. {@code Charging} clears the counter; any other status
     * ({@code Finishing} / {@code Faulted} / …) leaves the counter intact and lets the amps decision
     * proceed unchanged.
     */
    public static RemoteStartDecision remoteStartDecision(String status, int prevAttempts, int maxAttempts,
            int slowRetryTicks) {
        if ("Charging".equals(status)) {
            return new RemoteStartDecision(false, 0, false, false);
        }
        boolean startState = "Available".equals(status) || "Preparing".equals(status) || "SuspendedEVSE".equals(status);
        if (!startState) {
            return new RemoteStartDecision(false, prevAttempts, false, false);
        }
        int attempts = prevAttempts + 1;
        if (attempts <= maxAttempts) {
            return new RemoteStartDecision(true, attempts, false, false);
        }
        boolean slow = (attempts - maxAttempts) % slowRetryTicks == 0;
        return new RemoteStartDecision(slow, attempts, true, true);
    }

    /**
     * Time-of-use battery setpoint (legacy {@code BatteryTouDispatcher}): a fixed daily schedule.
     * Charge at {@code chargeRateW} (a negative watt setpoint) during the night band
     * {@code [nightStartHour, nightEndHour)}; discharge at {@code dischargeRateW} during the evening
     * peak {@code [eveStartHour, eveEndHour)} — but only when the battery is above its reserve
     * ({@code !batteryBelowReserve}), else hold to protect the reserve. Outside both bands, passive.
     * Returns {@code null} for "no setpoint request this tick" (passive / reserve-protected), matching
     * the legacy controller which emits nothing in those cases.
     */
    public static @Nullable Double batteryTouSetpointW(int hour, boolean batteryBelowReserve, int nightStartHour,
            int nightEndHour, int eveStartHour, int eveEndHour, double chargeRateW, double dischargeRateW) {
        if (hour >= eveStartHour && hour < eveEndHour) {
            return batteryBelowReserve ? null : dischargeRateW;
        }
        if (hour >= nightStartHour && hour < nightEndHour) {
            return chargeRateW;
        }
        return null;
    }

    /**
     * Soft production/export-shave ECO cap (legacy {@code SoftPeakShavingController}) — a sticky
     * hysteresis band on the ECO charging cap. When the smoothed grid imports beyond
     * {@code thresholdW} (a large negative, e.g. −5000 W) drop the cap to {@code lowCapA}; once it
     * recovers above {@code recoveryW} (e.g. −3000 W) lift it back to {@code normalCapA}; in the
     * band between, hold {@code prevCapA} (avoids flapping). A {@code NaN} grid reading holds the
     * previous cap. Stateful across ticks: feed the returned value back in as {@code prevCapA}.
     */
    public static int softEcoCapA(double gridSmoothedW, int prevCapA, double thresholdW, double recoveryW, int lowCapA,
            int normalCapA) {
        if (Double.isNaN(gridSmoothedW)) {
            return prevCapA;
        }
        if (gridSmoothedW < thresholdW) {
            return lowCapA;
        }
        if (gridSmoothedW > recoveryW) {
            return normalCapA;
        }
        return prevCapA;
    }

    /**
     * Belgian capacity-tariff peak guard (legacy {@code CapacityTariffShavingController}): true when
     * the projected end-of-quarter grid-import average would set a NEW month-to-date peak beyond a
     * safety margin, and that peak clears the minimum billable floor. All values are signed
     * ({@code − = import}); a new peak is MORE negative than the current one. Exporting/unknown →
     * false (no import-peak concern).
     */
    public static boolean wouldExceedCapacityPeak(double projectedQuarterW, double monthlyPeakW, double minBillableW,
            double marginW) {
        if (Double.isNaN(projectedQuarterW) || projectedQuarterW >= 0) {
            return false;
        }
        double mtdPeak = Double.isNaN(monthlyPeakW) ? 0.0 : monthlyPeakW;
        double threshold = Math.min(mtdPeak, -minBillableW);
        return projectedQuarterW < threshold - marginW;
    }

    /** Capacity-tariff shed gate: shed positive load while a new monthly peak is projected. */
    public static List<EmsAction> applyCapacityGate(List<EmsAction> actions, boolean wouldExceed) {
        if (!wouldExceed) {
            return actions;
        }
        List<EmsAction> shed = new ArrayList<>();
        for (EmsAction a : actions) {
            if (a.value() > 0) {
                shed.add(new EmsAction(a.itemName(), a.kind(), 0.0,
                        "capacity-tariff: shedding to avoid a new monthly peak"));
            } else {
                shed.add(a);
            }
        }
        return shed;
    }

    /** Outcome of the solar-surplus hysteresis: turn the load on, off, or leave it as-is. */
    public enum SurplusDecision {
        ON,
        OFF,
        HOLD
    }

    /**
     * The cloudiness-adaptive solar on-threshold from the legacy {@code SolarSurplusDispatcher}:
     * gloomy (&gt;70 %) → 500 W (grab what little excess there is); sunny (&lt;20 %) → 2500 W (wait
     * for a clear excess to avoid flapping); else 2000 W. {@code +1500 W} when the battery is below
     * its reserve (charging the battery comes first). NaN cloudiness → the 2000 W default.
     */
    public static double cloudinessAdaptiveThresholdW(double cloudinessPct, boolean batteryBelowReserve) {
        double base = 2000.0;
        if (!Double.isNaN(cloudinessPct) && cloudinessPct > 70.0) {
            base = 500.0;
        } else if (!Double.isNaN(cloudinessPct) && cloudinessPct < 20.0) {
            base = 2500.0;
        }
        return base + (batteryBelowReserve ? 1500.0 : 0.0);
    }

    /**
     * Solar-surplus boiler hysteresis from the legacy {@code SolarSurplusDispatcher}: drives off
     * the 5-minute average grid power (export +, import −). Turns ON only when the average exceeds
     * {@code onThresholdW} and the load is currently off; turns OFF only when the average drops
     * below {@code offThresholdW} (e.g. −1000 W) and the load is on; otherwise HOLD. The
     * transition-only behaviour avoids flapping and matches the legacy "issue a command only when
     * the state must change" pattern.
     */
    public static SurplusDecision planSolarBoiler(double avg5minGridW, double onThresholdW, double offThresholdW,
            boolean currentlyOn) {
        if (Double.isNaN(avg5minGridW)) {
            return SurplusDecision.HOLD;
        }
        if (avg5minGridW > onThresholdW && !currentlyOn) {
            return SurplusDecision.ON;
        }
        if (avg5minGridW < offThresholdW && currentlyOn) {
            return SurplusDecision.OFF;
        }
        return SurplusDecision.HOLD;
    }

    /**
     * SAFETY gate (the legacy {@code SafetyBreakerController} invariant): when the worst-phase
     * breaker headroom is below {@code marginA} (typically 6 A), no plan may ADD load — every
     * turn-on / set-to-positive action is overridden to off/0. Economics never outrank the fuse.
     * Returns the list unchanged when headroom is sufficient (or unknown/infinite).
     */
    public static List<EmsAction> applyBreakerGate(List<EmsAction> actions, double headroomA, double marginA) {
        if (Double.isInfinite(headroomA) || headroomA >= marginA) {
            return actions;
        }
        List<EmsAction> gated = new ArrayList<>();
        for (EmsAction a : actions) {
            if (a.value() > 0) {
                gated.add(new EmsAction(a.itemName(), a.kind(), 0.0, "breaker safety: headroom low, load held"));
            } else {
                gated.add(a);
            }
        }
        return gated;
    }

    /**
     * Unified per-consumer plan producing ONE coherent decision per consumer, covering all four
     * profile classes. A consumer with a demand whose cheapest hour is now runs at full power
     * (drawing from the grid in that cheap hour); otherwise it is dispatched from the remaining
     * surplus (controllable → fitting watts, simple → on above the threshold). A
     * {@link PowerProfile.ModeControllable} load gets the mode matching the current availability
     * (see {@link #modeIndex}); a {@link PowerProfile.Batch} load is started on live surplus or in
     * the cheapest contiguous window before its deadline (see {@link #batchStartNow}) and otherwise
     * receives an explicit {@link EmsAction.Kind#HOLD} — never an OFF, so a running program is not
     * interrupted. Deadline-driven loads do not consume the surplus budget (they are
     * grid-scheduled), so surplus still flows to the other consumers. Exactly one action is
     * returned per consumer, in order.
     */
    public static List<EmsAction> planConsumers(List<EnergyConsumer> consumers, double surplusW,
            double simpleLoadThresholdW, int hourNow, double[] priceSchedule24h) {
        return planConsumers(consumers, surplusW, simpleLoadThresholdW, hourNow, priceSchedule24h,
                LevelWindows.DEFAULT);
    }

    /** As above, with the site's configured (or seasonal) energy-level price windows. */
    public static List<EmsAction> planConsumers(List<EnergyConsumer> consumers, double surplusW,
            double simpleLoadThresholdW, int hourNow, double[] priceSchedule24h, LevelWindows levelWindows) {
        List<EmsAction> actions = new ArrayList<>();
        double remaining = Double.isNaN(surplusW) ? 0.0 : Math.max(0.0, surplusW);
        for (EnergyConsumer c : consumers) {
            boolean demandDriven = c.demandKwh() > 0 && c.deadlineHour() >= 0 && c.deadlineHour() <= 23
                    && runNowForDeadline(hourNow, c.deadlineHour(), c.demandKwh(), ratedKw(c, simpleLoadThresholdW),
                            priceSchedule24h);
            PowerProfile profile = c.profile();
            if (profile instanceof PowerProfile.Simple sp) {
                double thr = Double.isNaN(sp.thresholdW()) || sp.thresholdW() <= 0 ? simpleLoadThresholdW
                        : sp.thresholdW();
                if (demandDriven) {
                    // A demand+deadline guarantee wins even under a level gate — the deadline is a
                    // promise ("4 kWh by 07:00"), not a preference — so the interactive per-device
                    // level control is safe to set on a deadline-driven load.
                    actions.add(new EmsAction(sp.itemName(), EmsAction.Kind.ONOFF, 1.0, "deadline: cheapest hour, on"));
                } else if (sp.runAtLevel() > 0) {
                    // Energy-level-driven (storm.house's Schaltniveau): ON at level >= N, OFF below;
                    // 4+ = never. Bypasses the surplus budget (draw is level-justified).
                    int level = energyLevel(remaining, simpleLoadThresholdW, false,
                            pricePercentileLevel(hourNow, priceSchedule24h, levelWindows));
                    boolean on = sp.runAtLevel() <= 3 && level >= sp.runAtLevel();
                    actions.add(new EmsAction(sp.itemName(), EmsAction.Kind.ONOFF, on ? 1.0 : 0.0,
                            (on ? "level " + level + " >= " + sp.runAtLevel() + ": on"
                                    : "level gate: off (level " + level + " < " + sp.runAtLevel() + ")")));
                } else if (remaining >= thr) {
                    actions.add(new EmsAction(sp.itemName(), EmsAction.Kind.ONOFF, 1.0, "surplus dispatch: on"));
                    remaining -= thr;
                } else {
                    actions.add(new EmsAction(sp.itemName(), EmsAction.Kind.ONOFF, 0.0,
                            "idle: no surplus, deadline not due"));
                }
            } else if (profile instanceof PowerProfile.Controllable cp) {
                if (demandDriven) {
                    double w = Double.isNaN(cp.maxW()) ? Math.max(remaining, 1.0) : cp.maxW();
                    actions.add(new EmsAction(cp.itemName(), EmsAction.Kind.SET_WATTS, w,
                            "deadline: cheapest hour, full power"));
                } else {
                    double max = Double.isNaN(cp.maxW()) ? remaining : cp.maxW();
                    double min = Double.isNaN(cp.minW()) ? 0.0 : cp.minW();
                    double want = Math.min(max, remaining);
                    if (want > 0 && want >= min) {
                        actions.add(new EmsAction(cp.itemName(), EmsAction.Kind.SET_WATTS, want, "surplus dispatch"));
                        remaining -= want;
                    } else {
                        actions.add(new EmsAction(cp.itemName(), EmsAction.Kind.SET_WATTS, 0.0,
                                "idle: no surplus, deadline not due"));
                    }
                }
            } else if (profile instanceof PowerProfile.ModeControllable mp) {
                int idx = modeIndex(mp.modes().size(), remaining, simpleLoadThresholdW, demandDriven,
                        isExpensiveHour(hourNow, priceSchedule24h));
                // The mode class has no power setpoint, so the encouraged modes' draw is unknown and the
                // surplus budget is deliberately left untouched (documented reference limitation).
                actions.add(new EmsAction(mp.itemName(), EmsAction.Kind.SET_MODE, idx, mp.modes().get(idx),
                        "mode: " + mp.modes().get(idx) + (demandDriven ? " (deadline pressure)" : "")));
            } else if (profile instanceof PowerProfile.Batch bp) {
                double rated = Double.isNaN(bp.ratedW()) || bp.ratedW() <= 0 ? simpleLoadThresholdW : bp.ratedW();
                if (remaining >= rated) {
                    actions.add(new EmsAction(bp.itemName(), EmsAction.Kind.ONOFF, 1.0, "batch: surplus start"));
                    remaining -= rated;
                } else if (batchStartNow(hourNow, c.deadlineHour(), bp.runtimeHours(), priceSchedule24h, bp.shape())) {
                    actions.add(new EmsAction(bp.itemName(), EmsAction.Kind.ONOFF, 1.0,
                            "batch: cheapest window before deadline, start"));
                } else {
                    actions.add(new EmsAction(bp.itemName(), EmsAction.Kind.HOLD, 0.0,
                            "batch: waiting (no surplus, window not due)"));
                }
            }
        }
        return actions;
    }

    /** Hours counted as the "most expensive of the day" by {@link #isExpensiveHour}. */
    private static final int EXPENSIVE_HOURS_PER_DAY = 4;

    /**
     * True when the current hour ranks among the {@value #EXPENSIVE_HOURS_PER_DAY} most expensive
     * hours of a full 24 h price schedule (the shape of storm.house's {@code teuerstSperren}
     * window). A flat schedule has no expensive hours; fewer than 24 prices disables the check.
     */
    public static boolean isExpensiveHour(int hourNow, double[] priceSchedule24h) {
        if (priceSchedule24h.length < 24) {
            return false;
        }
        int h = Math.floorMod(hourNow, 24);
        double min = priceSchedule24h[0];
        int moreExpensive = 0;
        for (int i = 0; i < 24; i++) {
            min = Math.min(min, priceSchedule24h[i]);
            if (priceSchedule24h[i] > priceSchedule24h[h]) {
                moreExpensive++;
            }
        }
        return priceSchedule24h[h] > min && moreExpensive < EXPENSIVE_HOURS_PER_DAY;
    }

    /**
     * True when the current hour ranks among the {@value #EXPENSIVE_HOURS_PER_DAY} cheapest hours
     * of a full 24 h schedule (the mirror of {@link #isExpensiveHour} — storm.house's
     * "best-price" window shape). A flat schedule has no cheapest hours.
     */
    public static boolean isCheapestHour(int hourNow, double[] priceSchedule24h) {
        if (priceSchedule24h.length < 24) {
            return false;
        }
        int h = Math.floorMod(hourNow, 24);
        double max = priceSchedule24h[0];
        int cheaper = 0;
        for (int i = 0; i < 24; i++) {
            max = Math.max(max, priceSchedule24h[i]);
            if (priceSchedule24h[i] < priceSchedule24h[h]) {
                cheaper++;
            }
        }
        return priceSchedule24h[h] < max && cheaper < EXPENSIVE_HOURS_PER_DAY;
    }

    /**
     * Discrete-mode selection for a {@link PowerProfile.ModeControllable} load (taxonomy class 3,
     * SG-ready style). The availability is graded into four canonical levels — restricted / normal /
     * encouraged / maximum — and scaled onto the profile's mode list (index 0 = most restricted,
     * last = most encouraged): strong surplus (≥ 2× threshold) → maximum; some surplus (≥ threshold)
     * or deadline pressure → encouraged; an expensive hour with nothing spare → restricted;
     * otherwise normal.
     */
    public static int modeIndex(int modeCount, double surplusW, double thresholdW, boolean deadlinePressure,
            boolean expensiveHour) {
        int last = Math.max(0, modeCount - 1);
        return (int) Math.ceil(energyLevel(surplusW, thresholdW, deadlinePressure, expensiveHour) * last / 3.0);
    }

    /**
     * The site-wide <b>energy level</b> — how available cheap power is right now, graded
     * 0 (restricted) / 1 (normal) / 2 (encouraged) / 3 (maximum) from PV surplus and price.
     * The same concept M. Storm's production EMS calls <i>Energieniveau</i> (deliberately four
     * levels so it maps 1:1 onto SG-ready modes, EVCC charge modes, and — collapsed — plain
     * on/off): the site computes ONE level, and every consumer class maps it onto its own
     * control surface. Exposed as a first-class output so UIs can show a single glanceable
     * state and per-consumer "run at level >= N" thresholds stay trivial.
     */
    public static int energyLevel(double surplusW, double thresholdW, boolean deadlinePressure, boolean expensiveHour) {
        return energyLevel(surplusW, thresholdW, deadlinePressure, expensiveHour, false);
    }

    /**
     * As above, additionally lifted to at least "encouraged" during the day's cheapest-price
     * hours ({@link #isCheapestHour}) — the price half of the level, alongside the surplus half.
     * (storm.house computes its levels from configurable price-percentile windows with seasonal
     * profiles; this fixed-window lift is the reference's simpler equivalent.)
     */
    public static int energyLevel(double surplusW, double thresholdW, boolean deadlinePressure, boolean expensiveHour,
            boolean cheapestHour) {
        return energyLevel(surplusW, thresholdW, deadlinePressure, cheapestHour ? 2 : (expensiveHour ? 0 : 1));
    }

    /**
     * The level from the surplus half merged with a pre-graded price level (0..3, from
     * {@link #pricePercentileLevel}): strong surplus is always "maximum", any surplus or deadline
     * pressure lifts to at least "encouraged", a restricted price hour with nothing spare grades
     * "restricted", and otherwise the price level carries (so a percentile-cheapest hour can grade
     * "maximum" on price alone — storm.house semantics).
     */
    public static int energyLevel(double surplusW, double thresholdW, boolean deadlinePressure, int priceLevel) {
        double s = Double.isNaN(surplusW) ? 0.0 : surplusW;
        if (s >= 2 * thresholdW) {
            return 3;
        }
        if (s >= thresholdW || deadlinePressure) {
            return Math.max(priceLevel, 2);
        }
        if (priceLevel == 0 && s <= 0) {
            return 0;
        }
        return Math.max(priceLevel, 1);
    }

    /**
     * Grade the current hour's price into a level 0..3 by its rank in the 24 h schedule, using
     * configurable windows (storm.house's percentile windows): within the {@code cheapestHours}
     * cheapest of the day → 3, within the {@code cheapHours} cheapest → 2, within the
     * {@code expensiveHours} most expensive → 0, else 1. A flat or missing schedule grades 1
     * (no price signal). Tie-handling matches {@link #isExpensiveHour}/{@link #isCheapestHour}.
     */
    public static int pricePercentileLevel(int hourNow, double[] priceSchedule24h, LevelWindows windows) {
        if (priceSchedule24h.length < 24) {
            return 1;
        }
        int h = Math.floorMod(hourNow, 24);
        double min = priceSchedule24h[0];
        double max = priceSchedule24h[0];
        int cheaper = 0;
        int moreExpensive = 0;
        for (int i = 0; i < 24; i++) {
            min = Math.min(min, priceSchedule24h[i]);
            max = Math.max(max, priceSchedule24h[i]);
            if (priceSchedule24h[i] < priceSchedule24h[h]) {
                cheaper++;
            }
            if (priceSchedule24h[i] > priceSchedule24h[h]) {
                moreExpensive++;
            }
        }
        if (priceSchedule24h[h] < max && cheaper < windows.cheapestHours()) {
            return 3;
        }
        if (priceSchedule24h[h] < max && cheaper < windows.cheapHours()) {
            return 2;
        }
        if (priceSchedule24h[h] > min && moreExpensive < windows.expensiveHours()) {
            return 0;
        }
        return 1;
    }

    /**
     * Deterministic consumer ordering for surplus allocation: by {@link EnergyConsumer#priority()}
     * (lower = served first — storm.house's {@code Prioritaet}), ties by id. Without this, the
     * allocation order would depend on item-registry iteration order.
     */
    public static List<EnergyConsumer> sortByPriority(List<EnergyConsumer> consumers) {
        List<EnergyConsumer> out = new ArrayList<>(consumers);
        out.sort(java.util.Comparator.comparingInt(EnergyConsumer::priority).thenComparing(EnergyConsumer::id));
        return out;
    }

    /**
     * The per-consumer level-control item the engine honours if one exists: a Number 0..4 (0 = auto /
     * no gate, 1..3 = run at site level &gt;= N, 4 = never). Optional manual override — the binding no
     * longer auto-creates it; hand-create an item with this name and {@code readLevelOverride} reads it.
     */
    public static String levelControlItem(String consumerItemName) {
        return consumerItemName + "_EmsLevel";
    }

    /** Outcome of {@link #constrainOnOff}: the (possibly overridden) state and why. */
    public record SwitchConstraint(boolean on, boolean overridden, String reason) {
    }

    /**
     * Enforce a simple load's protection parameters ({@code {min,max} x {on,off}} minutes,
     * 0 = unconstrained) on a planner's desired state. Order of precedence: while ON,
     * min-runtime holds ON, then max-runtime forces OFF; while OFF, max-off forces ON (the
     * fridge case — after which min-runtime naturally provides the catch-up time), then
     * cooldown holds OFF. {@code minutesInState = NaN} (state age unknown, e.g. before the
     * first observed transition) leaves the desired state untouched. Safety gates run AFTER
     * this and may still shed a forced-ON — the fuse outranks the freezer, deliberately.
     */
    public static SwitchConstraint constrainOnOff(boolean wantOn, boolean isOn, double minutesInState, int minOnMin,
            int maxOnMin, int minOffMin, int maxOffMin) {
        if (Double.isNaN(minutesInState)) {
            return new SwitchConstraint(wantOn, false, "unconstrained (state age unknown)");
        }
        if (isOn) {
            if (!wantOn && minOnMin > 0 && minutesInState < minOnMin) {
                return new SwitchConstraint(true, true,
                        "min-runtime: on for " + Math.round(minutesInState) + " of " + minOnMin + " min — holding ON");
            }
            if (wantOn && maxOnMin > 0 && minutesInState >= maxOnMin) {
                return new SwitchConstraint(false, true,
                        "max-runtime: on " + Math.round(minutesInState) + " min >= " + maxOnMin + " — forcing OFF");
            }
        } else {
            if (!wantOn && maxOffMin > 0 && minutesInState >= maxOffMin) {
                return new SwitchConstraint(true, true, "max-off: off " + Math.round(minutesInState) + " min >= "
                        + maxOffMin + " — forcing ON (duty-cycle guarantee)");
            }
            if (wantOn && minOffMin > 0 && minutesInState < minOffMin) {
                return new SwitchConstraint(false, true,
                        "cooldown: off for " + Math.round(minutesInState) + " of " + minOffMin + " min — holding OFF");
            }
        }
        return new SwitchConstraint(wantOn, false, "within constraints");
    }

    /**
     * Batch/fixed-program start decision (taxonomy class 4 — the dishwasher case): should the
     * program start NOW so it runs in the cheapest <b>contiguous</b> window that still finishes by
     * {@code deadlineHour}? The program cannot be paused. Once too little time is left to keep
     * waiting ({@code span <= runtime}) the start is forced — the latest-start fallback both
     * production systems implement. With no usable price schedule the decision is deadline-only
     * (wait for the latest start); with no deadline it is always {@code false} — only live surplus
     * can start such a load (see {@link #planConsumers}). Ties between equally-cheap windows prefer
     * the earlier start (finish sooner, keep margin).
     *
     * <p>
     * The optional {@code shape} (normalized fractions of rated power per hour slot — M. Storm's
     * profile idea from the #3478 discussion) turns the flat price sum into a shape-weighted one,
     * so the start aligns the program's <em>heavy</em> hours with the cheap hours. An empty shape
     * is the rectangular profile of {@code runtimeHours}; a non-empty shape defines the runtime.
     */
    public static boolean batchStartNow(int hourNow, int deadlineHour, double runtimeHours, double[] priceSchedule24h,
            List<Double> shape) {
        if (deadlineHour < 0 || deadlineHour > 23) {
            return false;
        }
        int r = shape.isEmpty() ? Math.max(1, (int) Math.ceil(runtimeHours)) : shape.size();
        int h = Math.floorMod(hourNow, 24);
        int span = Math.floorMod(deadlineHour - h, 24);
        if (span == 0) {
            return false; // the deadline hour itself — too late to fit a run; next cycle
        }
        if (span <= r) {
            return true; // latest start reached — forced
        }
        if (priceSchedule24h.length < 24) {
            return false; // no prices: keep waiting for the latest start
        }
        int bestOffset = 0;
        double bestCost = Double.MAX_VALUE;
        for (int off = 0; off + r <= span; off++) {
            double cost = 0;
            for (int k = 0; k < r; k++) {
                double weight = shape.isEmpty() ? 1.0 : shape.get(k);
                cost += priceSchedule24h[(h + off + k) % 24] * weight;
            }
            if (cost < bestCost - 1e-9) { // strict improvement: ties keep the EARLIER start
                bestCost = cost;
                bestOffset = off;
            }
        }
        return bestOffset == 0;
    }

    /** Rectangular-profile convenience overload of {@link #batchStartNow(int, int, double, double[], List)}. */
    public static boolean batchStartNow(int hourNow, int deadlineHour, double runtimeHours, double[] priceSchedule24h) {
        return batchStartNow(hourNow, deadlineHour, runtimeHours, priceSchedule24h, List.of());
    }

    /** kW draw used to size a consumer's cheapest-window need: controllable max, else the threshold. */
    private static double ratedKw(EnergyConsumer c, double simpleLoadThresholdW) {
        if (c.profile() instanceof PowerProfile.Controllable cp && !Double.isNaN(cp.maxW()) && cp.maxW() > 0) {
            return cp.maxW() / 1000.0;
        }
        return (simpleLoadThresholdW > 0 ? simpleLoadThresholdW : 1000.0) / 1000.0;
    }

    /**
     * Baseline strategy: dispatch {@code surplusW} of spare power into the consumers.
     *
     * @param surplusW available surplus power in watts (NaN/negative treated as 0)
     * @param consumers the consumers to dispatch, in priority order
     * @param simpleLoadThresholdW surplus a simple on/off load needs before it is switched on
     * @return one action per consumer (off-actions included, so callers can release loads)
     */
    public static List<EmsAction> planSurplusDispatch(double surplusW, List<EnergyConsumer> consumers,
            double simpleLoadThresholdW) {
        List<EmsAction> actions = new ArrayList<>();
        double remaining = Double.isNaN(surplusW) ? 0.0 : Math.max(0.0, surplusW);

        for (EnergyConsumer c : consumers) {
            PowerProfile profile = c.profile();
            if (profile instanceof PowerProfile.Controllable cp) {
                double max = Double.isNaN(cp.maxW()) ? remaining : cp.maxW();
                double min = Double.isNaN(cp.minW()) ? 0.0 : cp.minW();
                double want = Math.min(max, remaining);
                if (want > 0 && want >= min) {
                    actions.add(new EmsAction(cp.itemName(), EmsAction.Kind.SET_WATTS, want,
                            String.format(Locale.ROOT, "surplus dispatch: %.0f W to %s", want, c.id())));
                    remaining -= want;
                } else {
                    actions.add(new EmsAction(cp.itemName(), EmsAction.Kind.SET_WATTS, 0.0,
                            "surplus dispatch: surplus below load minimum, off"));
                }
            } else if (profile instanceof PowerProfile.Simple sp) {
                if (remaining >= simpleLoadThresholdW) {
                    actions.add(new EmsAction(sp.itemName(), EmsAction.Kind.ONOFF, 1.0,
                            "surplus dispatch: surplus above threshold, on"));
                    remaining -= simpleLoadThresholdW;
                } else {
                    actions.add(new EmsAction(sp.itemName(), EmsAction.Kind.ONOFF, 0.0,
                            "surplus dispatch: surplus below threshold, off"));
                }
            }
        }
        return actions;
    }
}

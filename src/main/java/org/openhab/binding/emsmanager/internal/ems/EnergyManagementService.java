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
     * Unified per-consumer plan combining the two strategies into ONE coherent decision each —
     * resolving the conflict where surplus-dispatch and cheapest-window could disagree. A consumer
     * with a demand whose cheapest hour is now runs at full power (drawing from the grid in that
     * cheap hour); otherwise it is dispatched from the remaining surplus (controllable → fitting
     * watts, simple → on above the threshold). Deadline-driven loads do not consume the surplus
     * budget (they are grid-scheduled), so surplus still flows to the other consumers.
     */
    public static List<EmsAction> planConsumers(List<EnergyConsumer> consumers, double surplusW,
            double simpleLoadThresholdW, int hourNow, double[] priceSchedule24h) {
        List<EmsAction> actions = new ArrayList<>();
        double remaining = Double.isNaN(surplusW) ? 0.0 : Math.max(0.0, surplusW);
        for (EnergyConsumer c : consumers) {
            boolean demandDriven = c.demandKwh() > 0 && c.deadlineHour() >= 0 && c.deadlineHour() <= 23
                    && runNowForDeadline(hourNow, c.deadlineHour(), c.demandKwh(), ratedKw(c, simpleLoadThresholdW),
                            priceSchedule24h);
            PowerProfile profile = c.profile();
            if (profile instanceof PowerProfile.Simple sp) {
                if (demandDriven) {
                    actions.add(new EmsAction(sp.itemName(), EmsAction.Kind.ONOFF, 1.0, "deadline: cheapest hour, on"));
                } else if (remaining >= simpleLoadThresholdW) {
                    actions.add(new EmsAction(sp.itemName(), EmsAction.Kind.ONOFF, 1.0, "surplus dispatch: on"));
                    remaining -= simpleLoadThresholdW;
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
            }
        }
        return actions;
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

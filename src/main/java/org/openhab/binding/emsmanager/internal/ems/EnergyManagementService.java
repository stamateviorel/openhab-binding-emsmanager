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

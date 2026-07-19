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

import java.util.List;
import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TESTBED, not engine: compares the {@link ShadowEmsRunner engine}'s decisions against the legacy
 * controller pipeline's {@link SetpointRequest}s each tick, keeps a cumulative per-asset
 * match/total tally, and logs the parity lines. This class exists only on a site that runs the
 * engine <em>next to</em> an existing pipeline to validate a cutover — a standalone deployment of
 * the engine has no legacy decisions to compare against and never touches this class. Keeping it
 * separate keeps the engine itself free of migration scaffolding.
 *
 * <p>
 * Tally semantics: only ticks where at least one side makes an active decision are counted, so a
 * mutual "nothing to do" cannot inflate the match rate — a quiet day honestly reads "no event
 * seen" instead of a fake 100%.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class LegacyParityHarness {

    private final Logger logger = LoggerFactory.getLogger(LegacyParityHarness.class);
    // Cumulative parity tally per asset ({matchTicks, totalTicks}) — the measurable go/no-go
    // signal for a cutover: a sustained ~100% across a representative window (a sunny day with
    // surplus + a real EV session + an evening peak) is the green light.
    private final java.util.Map<String, long[]> tally = new java.util.LinkedHashMap<>();

    /** Tally one parity outcome for {@code asset}; non-{@code meaningful} ticks are not counted. */
    void record(String asset, boolean meaningful, boolean match) {
        if (!meaningful) {
            return;
        }
        long[] t = tally.computeIfAbsent(asset, k -> new long[2]);
        t[1]++;
        if (match) {
            t[0]++;
        }
    }

    /** One compact cumulative line: {@code asset match/total (pct%)} for every tracked asset. */
    void logSummary(String mode) {
        if (tally.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (var e : tally.entrySet()) {
            long ok = e.getValue()[0];
            long total = e.getValue()[1];
            double pct = total > 0 ? 100.0 * ok / total : 100.0;
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(String.format(Locale.ROOT, "%s %d/%d (%.1f%%)", e.getKey(), ok, total, pct));
        }
        logger.info("[{}]   parity cumulative: {}", mode, sb);
    }

    /**
     * Coarse safety-shed parity: does the engine want to shed load for a peak/capacity event, and
     * are the legacy safety controllers actually shedding this tick (a peak-shaving-hard /
     * -capacity PAUSE-on or boiler/airco OFF)? Not a faithful port of the legacy tiered state
     * machine — deliberately; it flags GROSS disagreement and captures evidence if a rare event
     * fires. Also emits the per-tick safety status line.
     */
    void compareSafetyShed(boolean peakShaveActive, boolean capacityExceed, boolean capacityShed,
            boolean evEcoSacrosanct, double headroomA, List<SetpointRequest> legacyDecisions, String mode,
            boolean verbose) {
        boolean legacyShed = false;
        for (SetpointRequest r : legacyDecisions) {
            boolean sheds = (r.kind() == SetpointRequest.Kind.PAUSE && r.value() > 0.5)
                    || (r.kind() == SetpointRequest.Kind.ONOFF && r.value() < 0.5);
            if (sheds && ("peak-shaving-hard".equals(r.controllerName())
                    || "peak-shaving-capacity".equals(r.controllerName()))) {
                legacyShed = true;
                break;
            }
        }
        boolean engineShed = peakShaveActive || capacityShed;
        record("safety-shed", engineShed || legacyShed, engineShed == legacyShed);
        if (verbose) {
            String capState = capacityExceed
                    ? (evEcoSacrosanct ? "ACTIVE — observe-only (ECO sacrosanct)" : "ACTIVE — load shed")
                    : "within budget";
            logger.info(
                    "[{}]   safety: breaker headroom {} A{} · peak-shaving {} · capacity-tariff {} · legacy-shed {}{}",
                    mode, Double.isInfinite(headroomA) ? "n/a" : Long.toString(Math.round(headroomA)),
                    headroomA < 6.0 ? " (LOW, gated)" : "", peakShaveActive ? "ACTIVE — load shed" : "inactive",
                    capState, legacyShed ? "ACTIVE" : "none", engineShed != legacyShed ? " — DIVERGE" : "");
        }
    }

    /**
     * Informational comparison of the engine's simple-deadline boiler intent against the legacy
     * pipeline's metered-planner decision (or the held relay state when it issued none). The
     * boiler is NOT engine-owned at cutover — the metered planner tracks delivered energy, which
     * the simple #3478 model does not — so this is tallied separately as a demo.
     */
    void compareBoilerDemo(boolean engineOn, boolean relayOn, List<SetpointRequest> legacyDecisions, String mode,
            boolean verbose) {
        Boolean legacyWants = null;
        for (SetpointRequest r : legacyDecisions) {
            if (EmsManagerBindingConstants.ASSET_BOILER.equals(r.assetId()) && r.kind() == SetpointRequest.Kind.ONOFF) {
                legacyWants = r.value() > 0;
            }
        }
        boolean legacyOn = legacyWants != null ? legacyWants : relayOn;
        String src = legacyWants != null ? "decision" : "held-state";
        record("boiler(demo)", true, engineOn == legacyOn);
        if (verbose) {
            logger.info("[{}]   parity boiler(demo, not owned): engine={} legacy={} ({}){}", mode,
                    engineOn ? "ON" : "OFF", legacyOn ? "ON" : "OFF", src,
                    engineOn != legacyOn ? " — differs (metered planner)" : " — match");
        }
    }

    /** Per-car comparison of the engine's EV decision against the legacy AMPS / CHARGE_START requests. */
    void compareEv(List<ShadowEmsRunner.EvDecision> decisions, List<SetpointRequest> legacyDecisions, String mode,
            boolean verbose) {
        for (ShadowEmsRunner.EvDecision d : decisions) {
            Integer legacyAmps = null;
            boolean legacyStart = false;
            for (SetpointRequest r : legacyDecisions) {
                if (!d.carKey().equals(r.assetId())) {
                    continue;
                }
                if (r.kind() == SetpointRequest.Kind.AMPS) {
                    legacyAmps = (int) Math.round(r.value());
                } else if (r.kind() == SetpointRequest.Kind.CHARGE_START) {
                    legacyStart = true;
                }
            }
            Integer engineAmps = d.amps();
            boolean ampsMatch = engineAmps == null ? legacyAmps == null : engineAmps.equals(legacyAmps);
            boolean match = ampsMatch && d.start() == legacyStart;
            boolean meaningful = engineAmps != null || legacyAmps != null || d.start() || legacyStart;
            record("ev:" + d.carKey(), meaningful, match);
            if (!verbose) {
                continue;
            }
            String note = "";
            if (!match && engineAmps != null && legacyAmps == null) {
                note = " (legacy: non-charging status held amps, not mirrored)";
            }
            String startTag = d.start() || legacyStart
                    ? " start[engine=" + (d.start() ? "y" : "n") + " legacy=" + (legacyStart ? "y" : "n") + "]"
                    : "";
            logger.info("[{}]   parity ev {}: engine={} legacy={}{} — {}{}{}", mode, d.carKey(),
                    engineAmps == null ? "—" : engineAmps + "A", legacyAmps == null ? "—" : legacyAmps + "A", startTag,
                    d.why(), match ? " — match" : " — DIVERGE", note);
        }
    }

    /** Comparison of the engine's battery ToU setpoint against the legacy WATTS_BATTERY request. */
    void compareBattery(@Nullable Double engineW, boolean belowReserveInEveWindow,
            List<SetpointRequest> legacyDecisions, String mode, boolean verbose) {
        Double legacyW = null;
        for (SetpointRequest r : legacyDecisions) {
            if (EmsManagerBindingConstants.ASSET_BATTERY.equals(r.assetId())
                    && r.kind() == SetpointRequest.Kind.WATTS_BATTERY) {
                legacyW = r.value();
            }
        }
        boolean match = engineW == null ? legacyW == null : legacyW != null && Math.abs(engineW - legacyW) < 1e-6;
        record("battery", engineW != null || legacyW != null, match);
        if (!verbose) {
            return;
        }
        String why = engineW == null
                ? (belowReserveInEveWindow ? "evening peak but below reserve → hold" : "passive (outside ToU windows)")
                : engineW < 0 ? "night charge" : "evening-peak discharge";
        logger.info("[{}]   parity battery: engine={} legacy={} — {}{}", mode,
                engineW == null ? "—" : Math.round(engineW) + " W", legacyW == null ? "—" : Math.round(legacyW) + " W",
                why, match ? " — match" : " — DIVERGE");
    }
}

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
package org.openhab.binding.emsmanager.internal.controller.peak;

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.openhab.binding.emsmanager.internal.core.Snapshot;

/**
 * 3-tier hard peak-shave: a progressive load-shedding safety net that engages
 * when sustained grid import crosses the peak threshold (tier engage +
 * de-escalate + master-kill + snapshot/restore). The soft pre-shave cap lives
 * in {@link SoftPeakShavingController}.
 *
 * <p>
 * State is kept in this Controller instance — survives across ticks but
 * not across bridge re-init. It could be persisted via JSONDB so a binding
 * reload doesn't drop active shed state.
 *
 * <p>
 * Trigger thresholds (defaults):
 *
 * <pre>
 *   peakThreshold     = -15 kW (sustained import → start confirmation)
 *   peakDuration      = 180 s  (confirmation window before tier 1 engage)
 *   tierInterval      =  45 s  (dwell between escalation steps)
 *   recoveryThreshold = -10 kW (better than this → de-escalate, one tier per dwell)
 * </pre>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class HardPeakShavingController implements Controller {

    public static final String NAME = "peak-shaving-hard";

    /** Status lines for the bridge channel — one per tier. */
    private static final String[] TIER_STATUS = new String[] { "Normal — protection on standby",
            "Peak shaving — ECO cars paused", "Peak shaving — ECO cars paused · boiler off",
            "Peak shaving — ECO cars paused · boiler + AC off" };

    private static final String[] TIER_DETAIL = new String[] { "Grid within safe limits. No intervention needed.",
            "ECO cars paused until the grid recovers. Fast charging (SNEL) is not interrupted — user choice respected.",
            "Boiler temporarily off alongside pausing ECO cars. Fast charging (SNEL) stays untouched.",
            "AC also temporarily off. Fast charging (SNEL) stays untouched; only the main breaker can still limit SNEL." };

    /** Status lines when ECO is sacrosanct — the ECO-pause tier is dropped, leaving boiler then airco. */
    private static final String[] TIER_STATUS_ECO_SAFE = new String[] { "Normal — protection on standby",
            "Peak shaving — boiler off (ECO protected)", "Peak shaving — boiler + AC off (ECO protected)",
            "Peak shaving — boiler + AC off (ECO protected)" };

    private static final String[] TIER_DETAIL_ECO_SAFE = new String[] {
            "Grid within safe limits. No intervention needed.",
            "Boiler temporarily off to shed load. ECO and fast (SNEL) charging both keep running — only the main breaker can still limit them.",
            "Boiler and AC temporarily off. ECO and fast (SNEL) charging both keep running — only the main breaker can still limit them.",
            "Boiler and AC temporarily off. ECO and fast (SNEL) charging both keep running — only the main breaker can still limit them." };

    private final boolean shadowMode;
    private final boolean ecoSacrosanct;

    // State across ticks.
    private volatile int level = 0;
    private volatile long levelChangedAtMs = 0L;
    private volatile long peakStartedAtMs = 0L;
    private final Map<Integer, Snapshot> snapshots = new HashMap<>();

    // Manual operator controls. Bridge tick observes the manual engage / reset
    // items and toggles these flags. The flags are consumed (cleared) by the
    // next evaluate() call.
    private volatile boolean pendingManualEngage = false;
    private volatile boolean pendingManualReset = false;

    /** Bridge sets this when the user toggles PeakShaving_Manual_Engage. */
    public void requestManualEngage() {
        pendingManualEngage = true;
    }

    /** Bridge sets this when the user toggles PeakShaving_Manual_Reset. */
    public void requestManualReset() {
        pendingManualReset = true;
    }

    public HardPeakShavingController(boolean shadowMode, boolean ecoSacrosanct) {
        this.shadowMode = shadowMode;
        this.ecoSacrosanct = ecoSacrosanct;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_HARD_PEAK_SHAVING;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean shadowMode() {
        return shadowMode;
    }

    public int level() {
        return level;
    }

    public String status() {
        int l = Math.max(0, Math.min(HARD_MAX_TIER, level));
        return (ecoSacrosanct ? TIER_STATUS_ECO_SAFE : TIER_STATUS)[l];
    }

    public String detail() {
        int l = Math.max(0, Math.min(HARD_MAX_TIER, level));
        return (ecoSacrosanct ? TIER_DETAIL_ECO_SAFE : TIER_DETAIL)[l];
    }

    /**
     * Highest tier this controller escalates to. With ECO sacrosanct the
     * ECO-pause tier is dropped, so shedding tops out at boiler + airco.
     */
    private int maxTier() {
        return ecoSacrosanct ? 2 : HARD_MAX_TIER;
    }

    @Override
    public List<SetpointRequest> evaluate(EnergyContext ctx) {
        // Use the RAW grid value (the confirmation window is the effective
        // smoothing). Smoothing is the soft controller's job.
        double grid = ctx.gridLoadRawW();
        if (Double.isNaN(grid)) {
            return List.of();
        }
        long now = ctx.tickAt().toEpochMilli();

        // Manual reset takes priority over engage.
        if (pendingManualReset) {
            pendingManualReset = false;
            pendingManualEngage = false;
            if (level > 0) {
                List<SetpointRequest> all = new ArrayList<>();
                for (int t = level; t >= 1; t--) {
                    all.addAll(emitRelease(t, ctx));
                    snapshots.remove(t);
                }
                level = 0;
                peakStartedAtMs = 0L;
                levelChangedAtMs = now;
                return all;
            }
            return List.of();
        }
        if (pendingManualEngage) {
            pendingManualEngage = false;
            if (level == 0) {
                peakStartedAtMs = now;
                return escalateTo(1, ctx, now);
            }
            // Already engaged — step up one tier if room.
            if (level < maxTier()) {
                return escalateTo(level + 1, ctx, now);
            }
        }

        // Master kill-switch — if disabled, release any active tiers and bail.
        if (!ctx.peakShavingEnabled()) {
            if (level > 0) {
                List<SetpointRequest> all = new ArrayList<>();
                for (int t = level; t >= 1; t--) {
                    all.addAll(emitRelease(t, ctx));
                    snapshots.remove(t);
                }
                level = 0;
                peakStartedAtMs = 0L;
                levelChangedAtMs = now;
                return all;
            }
            return List.of();
        }

        // Confirmation window (level still 0).
        if (level == 0) {
            if (grid < HARD_PEAK_THRESHOLD_W) {
                if (peakStartedAtMs == 0L) {
                    peakStartedAtMs = now;
                } else if (now - peakStartedAtMs > HARD_PEAK_DURATION_SEC * 1000L) {
                    return escalateTo(1, ctx, now);
                }
            } else if (peakStartedAtMs != 0L) {
                peakStartedAtMs = 0L;
            }
            return List.of();
        }

        // Already engaged. Escalate / de-escalate / hold.
        long dwell = now - levelChangedAtMs;
        if (grid < HARD_PEAK_THRESHOLD_W) {
            if (level < maxTier() && dwell > HARD_TIER_INTERVAL_SEC * 1000L) {
                return escalateTo(level + 1, ctx, now);
            }
        } else if (grid > HARD_RECOVERY_THRESHOLD_W) {
            if (dwell > HARD_TIER_INTERVAL_SEC * 1000L) {
                List<SetpointRequest> release = emitRelease(level, ctx);
                snapshots.remove(level);
                level -= 1;
                levelChangedAtMs = now;
                if (level == 0) {
                    peakStartedAtMs = 0L;
                    snapshots.clear();
                }
                return release;
            }
        }
        // Hold tier — between thresholds.
        return List.of();
    }

    /** Step the tier up to {@code target} and emit that tier's actions. */
    private List<SetpointRequest> escalateTo(int target, EnergyContext ctx, long now) {
        level = target;
        levelChangedAtMs = now;
        Snapshot snap = snapshots.get(target);
        if (snap == null) {
            snap = new Snapshot();
            snapshots.put(target, snap);
        }
        if (ecoSacrosanct) {
            // ECO sacrosanct — shed boiler, then airco; never pause cars.
            switch (target) {
                case 1:
                    return engageBoiler(snap, ctx);
                case 2:
                    return engageAirco(snap, ctx);
                default:
                    return List.of();
            }
        }
        switch (target) {
            case 1:
                return engageEcoPause(snap, ctx);
            case 2:
                return engageBoiler(snap, ctx);
            case 3:
                return engageAirco(snap, ctx);
            default:
                return List.of();
        }
    }

    /** ECO-pause tier (skipped when ECO is sacrosanct): pause ECO cars that aren't already paused. */
    private List<SetpointRequest> engageEcoPause(Snapshot snap, EnergyContext ctx) {
        List<SetpointRequest> out = new ArrayList<>();
        for (CarSnapshot car : ctx.cars().values()) {
            if (!car.cableConnected()) {
                continue;
            }
            if (car.mode() != CarSnapshot.Mode.ECO) {
                continue;
            }
            if (car.paused()) {
                continue;
            }
            String key = car.carKey() + ".pause";
            if (!snap.has(key)) {
                snap.capture(key, "OFF"); // restore to OFF on release
                out.add(new SetpointRequest(car.carKey(), SetpointRequest.Kind.PAUSE, 1.0, priority(), NAME,
                        "tier 1 — pause ECO " + car.carKey()));
            }
        }
        return out;
    }

    /** Boiler-off tier (if on). */
    private List<SetpointRequest> engageBoiler(Snapshot snap, EnergyContext ctx) {
        if (!ctx.boilerOn()) {
            return List.of();
        }
        String key = ASSET_BOILER + ".on";
        if (snap.has(key)) {
            return List.of();
        }
        snap.capture(key, "ON");
        return List.of(new SetpointRequest(ASSET_BOILER, SetpointRequest.Kind.ONOFF, 0.0, priority(), NAME,
                "tier 2 — boiler off"));
    }

    /** Airco-off tier (if on). */
    private List<SetpointRequest> engageAirco(Snapshot snap, EnergyContext ctx) {
        if (!ctx.aircoOn()) {
            return List.of();
        }
        String key = ASSET_AIRCO + ".on";
        if (snap.has(key)) {
            return List.of();
        }
        snap.capture(key, "ON");
        return List.of(new SetpointRequest(ASSET_AIRCO, SetpointRequest.Kind.ONOFF, 0.0, priority(), NAME,
                "tier 3 — airco off"));
    }

    /** Emit restore requests for everything captured at this tier. */
    private List<SetpointRequest> emitRelease(int tier, EnergyContext ctx) {
        Snapshot snap = snapshots.get(tier);
        if (snap == null || snap.size() == 0) {
            return List.of();
        }
        List<SetpointRequest> out = new ArrayList<>();
        // Iterate snapshot entries in their capture order so cars come before
        // boiler before airco (reverse of the escalation order).
        Map<String, String> entries = new LinkedHashMap<>(snap.entries());
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String key = e.getKey();
            String preValue = e.getValue();
            if (key.endsWith(".pause") && key.startsWith("car")) {
                String carKey = key.substring(0, key.indexOf('.'));
                out.add(new SetpointRequest(carKey, SetpointRequest.Kind.PAUSE, "ON".equals(preValue) ? 1.0 : 0.0,
                        priority(), NAME, "release tier " + tier + " — restore " + carKey + " pause=" + preValue));
            } else if ((ASSET_BOILER + ".on").equals(key)) {
                out.add(new SetpointRequest(ASSET_BOILER, SetpointRequest.Kind.ONOFF, "ON".equals(preValue) ? 1.0 : 0.0,
                        priority(), NAME, "release tier " + tier + " — restore boiler=" + preValue));
            } else if ((ASSET_AIRCO + ".on").equals(key)) {
                out.add(new SetpointRequest(ASSET_AIRCO, SetpointRequest.Kind.ONOFF, "ON".equals(preValue) ? 1.0 : 0.0,
                        priority(), NAME, "release tier " + tier + " — restore airco=" + preValue));
            }
        }
        return out;
    }
}

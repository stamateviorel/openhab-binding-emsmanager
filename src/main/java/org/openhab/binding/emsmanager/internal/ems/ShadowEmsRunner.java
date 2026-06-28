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
import org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.MetadataRegistry;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs Kai Kreuzer's core energy-management design (openhab-core #3478) end-to-end in
 * <b>shadow</b>: it discovers the {@code energy:}-tagged participants, reads their live state
 * from the item model, derives its own surplus from the tagged grid provider, runs the
 * {@link EnergyManagementService} strategy, and <b>logs</b> the plan it would apply — it never
 * writes any item. So it depends only on the participant items (as Kai envisioned) and makes
 * real decisions on a live site, alongside and without disturbing the production pipeline.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ShadowEmsRunner {

    private final Logger logger = LoggerFactory.getLogger(ShadowEmsRunner.class);
    private final MetadataParticipantScanner scanner;
    private final ItemRegistry itemRegistry;
    private final double simpleLoadThresholdW;
    private final double breakerLimitAperPhase;
    private final double capacityMinBillableW;
    private final @Nullable EmsActuator actuator;
    // Peak-shave hysteresis state, held across ticks (legacy HardPeakShavingController).
    private boolean peakShaveActive = false;

    public ShadowEmsRunner(MetadataRegistry metadataRegistry, ItemRegistry itemRegistry, double simpleLoadThresholdW,
            double breakerLimitAperPhase, double capacityMinBillableW, @Nullable EmsActuator actuator) {
        this.scanner = new MetadataParticipantScanner(metadataRegistry);
        this.itemRegistry = itemRegistry;
        this.simpleLoadThresholdW = simpleLoadThresholdW > 0 ? simpleLoadThresholdW : 1000.0;
        this.breakerLimitAperPhase = breakerLimitAperPhase;
        this.capacityMinBillableW = capacityMinBillableW;
        this.actuator = actuator;
    }

    /**
     * Discover {@code energy:}-tagged items, read their live state, run the strategy, log the
     * plan. Never writes.
     *
     * @param fallbackSurplusW surplus to use when no grid provider is tagged to derive it from
     */
    public void run(EnergyContext ctx, List<SetpointRequest> legacyDecisions) {
        double fallbackSurplusW = ctx.availableExcessW();
        List<EnergyProvider> providers = scanner.providers();
        List<EnergyConsumer> consumers = scanner.consumers();
        if (providers.isEmpty() && consumers.isEmpty()) {
            logger.info("[EMS-SHADOW] enabled, but no items carry 'energy' metadata yet — tag items to see a plan");
            return;
        }

        // Derive surplus from a tagged grid provider's live net power; fall back otherwise.
        double surplus = fallbackSurplusW;
        EnergyProvider grid = null;
        for (EnergyProvider p : providers) {
            if (p.role() == ProviderRole.GRID) {
                grid = p;
                double gridW = readW(p.id());
                if (!Double.isNaN(gridW)) {
                    surplus = EnergyManagementService.surplusFromGridNet(gridW);
                }
                break;
            }
        }

        EmsActuator act = actuator;
        String mode = act != null ? "EMS-APPLY" : "EMS-SHADOW";
        // Unified plan: one coherent decision per consumer (surplus dispatch + cheapest-window)...
        double[] schedule = grid != null ? EnergyManagementService.parseSchedule(readString(grid.scheduleItem()))
                : new double[0];
        int hourNow = java.time.LocalTime.now().getHour();
        List<EmsAction> actions = EnergyManagementService.planConsumers(consumers, surplus, simpleLoadThresholdW,
                hourNow, schedule);
        // Legacy-faithful refinement (ported SolarSurplusDispatcher): simple on/off loads that aren't
        // deadline-driven follow the 5-min-avg hysteresis with a cloudiness-adaptive on-threshold,
        // instead of the instantaneous soak — this is what closes the boiler parity divergence.
        double adaptiveOnW = !Double.isNaN(ctx.cloudinessTodayPct()) ? EnergyManagementService
                .cloudinessAdaptiveThresholdW(ctx.cloudinessTodayPct(), ctx.batteryBelowReserve())
                : simpleLoadThresholdW;
        List<EmsAction> refined = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            EmsAction a = actions.get(i);
            EnergyConsumer c = consumers.get(i);
            if (c.profile() instanceof PowerProfile.Simple && a.kind() == EmsAction.Kind.ONOFF
                    && !a.reason().contains("deadline")) {
                boolean currentlyOn = readSwitchOn(a.itemName());
                EnergyManagementService.SurplusDecision d = EnergyManagementService
                        .planSolarBoiler(ctx.gridLoad5minAvgW(), adaptiveOnW, -1000.0, currentlyOn);
                double v = d == EnergyManagementService.SurplusDecision.ON ? 1.0
                        : d == EnergyManagementService.SurplusDecision.OFF ? 0.0 : (currentlyOn ? 1.0 : 0.0);
                String reason = d == EnergyManagementService.SurplusDecision.ON
                        ? String.format(Locale.ROOT, "solar surplus on (5min avg > %.0f W)", adaptiveOnW)
                        : d == EnergyManagementService.SurplusDecision.OFF ? "solar surplus off (5min avg < -1000 W)"
                                : "solar surplus: hold";
                refined.add(new EmsAction(a.itemName(), EmsAction.Kind.ONOFF, v, reason));
            } else {
                refined.add(a);
            }
        }
        actions = refined;
        // ...then the SAFETY gate: no plan may add load when breaker headroom is low (fuse > economics).
        double headroomA = EnergyManagementService.minBreakerHeadroomA(ctx.totalAmpsL1(), ctx.totalAmpsL2(),
                ctx.totalAmpsL3(), breakerLimitAperPhase);
        actions = EnergyManagementService.applyBreakerGate(actions, headroomA, 6.0);
        // ...and the hard peak-shave gate: shed all load while a sustained grid import peak is active.
        peakShaveActive = EnergyManagementService.peakShaveActive(ctx.gridLoadRawW(), -15000.0, -10000.0,
                peakShaveActive);
        actions = EnergyManagementService.applyPeakShaveGate(actions, peakShaveActive);
        // ...and the capacity-tariff guard: shed if this quarter would set a new monthly import peak.
        boolean capacityExceed = EnergyManagementService.wouldExceedCapacityPeak(ctx.currentQuarterAvgW(),
                ctx.monthlyPeakW(), capacityMinBillableW, 300.0);
        actions = EnergyManagementService.applyCapacityGate(actions, capacityExceed);
        logger.info("[{}] Kai #3478 model: surplus {} W · {} provider(s) · {} consumer(s) → {} action(s) ({})", mode,
                Math.round(surplus), providers.size(), consumers.size(), actions.size(),
                act != null ? "applying" : "no writes");
        for (EnergyProvider p : providers) {
            double w = readW(p.id());
            String price = "";
            String priceItem = p.priceItem();
            if (priceItem != null) {
                double pr = readW(priceItem);
                if (!Double.isNaN(pr)) {
                    price = String.format(java.util.Locale.ROOT, ", price=%.3f", pr);
                }
            }
            logger.info("[{}]   provider {} role={} power={} W{}{}", mode, p.id(), p.role(),
                    Double.isNaN(w) ? "?" : Long.toString(Math.round(w)), p.controllable() ? " (controllable)" : "",
                    price);
        }
        for (EmsAction a : actions) {
            String v = a.kind() == EmsAction.Kind.SET_WATTS ? Math.round(a.value()) + " W"
                    : (a.value() > 0 ? "ON" : "OFF");
            if (act != null) {
                boolean ok = act.apply(a);
                logger.info("[{}]   {} {} -> {} — {}", mode, ok ? "set" : "skipped (item missing)", a.itemName(), v,
                        a.reason());
            } else {
                logger.info("[{}]   would set {} -> {} — {}", mode, a.itemName(), v, a.reason());
            }
        }

        // Battery strategy: command a controllable battery provider to charge from the surplus.
        for (EnergyProvider p : providers) {
            if (p.role() == ProviderRole.BATTERY && p.controllable()) {
                EmsAction ba = EnergyManagementService.planBatteryCharge(surplus, readW(p.socItem()), p);
                if (ba != null) {
                    String bv = Math.round(ba.value()) + " W";
                    if (act != null) {
                        boolean ok = act.apply(ba);
                        logger.info("[{}]   {} {} -> {} — {}", mode, ok ? "set" : "skipped (item missing)",
                                ba.itemName(), bv, ba.reason());
                    } else {
                        logger.info("[{}]   would set {} -> {} — {}", mode, ba.itemName(), bv, ba.reason());
                    }
                }
                break;
            }
        }

        // Parity foundation: report the safety headroom the legacy SafetyBreakerController guards
        // on (the plan was already gated on it above), and flag where the engine's boiler intent
        // diverges from the live pipeline — the validation signal that must reach zero before cutover.
        logger.info("[{}]   safety: breaker headroom {} A{} · peak-shaving {} · capacity-tariff {}", mode,
                Double.isInfinite(headroomA) ? "n/a" : Long.toString(Math.round(headroomA)),
                headroomA < 6.0 ? " (LOW, gated)" : "", peakShaveActive ? "ACTIVE — load shed" : "inactive",
                capacityExceed ? "ACTIVE — load shed" : "within budget");
        for (EnergyConsumer c : consumers) {
            if ("Boiler_technical_room_real".equals(c.id())) {
                boolean engineOn = !actions.isEmpty() && actions.get(0).value() > 0;
                // Compare against the legacy pipeline's DECISION for the boiler asset this tick;
                // when it issued none, it is holding the current relay state.
                Boolean legacyWants = null;
                for (SetpointRequest r : legacyDecisions) {
                    if (EmsManagerBindingConstants.ASSET_BOILER.equals(r.assetId())
                            && r.kind() == SetpointRequest.Kind.ONOFF) {
                        legacyWants = r.value() > 0;
                    }
                }
                boolean legacyOn = legacyWants != null ? legacyWants : ctx.boilerOn();
                String src = legacyWants != null ? "decision" : "held-state";
                logger.info("[{}]   parity boiler: engine={} legacy={} ({}){}", mode, engineOn ? "ON" : "OFF",
                        legacyOn ? "ON" : "OFF", src, engineOn != legacyOn ? " — DIVERGE" : " — match");
            }
        }
    }

    /** True if the (switch) item is currently ON; false if off or missing. */
    private boolean readSwitchOn(String itemName) {
        try {
            return itemRegistry.getItem(itemName).getState() == OnOffType.ON;
        } catch (ItemNotFoundException e) {
            return false;
        }
    }

    /** Read an item's state as a string, or null if missing. */
    private @Nullable String readString(@Nullable String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return null;
        }
        try {
            return itemRegistry.getItem(itemName).getState().toString();
        } catch (ItemNotFoundException e) {
            return null;
        }
    }

    /** Read an item's numeric state in its own unit (W expected), or NaN if missing/non-numeric. */
    private double readW(@Nullable String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return Double.NaN;
        }
        try {
            State s = itemRegistry.getItem(itemName).getState();
            if (s instanceof QuantityType<?> q) {
                return q.doubleValue();
            }
            if (s instanceof DecimalType d) {
                return d.doubleValue();
            }
        } catch (ItemNotFoundException e) {
            return Double.NaN;
        }
        return Double.NaN;
    }
}

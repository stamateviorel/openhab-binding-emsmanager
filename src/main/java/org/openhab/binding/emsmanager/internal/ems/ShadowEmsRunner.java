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
import org.openhab.binding.emsmanager.internal.core.CapabilityCheck;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
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
 * The ENGINE of Kai Kreuzer's core energy-management design (openhab-core #3478), run end-to-end
 * against the live item model: it discovers the {@code energy:}-tagged participants, reads their
 * live state, derives its own surplus from the tagged grid provider, runs the pure
 * {@link EnergyManagementService} strategies across all four consumer profile classes, applies the
 * safety gates, and produces its decisions both as {@link EmsAction}s (standalone actuation via
 * {@link EmsActuator} when {@code emsApply} is set) and as {@link SetpointRequest}s (integrated
 * actuation through the binding's existing dispatch when {@code emsOwnsDispatch} is set). In plain
 * shadow it only logs.
 *
 * <p>
 * Migration/validation scaffolding — comparing the engine's decisions against a legacy controller
 * pipeline — deliberately lives in {@link LegacyParityHarness}, not here, so the engine itself
 * stays free of site-migration concerns.
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
    private final double gridSafetyMarginW;
    // When true, the site keeps ECO charging sacrosanct: capacity-tariff is observe-only and never
    // sheds (mirrors CapacityTariffShavingController under evEcoSacrosanct). The engine must respect
    // the same policy so it doesn't shed load the legacy deliberately holds.
    private final boolean evEcoSacrosanct;
    // Item name of the simple consumer to run the informational boiler(demo) parity against, or
    // empty to skip. Site configuration — never hardcoded.
    private final String parityBoilerItem;
    // Soft production-shave band, shared with the legacy controller (identical parameters both sides).
    private final SoftShaveBand softBand;
    private final LevelWindows levelWindows;
    private final boolean levelSeasonalAuto;
    // Site EV electrical parameters (phases / phase voltage / max amps), shared with the legacy coordinator.
    private final EvElectrical evElectrical;
    private final @Nullable EmsActuator actuator;
    // Testbed: decision-vs-decision comparison against the legacy pipeline (see class javadoc).
    private final LegacyParityHarness parity = new LegacyParityHarness();
    // Peak-shave hysteresis state, held across ticks (legacy HardPeakShavingController).
    private boolean peakShaveActive = false;
    // Per-car last-sent amps, held across ticks — drives EV ramp + hysteresis (legacy
    // EvCoordinatorController.lastSentAmps).
    private final java.util.Map<String, Integer> evLastSentAmps = new java.util.HashMap<>();
    // Per-car consecutive auto-RemoteStart attempts, held across ticks (legacy chargeStartAttempts) —
    // drives the wedged-charger backoff.
    private final java.util.Map<String, Integer> evChargeStartAttempts = new java.util.HashMap<>();
    // One start per calendar day per batch item (reference simplification of FINISHED-state
    // tracking): latched on an emitted start OR on observing the program running.
    private final java.util.Map<String, java.time.LocalDate> batchStartedOn = new java.util.HashMap<>();
    // Observed on/off state + the instant it last changed, per simple-load item — feeds the
    // {min,max}x{on,off} protection constraints (state age unknown until the first transition).
    private final java.util.Map<String, Boolean> switchLastState = new java.util.HashMap<>();
    private final java.util.Map<String, java.time.Instant> switchSince = new java.util.HashMap<>();
    private volatile int lastEnergyLevel = 1;
    // Dynamic soft ECO cap (legacy SoftPeakShavingController) — sticky hysteresis band on the ECO
    // charging cap, held across ticks and updated from the smoothed grid each EV pass.
    private int evSoftCapA;

    // Auto-RemoteStart backoff constants (legacy EvCoordinatorController).
    private static final int EV_CHARGE_START_MAX_ATTEMPTS = 5;
    private static final int EV_CHARGE_START_SLOW_RETRY_TICKS = 20;
    // Identity + priority the engine stamps on the SetpointRequests it produces for the dispatch path.
    // Priority sits in the dispatch band (>50) so a legacy SAFETY request (breaker=10, capacity=30,
    // hard-peak=40, soft=50) always outranks it when both target the same asset+kind at cutover.
    private static final String ENGINE_CONTROLLER_NAME = "kai-ems-engine";
    private static final int ENGINE_PRIORITY = EmsManagerBindingConstants.PRIO_EV_COORDINATOR;

    // Time-of-use battery schedule (legacy BatteryTouDispatcher).
    private static final int BAT_NIGHT_START_HOUR = 2;
    private static final int BAT_NIGHT_END_HOUR = 6;
    private static final int BAT_EVE_START_HOUR = 17;
    private static final int BAT_EVE_END_HOUR = 21;
    private static final double BAT_CHARGE_RATE_W = -2000.0;
    private static final double BAT_DISCHARGE_RATE_W = 2000.0;

    /**
     * One car's engine decision this tick: the AMPS target ({@code null} = no amps request), the
     * PAUSE request ({@code null} = none, 1.0 = pause, 0.0 = resume), whether a RemoteStart is
     * emitted, and a short human reason. Consumed by the request emission and by the parity harness.
     */
    public record EvDecision(String carKey, @Nullable Integer amps, @Nullable Double pause, boolean start, String why) {
    }

    public ShadowEmsRunner(MetadataRegistry metadataRegistry, ItemRegistry itemRegistry, double simpleLoadThresholdW,
            double breakerLimitAperPhase, double capacityMinBillableW, double gridSafetyMarginW,
            boolean evEcoSacrosanct, String parityBoilerItem, SoftShaveBand softBand, EvElectrical evElectrical,
            LevelWindows levelWindows, boolean levelSeasonalAuto,
            java.util.Collection<EnergyParticipantProvider> participantProviders, @Nullable EmsActuator actuator) {
        this.levelWindows = levelWindows.sanitized();
        this.levelSeasonalAuto = levelSeasonalAuto;
        this.scanner = new MetadataParticipantScanner(metadataRegistry, () -> participantProviders);
        this.itemRegistry = itemRegistry;
        this.simpleLoadThresholdW = simpleLoadThresholdW > 0 ? simpleLoadThresholdW : 1000.0;
        this.breakerLimitAperPhase = breakerLimitAperPhase;
        this.capacityMinBillableW = capacityMinBillableW;
        this.gridSafetyMarginW = gridSafetyMarginW;
        this.evEcoSacrosanct = evEcoSacrosanct;
        this.parityBoilerItem = parityBoilerItem;
        this.softBand = softBand;
        this.evElectrical = evElectrical;
        this.evSoftCapA = softBand.normalCapA();
        this.actuator = actuator;
    }

    /** The site energy level (0..3) computed on the last {@link #run}; for the MainUI hero + rules. */
    public int lastEnergyLevel() {
        return lastEnergyLevel;
    }

    /** Canonical text for a site energy level 0..3 (restricted/normal/encouraged/maximum). */
    public static String levelText(int level) {
        return switch (level) {
            case 0 -> "restricted";
            case 2 -> "encouraged";
            case 3 -> "maximum";
            default -> "normal";
        };
    }

    /**
     * Override each Simple consumer's {@code runAtLevel} from its live level-control item (0..4) —
     * the interactive per-device control the user sets in the MainUI Energy section. Absent/unset
     * item = keep the metadata default. Non-Simple consumers are untouched.
     */
    private List<EnergyConsumer> applyLevelOverrides(List<EnergyConsumer> consumers) {
        List<EnergyConsumer> out = new ArrayList<>(consumers.size());
        for (EnergyConsumer c : consumers) {
            Integer override = readLevelOverride(c.id());
            if (override != null && c.profile() instanceof PowerProfile.Simple sp) {
                PowerProfile.Simple ov = new PowerProfile.Simple(sp.itemName(), sp.thresholdW(), override.intValue(),
                        sp.minOnMinutes(), sp.maxOnMinutes(), sp.minOffMinutes(), sp.maxOffMinutes());
                out.add(new EnergyConsumer(c.id(), ov, c.demandKwh(), c.deadlineHour(), c.measureItem(), c.readyItem(),
                        c.priority()));
            } else {
                out.add(c);
            }
        }
        return out;
    }

    private @Nullable Integer readLevelOverride(String consumerId) {
        org.openhab.core.items.Item item = itemRegistry.get(EnergyManagementService.levelControlItem(consumerId));
        if (item != null) {
            State state = item.getState();
            if (state instanceof DecimalType d) {
                int v = d.intValue();
                if (v >= 0 && v <= 4) {
                    return Integer.valueOf(v);
                }
            }
        }
        return null;
    }

    /**
     * Discover {@code energy:}-tagged items, read their live state, run the strategies, and (in
     * shadow) log the plan or (in apply) actuate it. This is called <b>every</b> bridge tick so the
     * engine's stateful hysteresis — EV ramp/hysteresis, the peak-shave band, the sticky soft ECO
     * cap — evolves at the real control cadence and the parity tally samples every tick. To keep the
     * log readable, the human-facing lines are emitted only when {@code verbose} (roughly once a
     * minute); decisions, state updates and the tally are computed unconditionally.
     *
     * @param verbose whether to emit the per-tick human-facing log lines this tick
     * @return the engine's decisions as {@link SetpointRequest}s (per-car EV AMPS/PAUSE/CHARGE_START,
     *         battery WATTS_BATTERY) — the payload for the dispatch path at cutover. In shadow this
     *         is returned but not dispatched; the caller decides.
     */
    public List<SetpointRequest> run(EnergyContext ctx, List<SetpointRequest> legacyDecisions, boolean verbose) {
        List<SetpointRequest> engineRequests = new ArrayList<>();
        double fallbackSurplusW = ctx.availableExcessW();
        List<EnergyProvider> providers = scanner.providers();
        List<EnergyConsumer> consumers = applyLevelOverrides(
                EnergyManagementService.sortByPriority(scanner.consumers()));
        if (providers.isEmpty() && consumers.isEmpty()) {
            if (verbose) {
                logger.info("[EMS-SHADOW] enabled, but no items carry 'energy' metadata yet — tag items to see a plan");
            }
            return engineRequests;
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
        // Unified plan: one coherent decision per consumer, across all four profile classes...
        double[] schedule = grid != null ? EnergyManagementService.parseSchedule(readString(grid.scheduleItem()))
                : new double[0];
        int hourNow = java.time.LocalTime.now().getHour();
        LevelWindows lw = levelSeasonalAuto ? LevelWindows.seasonal(java.time.LocalDate.now().getMonthValue())
                : levelWindows;
        List<EmsAction> actions = EnergyManagementService.planConsumers(consumers, surplus, simpleLoadThresholdW,
                hourNow, schedule, lw);
        // Live-state refinements the pure plan cannot know:
        // - simple loads that aren't deadline-driven follow the 5-min-avg hysteresis with a
        // cloudiness-adaptive on-threshold instead of the instantaneous soak (avoids relay flap);
        // - a batch program that is ALREADY RUNNING is never re-commanded (start-once semantics).
        double adaptiveOnW = !Double.isNaN(ctx.cloudinessTodayPct()) ? EnergyManagementService
                .cloudinessAdaptiveThresholdW(ctx.cloudinessTodayPct(), ctx.batteryBelowReserve())
                : simpleLoadThresholdW;
        List<EmsAction> refined = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            EmsAction a = actions.get(i);
            EnergyConsumer c = consumers.get(i);
            // Readiness interlock (storm.house's startklar): while the ready-item is not ON, the
            // load is never activated — a start/on/encourage becomes off/hold.
            String readyItem = c.readyItem();
            if (readyItem != null && !readSwitchOn(readyItem) && a.value() > 0) {
                refined.add(new EmsAction(a.itemName(),
                        a.kind() == EmsAction.Kind.ONOFF && c.profile() instanceof PowerProfile.Batch
                                ? EmsAction.Kind.HOLD
                                : a.kind(),
                        0.0, "not ready (" + readyItem + " off)"));
                continue;
            }
            // Refine only NON-deadline-driven simple loads through the solar-surplus hysteresis; the
            // deadline-driven action reason begins "deadline:" (use startsWith, not contains — the
            // idle reason "idle: no surplus, deadline not due" also mentions the word "deadline").
            if (c.profile() instanceof PowerProfile.Simple && a.kind() == EmsAction.Kind.ONOFF
                    && !a.reason().startsWith("deadline")) {
                boolean currentlyOn = readSwitchOn(a.itemName());
                EnergyManagementService.SurplusDecision d = EnergyManagementService
                        .planSolarBoiler(ctx.gridLoad5minAvgW(), adaptiveOnW, -1000.0, currentlyOn);
                double v = d == EnergyManagementService.SurplusDecision.ON ? 1.0
                        : d == EnergyManagementService.SurplusDecision.OFF ? 0.0 : (currentlyOn ? 1.0 : 0.0);
                String reason = d == EnergyManagementService.SurplusDecision.ON
                        ? String.format(Locale.ROOT, "solar surplus on (5min avg > %.0f W)", adaptiveOnW)
                        : d == EnergyManagementService.SurplusDecision.OFF ? "solar surplus off (5min avg < -1000 W)"
                                : "solar surplus: hold";
                // Protection constraints ({min,max}x{on,off}) on the desired state — min/max
                // runtime, cooldown, and the fridge max-off duty-cycle guarantee.
                if (c.profile() instanceof PowerProfile.Simple sp && (sp.minOnMinutes() > 0 || sp.maxOnMinutes() > 0
                        || sp.minOffMinutes() > 0 || sp.maxOffMinutes() > 0)) {
                    EnergyManagementService.SwitchConstraint sc = EnergyManagementService.constrainOnOff(v > 0,
                            currentlyOn, switchStateAgeMinutes(a.itemName(), currentlyOn), sp.minOnMinutes(),
                            sp.maxOnMinutes(), sp.minOffMinutes(), sp.maxOffMinutes());
                    if (sc.overridden()) {
                        v = sc.on() ? 1.0 : 0.0;
                        reason = sc.reason();
                    }
                }
                refined.add(new EmsAction(a.itemName(), EmsAction.Kind.ONOFF, v, reason));
            } else if (c.profile() instanceof PowerProfile.Batch) {
                boolean start = a.kind() == EmsAction.Kind.ONOFF && a.value() > 0;
                java.time.LocalDate today = java.time.LocalDate.now();
                if (isBatchRunning(c, a.itemName())) {
                    batchStartedOn.put(a.itemName(), today); // observed running (any starter) → latch today
                    refined.add(new EmsAction(a.itemName(), EmsAction.Kind.HOLD, 0.0, "batch: already running — hold"));
                } else if (start && today.equals(batchStartedOn.get(a.itemName()))) {
                    refined.add(new EmsAction(a.itemName(), EmsAction.Kind.HOLD, 0.0,
                            "batch: already ran today — hold (one start per day)"));
                } else {
                    refined.add(a);
                }
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
        // ...and the capacity-tariff guard: shed if this quarter would set a new monthly import peak —
        // BUT only when the site does not keep ECO sacrosanct. Under evEcoSacrosanct the legacy
        // capacity-tariff controller is observe-only (never sheds), so the engine must be too.
        boolean capacityExceed = EnergyManagementService.wouldExceedCapacityPeak(ctx.currentQuarterAvgW(),
                ctx.monthlyPeakW(), capacityMinBillableW, 300.0);
        boolean capacityShed = capacityExceed && !evEcoSacrosanct;
        actions = EnergyManagementService.applyCapacityGate(actions, capacityShed);
        // A shed gate strips a SET_MODE's string; for a ModeControllable load "shed" must mean the
        // MOST-RESTRICTED mode, not silence — remap so the restrictive mode is actually commanded.
        for (int i = 0; i < actions.size(); i++) {
            EmsAction a = actions.get(i);
            if (a.kind() == EmsAction.Kind.SET_MODE && a.stringValue() == null
                    && consumers.get(i).profile() instanceof PowerProfile.ModeControllable mp) {
                actions.set(i, new EmsAction(a.itemName(), EmsAction.Kind.SET_MODE, 0, mp.modes().get(0),
                        a.reason() + " → " + mp.modes().get(0)));
            }
        }
        int siteLevel = EnergyManagementService.energyLevel(surplus, simpleLoadThresholdW, false,
                EnergyManagementService.pricePercentileLevel(hourNow, schedule, lw));
        this.lastEnergyLevel = siteLevel;
        if (verbose) {
            logger.info("[{}]   energy level {}/3 ({})", mode, siteLevel, levelText(siteLevel));
            logger.info("[{}] Kai #3478 model: surplus {} W · {} provider(s) · {} consumer(s) → {} action(s) ({})",
                    mode, Math.round(surplus), providers.size(), consumers.size(), actions.size(),
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
        }
        // Actuate always (when out of shadow); log only when verbose. HOLD actions are deliberate
        // no-ops: nothing is sent. A surviving batch start latches the one-start-per-day memory.
        for (int ai = 0; ai < actions.size(); ai++) {
            EmsAction a = actions.get(ai);
            if (a.kind() == EmsAction.Kind.ONOFF && a.value() > 0
                    && consumers.get(ai).profile() instanceof PowerProfile.Batch) {
                batchStartedOn.put(a.itemName(), java.time.LocalDate.now());
            }
            if (a.kind() == EmsAction.Kind.HOLD || (a.kind() == EmsAction.Kind.SET_MODE && a.stringValue() == null)) {
                if (verbose) {
                    logger.info("[{}]   hold {} — {}", mode, a.itemName(), a.reason());
                }
                continue;
            }
            String v;
            switch (a.kind()) {
                case SET_WATTS:
                    v = Math.round(a.value()) + " W";
                    break;
                case SET_MODE:
                    v = String.valueOf(a.stringValue());
                    break;
                default:
                    v = a.value() > 0 ? "ON" : "OFF";
                    break;
            }
            if (act != null) {
                boolean ok = act.apply(a);
                if (verbose) {
                    logger.info("[{}]   {} {} -> {} — {}", mode, ok ? "set" : "skipped (item missing)", a.itemName(), v,
                            a.reason());
                }
            } else if (verbose) {
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
                        if (verbose) {
                            logger.info("[{}]   {} {} -> {} — {}", mode, ok ? "set" : "skipped (item missing)",
                                    ba.itemName(), bv, ba.reason());
                        }
                    } else if (verbose) {
                        logger.info("[{}]   would set {} -> {} — {}", mode, ba.itemName(), bv, ba.reason());
                    }
                }
                break;
            }
        }

        // ---- Testbed from here: compare the engine's decisions against the legacy pipeline. ----
        parity.compareSafetyShed(peakShaveActive, capacityExceed, capacityShed, evEcoSacrosanct, headroomA,
                legacyDecisions, mode, verbose);
        if (!parityBoilerItem.isBlank()) {
            for (int i = 0; i < consumers.size(); i++) {
                if (parityBoilerItem.equals(consumers.get(i).id()) && i < actions.size()) {
                    parity.compareBoilerDemo(actions.get(i).value() > 0, ctx.boilerOn(), legacyDecisions, mode,
                            verbose);
                    break;
                }
            }
        }

        // EV coordination (ported EvCoordinatorController): decide per car, emit requests, compare.
        List<EvDecision> evDecisions = decideEvs(ctx);
        for (EvDecision d : evDecisions) {
            Integer amps = d.amps();
            Double pause = d.pause();
            if (amps != null) {
                engineRequests.add(new SetpointRequest(d.carKey(), SetpointRequest.Kind.AMPS, amps, ENGINE_PRIORITY,
                        ENGINE_CONTROLLER_NAME, d.why()));
            }
            if (pause != null) {
                engineRequests.add(new SetpointRequest(d.carKey(), SetpointRequest.Kind.PAUSE, pause, ENGINE_PRIORITY,
                        ENGINE_CONTROLLER_NAME, pause > 0.5 ? "engine: pause" : "engine: resume"));
            }
            if (d.start()) {
                engineRequests.add(new SetpointRequest(d.carKey(), SetpointRequest.Kind.CHARGE_START, 1.0,
                        ENGINE_PRIORITY, ENGINE_CONTROLLER_NAME, "engine: auto-RemoteStart"));
            }
        }
        parity.compareEv(evDecisions, legacyDecisions, mode, verbose);

        // Battery time-of-use dispatch (ported BatteryTouDispatcher): decide, emit, compare.
        int hour = java.time.ZonedDateTime.ofInstant(ctx.tickAt(), java.time.ZoneId.systemDefault()).getHour();
        Double batteryW = EnergyManagementService.batteryTouSetpointW(hour, ctx.batteryBelowReserve(),
                BAT_NIGHT_START_HOUR, BAT_NIGHT_END_HOUR, BAT_EVE_START_HOUR, BAT_EVE_END_HOUR, BAT_CHARGE_RATE_W,
                BAT_DISCHARGE_RATE_W);
        if (batteryW != null) {
            engineRequests.add(new SetpointRequest(EmsManagerBindingConstants.ASSET_BATTERY,
                    SetpointRequest.Kind.WATTS_BATTERY, batteryW, ENGINE_PRIORITY, ENGINE_CONTROLLER_NAME,
                    batteryW < 0 ? "engine: night charge" : "engine: evening-peak discharge"));
        }
        boolean belowReserveInEve = ctx.batteryBelowReserve() && hour >= BAT_EVE_START_HOUR && hour < BAT_EVE_END_HOUR;
        parity.compareBattery(batteryW, belowReserveInEve, legacyDecisions, mode, verbose);

        if (verbose) {
            parity.logSummary(mode);
            logger.info("[{}]   dispatch-ready: {} engine SetpointRequest(s) (EV + battery) — {}", mode,
                    engineRequests.size(), actuator != null ? "engine owns dispatch" : "shadow (not dispatched)");
        }
        return engineRequests;
    }

    /**
     * The ported {@code EvCoordinatorController} per-car decision: shared ECO budget across the
     * active cable-connected non-SNEL cars, per-car breaker headroom, SNEL/ECO target amps (ECO
     * floored at the minimum — the "never auto-pauses" invariant), ramp + hysteresis with per-car
     * last-sent amps held across ticks, modbus-stale → MIN, low headroom → pause, hard peak-shave →
     * defer, ECO external-pause respect, and the auto-RemoteStart wedged-charger backoff. The sticky
     * soft-ECO-cap band is updated here from the smoothed grid.
     */
    private List<EvDecision> decideEvs(EnergyContext ctx) {
        List<EvDecision> out = new ArrayList<>();
        java.util.Collection<CarSnapshot> cars = ctx.cars().values();
        if (cars.isEmpty()) {
            return out;
        }
        // Shared ECO budget across active (cable-in, non-SNEL/OFF) cars — legacy computeEcoBudgetPerCarW.
        int activeEco = 0;
        double totalEcoDrawW = 0.0;
        for (CarSnapshot car : cars) {
            if (car.cableConnected() && car.mode() != CarSnapshot.Mode.SNEL && car.mode() != CarSnapshot.Mode.OFF) {
                activeEco++;
                totalEcoDrawW += car.liveDrawW();
            }
        }
        double ecoBudgetW = EnergyManagementService.ecoBudgetPerCarW(ctx.gridLoadSmoothedW(), totalEcoDrawW, activeEco,
                gridSafetyMarginW);
        // Update the sticky soft ECO cap from the smoothed grid (legacy SoftPeakShavingController).
        evSoftCapA = EnergyManagementService.softEcoCapA(ctx.gridLoadSmoothedW(), evSoftCapA, softBand.thresholdW(),
                softBand.recoveryW(), softBand.lowCapA(), softBand.normalCapA());

        for (CarSnapshot car : cars) {
            String key = car.carKey();
            Integer engineAmps;
            Double enginePause = null;
            boolean engineStart = false;
            String why;
            if (!car.cableConnected() || car.mode() == CarSnapshot.Mode.OFF) {
                engineAmps = null;
                why = "no cable / OFF";
                evLastSentAmps.remove(key);
                evChargeStartAttempts.remove(key); // cable-unplug clears the backoff counter (legacy)
            } else if (!ctx.modbusFresh()) {
                engineAmps = CapabilityCheck.MIN_CHARGING_CURRENT_A;
                why = "modbus stale → MIN";
                evLastSentAmps.put(key, engineAmps);
            } else {
                int headroom = CapabilityCheck.breakerHeadroomA(car.ampsL1(), car.ampsL2(), car.ampsL3(),
                        ctx.totalAmpsL1(), ctx.totalAmpsL2(), ctx.totalAmpsL3(),
                        breakerLimitAperPhase - CapabilityCheck.BREAKER_HEADROOM_A, evElectrical.maxChargeCurrentA());
                if (headroom < CapabilityCheck.MIN_CHARGING_CURRENT_A) {
                    engineAmps = null; // legacy emits PAUSE here, not an AMPS setpoint
                    enginePause = 1.0;
                    why = "breaker headroom " + headroom + "A < MIN → pause (no amps)";
                    evLastSentAmps.remove(key);
                } else if (peakShaveActive) {
                    engineAmps = null; // hard peak-shave: defer entirely
                    why = "peak-shaving active → defer";
                } else if (car.mode() != CarSnapshot.Mode.SNEL && car.paused()) {
                    // External-pause respect (ECO only; SNEL is exempt). The engine never sets a pause,
                    // so a pause it sees is always external → leave the car alone (legacy returns).
                    engineAmps = null;
                    why = "ECO external-pause respected (no amps)";
                } else {
                    // Auto-RemoteStart backoff — may declare the car wedged and suppress amps this tick.
                    int prevAttempts = evChargeStartAttempts.getOrDefault(key, 0);
                    EnergyManagementService.RemoteStartDecision rs = EnergyManagementService.remoteStartDecision(
                            car.ocppStatus(), prevAttempts, EV_CHARGE_START_MAX_ATTEMPTS,
                            EV_CHARGE_START_SLOW_RETRY_TICKS);
                    evChargeStartAttempts.put(key, rs.newAttempts());
                    engineStart = rs.emitStart();
                    if (rs.suppressAmps()) {
                        engineAmps = null; // wedged charger: slow-retry start only, nothing else
                        why = "RemoteStart wedged (attempt " + rs.newAttempts() + ") → backoff, no amps";
                    } else {
                        int target = EnergyManagementService.evChargeTargetAmps(car.mode(), headroom, ecoBudgetW,
                                evSoftCapA, evElectrical.phases(), evElectrical.phaseVoltage(),
                                CapabilityCheck.MIN_CHARGING_CURRENT_A, evElectrical.maxChargeCurrentA());
                        Integer prev = evLastSentAmps.get(key);
                        int result;
                        if (prev == null) {
                            result = target; // first tick: no ramp / hysteresis (legacy prev==null path)
                        } else {
                            int ramped = EnergyManagementService.rampLimitAmps(target, prev,
                                    EmsManagerBindingConstants.RAMP_UP_STEP_A);
                            // Safety drops to MIN always pass through the hysteresis band (legacy).
                            result = ramped == CapabilityCheck.MIN_CHARGING_CURRENT_A ? ramped
                                    : EnergyManagementService.applyHysteresisAmps(ramped, prev,
                                            EmsManagerBindingConstants.HYSTERESIS_A);
                        }
                        evLastSentAmps.put(key, result);
                        engineAmps = result;
                        enginePause = 0.0; // charging → clear/resume any pause (legacy emits PAUSE=0 here)
                        boolean eco = car.mode() != CarSnapshot.Mode.SNEL;
                        why = (eco ? "ECO" : "SNEL")
                                + (eco && !Double.isNaN(ecoBudgetW) ? " budget=" + Math.round(ecoBudgetW) + "W" : "")
                                + " (headroom=" + headroom + "A)" + (engineStart ? " · +RemoteStart" : "");
                    }
                }
            }
            out.add(new EvDecision(key, engineAmps, enginePause, engineStart, why));
        }
        return out;
    }

    // A batch load with a measured-power item is considered RUNNING above this draw — measured
    // feedback beats switch state (a dishwasher's switch may stay ON; its power tells the truth).
    private static final double BATCH_RUNNING_MIN_W = 25.0;

    /**
     * Is a batch program currently running? Prefers the consumer's measured-power item
     * ({@code measure=} metadata — the autonomy principle from #3478: plan on measured, not
     * commanded); falls back to the control item's ON state when no measurement is tagged.
     */
    private boolean isBatchRunning(EnergyConsumer c, String itemName) {
        String measure = c.measureItem();
        if (measure != null) {
            double w = readW(measure);
            return !Double.isNaN(w) && w > BATCH_RUNNING_MIN_W;
        }
        return readSwitchOn(itemName);
    }

    /**
     * Minutes the item has been in its current observed on/off state, or NaN until the first
     * transition has been observed (constraints stay inactive on unknown age).
     */
    private double switchStateAgeMinutes(String itemName, boolean currentState) {
        Boolean last = switchLastState.put(itemName, currentState);
        if (last == null || last != currentState) {
            switchSince.put(itemName, java.time.Instant.now());
            return last == null ? Double.NaN : 0.0;
        }
        java.time.Instant since = switchSince.get(itemName);
        return since == null ? Double.NaN : (java.time.Instant.now().toEpochMilli() - since.toEpochMilli()) / 60000.0;
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

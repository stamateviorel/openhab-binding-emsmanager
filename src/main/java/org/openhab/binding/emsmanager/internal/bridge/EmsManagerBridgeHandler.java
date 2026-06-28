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
package org.openhab.binding.emsmanager.internal.bridge;

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.emsmanager.internal.asset.AircoAssetHandler;
import org.openhab.binding.emsmanager.internal.asset.AssetHandler;
import org.openhab.binding.emsmanager.internal.asset.BatteryAssetHandler;
import org.openhab.binding.emsmanager.internal.asset.BoilerAssetHandler;
import org.openhab.binding.emsmanager.internal.asset.ChargerAssetHandler;
import org.openhab.binding.emsmanager.internal.config.BatteryConfig;
import org.openhab.binding.emsmanager.internal.config.EmsBridgeConfig;
import org.openhab.binding.emsmanager.internal.controller.analytics.Co2TrackingController;
import org.openhab.binding.emsmanager.internal.controller.analytics.CostAnalyticsController;
import org.openhab.binding.emsmanager.internal.controller.analytics.LongTermStatsController;
import org.openhab.binding.emsmanager.internal.controller.capacity.CapacityTariffShavingController;
import org.openhab.binding.emsmanager.internal.controller.dispatch.BatteryTouDispatcher;
import org.openhab.binding.emsmanager.internal.controller.dispatch.BoilerPlanController;
import org.openhab.binding.emsmanager.internal.controller.dispatch.BoilerScheduleController;
import org.openhab.binding.emsmanager.internal.controller.dispatch.HeatPumpOptimizerController;
import org.openhab.binding.emsmanager.internal.controller.dispatch.ProductionShavingDispatcher;
import org.openhab.binding.emsmanager.internal.controller.dispatch.SelfConsumptionOptimizer;
import org.openhab.binding.emsmanager.internal.controller.dispatch.SolarSurplusDispatcher;
import org.openhab.binding.emsmanager.internal.controller.ev.EvChargingPlanController;
import org.openhab.binding.emsmanager.internal.controller.ev.EvCoordinatorController;
import org.openhab.binding.emsmanager.internal.controller.peak.HardPeakShavingController;
import org.openhab.binding.emsmanager.internal.controller.peak.SoftPeakShavingController;
import org.openhab.binding.emsmanager.internal.controller.safety.SafetyBreakerController;
import org.openhab.binding.emsmanager.internal.core.CapacityTariffTracker;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.ContextBuilder;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.EwmaFilter;
import org.openhab.binding.emsmanager.internal.core.PriorityScheduler;
import org.openhab.binding.emsmanager.internal.core.RollingAverage;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.openhab.binding.emsmanager.internal.devicemeter.DeviceMeterHandler;
import org.openhab.binding.emsmanager.internal.ems.EmsActuator;
import org.openhab.binding.emsmanager.internal.ems.ShadowEmsRunner;
import org.openhab.binding.emsmanager.internal.report.WeeklyReportService;
import org.openhab.binding.emsmanager.internal.sizing.BatterySizingService;
import org.openhab.binding.emsmanager.internal.tariff.compare.TariffComparisonService;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.GenericItem;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.MetadataRegistry;
import org.openhab.core.items.StateChangeListener;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.persistence.PersistenceServiceRegistry;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridge handler. Drives the per-tick dispatch step — when bridge
 * {@code shadowMode = false}, asset handlers actually write. Can optionally
 * mirror-write a set of {@code PeakShaving_*} items so externally-defined
 * widgets keep working.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EmsManagerBridgeHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(EmsManagerBridgeHandler.class);

    private final ItemRegistry itemRegistry;
    private final MetadataRegistry metadataRegistry;
    private final EventPublisher eventPublisher;
    private final ThingRegistry thingRegistry;
    private final @Nullable PersistenceServiceRegistry persistenceRegistry;
    private @Nullable BatterySizingService sizingService;
    private @Nullable TariffComparisonService tariffComparisonService;
    private final WeeklyReportService weeklyReportService;
    private @Nullable LocalDate lastWeeklyReportDate;
    private @Nullable LocalDate lastAnalyticsDate;
    private @Nullable ScheduledFuture<?> tickJob;
    private final AtomicLong tickCounter = new AtomicLong(0);
    // Fast-tick: a Mode/Cable/Pause change on any car schedules a debounced tick
    // so ECO/SNEL flips re-evaluate in ~1 s instead of waiting for the next
    // periodic tick. The periodic tick stays as the fallback.
    private static final long DEBOUNCE_MS = 500L;
    private @Nullable ScheduledFuture<?> debouncedTickFuture;
    private final List<GenericItem> watchedItems = new ArrayList<>();
    private @Nullable StateChangeListener kickListener;
    // Guards tick() against the periodic and debounced invocations overlapping —
    // controller state (EWMA, capacity tracker, dedupe) is not re-entrant.
    private final Object tickLock = new Object();
    private final PriorityScheduler controllerScheduler = new PriorityScheduler();
    private final Map<String, AssetHandler> assets = new HashMap<>();
    private volatile boolean shadowMode = true;
    private volatile boolean publishLegacyMirrorItems = false;

    private @Nullable EwmaFilter gridEwma;
    private @Nullable RollingAverage grid5minAvg;
    private @Nullable ContextBuilder contextBuilder;
    private @Nullable ShadowEmsRunner shadowEms;
    private @Nullable SoftPeakShavingController softPeakShaving;
    private @Nullable HardPeakShavingController hardPeakShaving;
    private @Nullable SolarSurplusDispatcher solarSurplus;
    private @Nullable EvCoordinatorController evCoordinator;
    private @Nullable CapacityTariffShavingController capacityTariff;
    private @Nullable CapacityTariffTracker capacityTracker;
    private @Nullable SelfConsumptionOptimizer optimizer;

    private final @Nullable HttpClient httpClient;

    public EmsManagerBridgeHandler(Bridge bridge, ItemRegistry itemRegistry, MetadataRegistry metadataRegistry,
            EventPublisher eventPublisher, ThingRegistry thingRegistry,
            @Nullable PersistenceServiceRegistry persistenceRegistry, @Nullable HttpClient httpClient) {
        super(bridge);
        this.itemRegistry = itemRegistry;
        this.metadataRegistry = metadataRegistry;
        this.eventPublisher = eventPublisher;
        this.thingRegistry = thingRegistry;
        this.persistenceRegistry = persistenceRegistry;
        this.httpClient = httpClient;
        this.weeklyReportService = new WeeklyReportService(itemRegistry);
    }

    @Override
    public void initialize() {
        EmsBridgeConfig config = getConfigAs(EmsBridgeConfig.class);
        shadowMode = config.shadowMode;
        publishLegacyMirrorItems = config.publishLegacyMirrorItems;

        // Energy-management engine (Kai Kreuzer's core design, openhab-core #3478). Discovers
        // `energy`-tagged items and logs the plan; only writes when emsApply is also set.
        EmsActuator actuator = config.emsApply ? new EmsActuator(eventPublisher, itemRegistry) : null;
        shadowEms = config.emsShadowEnabled
                ? new ShadowEmsRunner(metadataRegistry, itemRegistry, config.emsSimpleLoadThresholdW,
                        config.mainBreakerAmpsPerPhase, actuator)
                : null;

        tickCounter.set(0);

        EwmaFilter ewma = new EwmaFilter(config.gridEwmaTauSec * 1000L);
        this.gridEwma = ewma;
        RollingAverage rolling5min = new RollingAverage(GRID_5MIN_WINDOW_MS);
        this.grid5minAvg = rolling5min;
        CapacityTariffTracker capTracker = new CapacityTariffTracker(java.time.ZoneId.systemDefault());
        this.capacityTracker = capTracker;
        this.contextBuilder = new ContextBuilder(itemRegistry, ewma, rolling5min, capTracker, config.gridSafetyMarginW,
                config, thingRegistry);

        // Controllers are registered in priority order. The safety breaker check
        // runs first and is always-on; the bridge shadowMode flag still gates the
        // actual write.
        controllerScheduler.register(new SafetyBreakerController(shadowMode));
        // Capacity-tariff controller follows the bridge shadowMode flag.
        CapacityTariffShavingController cap = new CapacityTariffShavingController(shadowMode,
                config.capacityMinBillableW, config.evEcoSacrosanct);
        this.capacityTariff = cap;
        controllerScheduler.register(cap);
        HardPeakShavingController hard = new HardPeakShavingController(shadowMode, config.evEcoSacrosanct);
        this.hardPeakShaving = hard;
        controllerScheduler.register(hard);
        SoftPeakShavingController soft = new SoftPeakShavingController(shadowMode);
        this.softPeakShaving = soft;
        controllerScheduler.register(soft);
        // EvCoordinator runs at priority 60 — after hard/soft peak shaving so it can defer to them.
        // shadowMode arg follows the bridge so the master kill switch covers it.
        EvCoordinatorController ev = new EvCoordinatorController(shadowMode, hard, soft, config.gridSafetyMarginW);
        this.evCoordinator = ev;
        controllerScheduler.register(ev);
        // EV charging plan — pure observer. Reads per-car plan inputs,
        // integrates session kWh, publishes required / projected cost / status
        // for the UI. Does not emit setpoints (the UI prompts the user).
        controllerScheduler.register(new EvChargingPlanController(eventPublisher, itemRegistry));
        // Deadline-aware DHW boiler planner — solar-first, cheapest-hour overnight
        // top-up. Registered before SolarSurplus so the surplus dispatcher sees its
        // decision and won't turn the boiler off (import) mid-top-up. Its own shadow
        // flag lets it log decisions for monitoring before it acts.
        BoilerPlanController boilerPlan = new BoilerPlanController(config.boilerPlanShadow, config.boilerDailyTargetKwh,
                config.boilerReadyByHour, config.boilerRatedKw, hard, eventPublisher, itemRegistry,
                config.boilerPowerItem);
        controllerScheduler.register(boilerPlan);
        if (boilerPlan.enabled()) {
            logger.info("BoilerPlanController: target={}kWh by {}:00, rated={}kW, shadow={}",
                    config.boilerDailyTargetKwh, config.boilerReadyByHour, config.boilerRatedKw,
                    config.boilerPlanShadow);
        }
        SolarSurplusDispatcher surplus = new SolarSurplusDispatcher(shadowMode, boilerPlan);
        this.solarSurplus = surplus;
        controllerScheduler.register(surplus);
        // BatteryTouDispatcher follows the bridge shadow. The
        // BatteryAssetHandler is where the controlMode (auto/fixed/readonly)
        // gating lives — the dispatcher emits setpoint requests identically
        // in every mode; the asset handler writes (auto) or rejects+logs
        // (fixed/readonly).
        controllerScheduler.register(new BatteryTouDispatcher(shadowMode));
        // SelfConsumptionOptimizer — pure observer, computes 24h plan from tariff schedule.
        SelfConsumptionOptimizer optimizer = new SelfConsumptionOptimizer();
        this.optimizer = optimizer;
        controllerScheduler.register(optimizer);
        // ProductionShavingDispatcher — anti-curtailment when battery full + solar high.
        controllerScheduler.register(new ProductionShavingDispatcher());
        // Boiler force-on schedule. CSV in bridge config; no-op when empty.
        BoilerScheduleController boilerSchedule = new BoilerScheduleController(config.boilerForceOnSchedule);
        controllerScheduler.register(boilerSchedule);
        if (boilerSchedule.enabled()) {
            logger.info("BoilerScheduleController: schedule active = '{}'", boilerSchedule.rawSchedule());
        }
        // HeatPumpOptimizerController — observer at priority 85. Reads
        // each emsmanager:heatpump Thing via ThingRegistry, publishes recommendation
        // on the Thing's channels. No-op when no heat-pump Things exist. Gets a real
        // outdoor-temp forecast for the predictive DP planner when an HttpClient is
        // available.
        org.openhab.binding.emsmanager.internal.weather.OpenMeteoTempForecast tempForecast = null;
        HttpClient localHttpClient = httpClient;
        if (localHttpClient != null) {
            tempForecast = new org.openhab.binding.emsmanager.internal.weather.OpenMeteoTempForecast(localHttpClient,
                    config.latitude, config.longitude);
        }
        controllerScheduler.register(new HeatPumpOptimizerController(thingRegistry, itemRegistry, hard, tempForecast));
        // CostAnalyticsController — pure observer at priority 110, no
        // setpoint emissions. Reads tariff price from EnergyContext, writes
        // analytics items each tick. Initializes from persisted item state so
        // the accumulators survive bridge restarts.
        CostAnalyticsController analytics = new CostAnalyticsController(eventPublisher, config.injectionPriceEurPerKWh);
        analytics.initFromItems(itemRegistry);
        controllerScheduler.register(analytics);
        // Long-term stats rollups (yesterday / week / month / year) for the time-range UI.
        controllerScheduler.register(new LongTermStatsController(eventPublisher, itemRegistry));
        // Battery sizing service (heavy; manually triggered).
        PersistenceServiceRegistry localPersistenceRegistry = persistenceRegistry;
        if (localPersistenceRegistry != null) {
            this.sizingService = new BatterySizingService(eventPublisher, itemRegistry, localPersistenceRegistry);
            this.tariffComparisonService = new TariffComparisonService(eventPublisher, itemRegistry,
                    localPersistenceRegistry);
        }
        // CO₂ tracking. Pick emissions provider per bridge config.
        org.openhab.binding.emsmanager.internal.emissions.EmissionsTracker emissionsTracker = null;
        if ("electricitymaps".equalsIgnoreCase(config.emissionsProvider) && config.electricityMapsApiKey != null
                && !config.electricityMapsApiKey.isBlank()) {
            // HttpClient gets created lazily; we can grab the common one via the
            // EmsManagerHandlerFactory's reference. For now build a minimal pass-through
            // — the controller falls back to fixed when provider returns NaN, so this
            // is safe even if we can't bind an HTTP client right here.
            try {
                org.eclipse.jetty.client.HttpClient hc = new org.eclipse.jetty.client.HttpClient();
                hc.start();
                emissionsTracker = new org.openhab.binding.emsmanager.internal.emissions.ElectricityMapsProvider(hc,
                        config.electricityMapsApiKey, config.electricityMapsZone, config.injectionCo2OffsetGramsPerKWh);
                logger.info("CO₂ tracking: using ElectricityMaps live provider (zone={})", config.electricityMapsZone);
            } catch (Throwable t) {
                logger.warn("Failed to start ElectricityMaps client, falling back to fixed factors: {}", t.toString());
            }
        }
        if (emissionsTracker == null) {
            emissionsTracker = new org.openhab.binding.emsmanager.internal.emissions.FixedEmissionsProvider(
                    config.gridCo2GramsPerKWh, config.injectionCo2OffsetGramsPerKWh);
        }
        controllerScheduler.register(new Co2TrackingController(eventPublisher, itemRegistry, config.gridCo2GramsPerKWh,
                config.injectionCo2OffsetGramsPerKWh, emissionsTracker));
        // Per-device anomaly detection.
        controllerScheduler
                .register(new org.openhab.binding.emsmanager.internal.controller.analytics.AnomalyDetectionController(
                        eventPublisher, itemRegistry, thingRegistry, config.anomalyAbsoluteFloorKwh));
        // Statistics rollup tier: daily 23:58 snapshot of finalized totals into
        // clean EMS_Stat_* series (one point/day) for fast month/year charts.
        controllerScheduler
                .register(new org.openhab.binding.emsmanager.internal.controller.analytics.StatisticsRollupController(
                        eventPublisher, itemRegistry, config.statisticsRollupEnabled));

        // Seed the battery reserve-target item on first init if it's still NULL,
        // so downstream consumers have a sane default.
        seedReserveTargetIfMissing(nameOr(config.batteryReserveTargetItem, ITEM_BATTERY_RESERVE_TARGET));

        // Asset handlers — populated regardless of shadow; the handlers themselves
        // honour the shadow flag passed to apply().
        assets.clear();
        assets.put(ASSET_BOILER,
                new BoilerAssetHandler(eventPublisher, nameOr(config.boilerStateItem, ITEM_BOILER_REAL)));
        assets.put(ASSET_AIRCO, new AircoAssetHandler(eventPublisher, nameOr(config.aircoGroupItem, ITEM_AIRCO_GROUP)));
        for (int n = 1; n <= 4; n++) {
            String key = "car" + n;
            String pause = String.format(nameOr(config.carPauseItemPattern, ITEM_CAR_PAUSE_FMT), n);
            String currentLimit = String.format(nameOr(config.carCurrentLimitItemPattern, ITEM_CAR_CURRENT_LIMIT_FMT),
                    n);
            String charging = String.format(nameOr(config.carChargingItemPattern, ITEM_CAR_CHARGING_FMT), n);
            assets.put(key, new ChargerAssetHandler(eventPublisher, key, pause, currentLimit, charging));
        }
        // Per-charger asset handlers from emsmanager:charger Things. A charger
        // Thing's carKey overrides the matching fixed car%d handler with its own
        // explicit write-item bindings. Charger Things added after bridge init are
        // picked up on the next bridge re-init.
        int chargerThings = 0;
        for (org.openhab.core.thing.Thing t : thingRegistry.getAll()) {
            if (!THING_TYPE_CHARGER.equals(t.getThingTypeUID())) {
                continue;
            }
            org.openhab.core.thing.binding.ThingHandler h = t.getHandler();
            if (h instanceof org.openhab.binding.emsmanager.internal.charger.ChargerHandler ch) {
                var ccfg = ch.getCfg();
                assets.put(ch.carKey(), new ChargerAssetHandler(eventPublisher, ch.carKey(), ccfg.pauseItem,
                        ccfg.currentLimitItem, ccfg.chargingItem));
                chargerThings++;
            }
        }
        if (chargerThings > 0) {
            logger.info("A.2: {} charger Thing(s) provide write bindings (override car%d defaults)", chargerThings);
        }
        // Battery asset. controlMode defaults to readonly.
        BatteryConfig batteryConfig = new BatteryConfig();
        batteryConfig.controlMode = config.batteryControlMode;
        batteryConfig.setpointItemName = config.batterySetpointItemName.isEmpty() ? null
                : config.batterySetpointItemName;
        batteryConfig.fixedSetpointW = config.batteryFixedSetpointW;
        batteryConfig.minSetpointW = config.batteryMinSetpointW;
        batteryConfig.maxSetpointW = config.batteryMaxSetpointW;
        assets.put(ASSET_BATTERY, new BatteryAssetHandler(eventPublisher, batteryConfig));

        int interval = Math.max(1, config.tickIntervalSeconds);
        tickJob = scheduler.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.SECONDS);

        registerKickListeners(config);

        updateState(CHANNEL_SHADOW_MODE, OnOffType.from(shadowMode));
        updateState(CHANNEL_TICK_COUNT, new DecimalType(0));
        updateState(CHANNEL_CONTROLLER_COUNT, new DecimalType(controllerScheduler.size()));

        updateStatus(ThingStatus.ONLINE);

        logger.info(
                "EMS Manager bridge initialized: tick={}s, shadowMode={}, gridSafetyMargin={}W, ewmaTau={}s, controllers={}, assets={}",
                interval, shadowMode, config.gridSafetyMarginW, config.gridEwmaTauSec, controllerScheduler.size(),
                assets.size());
        if (shadowMode) {
            logger.info("EMS Manager: SHADOW MODE ACTIVE — controllers will not write setpoints.");
        } else {
            logger.warn("EMS Manager: SHADOW MODE OFF — binding will write setpoints + mirror items.");
        }
    }

    /**
     * Subscribe to per-car Mode / CableConnected / Pause item changes so a flip
     * (e.g. ECO → SNEL) triggers a debounced re-evaluation within ~1 s rather
     * than waiting up to one tick interval. Items missing at init are simply not
     * watched — the periodic tick still covers them.
     */
    private void registerKickListeners(EmsBridgeConfig config) {
        StateChangeListener listener = new StateChangeListener() {
            @Override
            public void stateChanged(Item item, State oldState, State newState) {
                onWatchedItemChanged(item.getName(), newState);
            }

            @Override
            public void stateUpdated(Item item, State state) {
                // Only react to actual changes, not same-value re-updates.
            }
        };
        this.kickListener = listener;
        Set<String> names = new LinkedHashSet<>();
        for (int n = 1; n <= 4; n++) {
            names.add(String.format(nameOr(config.carModeItemPattern, ITEM_CAR_MODE_FMT), n));
            names.add(String.format(nameOr(config.carCableItemPattern, ITEM_CAR_CABLE_FMT), n));
            names.add(String.format(nameOr(config.carPauseItemPattern, ITEM_CAR_PAUSE_FMT), n));
        }
        for (String name : names) {
            try {
                Item item = itemRegistry.getItem(name);
                if (item instanceof GenericItem gi) {
                    gi.addStateChangeListener(listener);
                    watchedItems.add(gi);
                }
            } catch (Exception e) {
                // Item not present yet — fine; the periodic tick still re-evaluates it.
            }
        }
        logger.info("Fast-tick: watching {} car input items for instant re-evaluation", watchedItems.size());
    }

    /** Schedule a single coalesced tick shortly after a watched car input changes. */
    private void onWatchedItemChanged(String itemName, State newState) {
        if (tickJob == null || shadowMode) {
            return;
        }
        ScheduledFuture<?> prev = debouncedTickFuture;
        if (prev != null && !prev.isDone()) {
            prev.cancel(false);
        }
        debouncedTickFuture = scheduler.schedule(() -> {
            logger.debug("Debounced tick fired due to {} change ({})", itemName, newState);
            tick();
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> job = tickJob;
        if (job != null) {
            job.cancel(true);
            tickJob = null;
        }
        ScheduledFuture<?> debounced = debouncedTickFuture;
        if (debounced != null) {
            debounced.cancel(false);
            debouncedTickFuture = null;
        }
        StateChangeListener kl = kickListener;
        if (kl != null) {
            for (GenericItem gi : watchedItems) {
                try {
                    gi.removeStateChangeListener(kl);
                } catch (Exception e) {
                    // best-effort cleanup
                }
            }
        }
        watchedItems.clear();
        kickListener = null;
        for (var c : controllerScheduler.controllers()) {
            controllerScheduler.unregister(c);
        }
        assets.clear();
        gridEwma = null;
        grid5minAvg = null;
        contextBuilder = null;
        softPeakShaving = null;
        hardPeakShaving = null;
        solarSurplus = null;
        evCoordinator = null;
        capacityTariff = null;
        capacityTracker = null;
        optimizer = null;
        super.dispose();
        logger.info("EMS Manager bridge disposed.");
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (CHANNEL_SHADOW_MODE.equals(channelUID.getId())) {
            updateState(CHANNEL_SHADOW_MODE, OnOffType.from(shadowMode));
        }
    }

    public boolean isShadowMode() {
        return shadowMode;
    }

    public PriorityScheduler getControllerScheduler() {
        return controllerScheduler;
    }

    /**
     * If the configured battery-reserve-target item is NULL / UNDEF on init,
     * seed it to the default so downstream consumers have a usable value.
     */
    private void seedReserveTargetIfMissing(String reserveTargetItem) {
        try {
            var item = itemRegistry.getItem(reserveTargetItem);
            var state = item.getState();
            String s = state.toString();
            if (state instanceof org.openhab.core.types.UnDefType || s == null || "NULL".equals(s) || "UNDEF".equals(s)
                    || s.isEmpty()) {
                eventPublisher.post(org.openhab.core.items.events.ItemEventFactory.createStateEvent(reserveTargetItem,
                        new DecimalType(DEFAULT_RESERVE_PCT), null));
                logger.info("Seeded {} = {} (was unset)", reserveTargetItem, DEFAULT_RESERVE_PCT);
            }
        } catch (Throwable t) {
            logger.debug("seedReserveTargetIfMissing: {}", t.getMessage());
        }
    }

    /**
     * Aggregate per-device data into bridge channels — sum of currentW + sum of kWh-today, plus untracked.
     */
    private void publishDeviceAggregates(EnergyContext ctx, java.util.List<DeviceMeterHandler> meters) {
        double trackedW = 0.0;
        double trackedKwhDay = 0.0;
        for (DeviceMeterHandler dm : meters) {
            trackedW += dm.currentW();
            trackedKwhDay += dm.kwhToday();
        }
        double houseW = ctx.houseLoadSumW();
        double untrackedW = Double.isNaN(houseW) ? Double.NaN : Math.max(0.0, houseW - trackedW);
        updateState(CHANNEL_DEVICE_TRACKED_W, new QuantityType<>(trackedW, Units.WATT));
        if (!Double.isNaN(untrackedW)) {
            updateState(CHANNEL_DEVICE_UNTRACKED_W, new QuantityType<>(untrackedW, Units.WATT));
        }
        updateState(CHANNEL_DEVICE_TRACKED_KWH_DAY, new QuantityType<>(trackedKwhDay, Units.KILOWATT_HOUR));
        // Untracked kWh-today is harder (we'd need to integrate house separately).
        // For now we don't publish — phase 18 v2 could add a HouseAccumulator.
    }

    /**
     * Auto-run the heavy analytics (battery sizing + tariff comparison) once per
     * day at/after 06:00, so the dashboard values stay fresh without the user
     * pressing the manual trigger buttons. Runs off the tick thread.
     */
    private void maybeRunDailyAnalytics(ZonedDateTime now, EmsBridgeConfig config) {
        if (now.getHour() < 6) {
            return;
        }
        LocalDate today = now.toLocalDate();
        if (today.equals(lastAnalyticsDate)) {
            return;
        }
        // Startup-race guard: these persistence-backed analytics need the source
        // items registered with a real state. On a cold start the first tick can
        // fire before the textual items have loaded, yielding "0 buckets". Defer
        // WITHOUT claiming the day so the next tick retries once inputs exist.
        if (!analyticsInputsReady(config)) {
            return;
        }
        lastAnalyticsDate = today;
        BatterySizingService sizing = sizingService;
        TariffComparisonService tariff = tariffComparisonService;
        scheduler.execute(() -> {
            try {
                if (sizing != null) {
                    sizing.compute(0.30, config.injectionPriceEurPerKWh, config.batteryCostEurPerKWhInstalled,
                            config.batteryPaybackYearsHorizon, config.batterySizingLookbackDays,
                            nameOr(config.solarLoadItem, ITEM_SOLAR_LOAD),
                            nameOr(config.houseLoadSumItem, ITEM_HOUSE_LOAD_SUM));
                }
                if (tariff != null) {
                    tariff.compute(0.30, config.batterySizingLookbackDays,
                            nameOr(config.solarLoadItem, ITEM_SOLAR_LOAD),
                            nameOr(config.houseLoadSumItem, ITEM_HOUSE_LOAD_SUM));
                }
                logger.info("Daily analytics auto-run complete ({})", today);
            } catch (Throwable t) {
                logger.warn("Daily analytics auto-run failed: {}", t.toString());
            }
        });
    }

    /** True only when the analytics source items exist with a non-UNDEF state. */
    private boolean analyticsInputsReady(EmsBridgeConfig config) {
        return hasState(nameOr(config.solarLoadItem, ITEM_SOLAR_LOAD))
                && hasState(nameOr(config.houseLoadSumItem, ITEM_HOUSE_LOAD_SUM));
    }

    private static String nameOr(@Nullable String configured, String fallback) {
        return (configured == null || configured.isBlank()) ? fallback : configured;
    }

    private boolean hasState(String itemName) {
        try {
            return !(itemRegistry.getItem(itemName).getState() instanceof UnDefType);
        } catch (Exception e) {
            return false;
        }
    }

    private void observeBatterySizingTrigger(EmsBridgeConfig config) {
        BatterySizingService svc = sizingService;
        if (svc == null) {
            return;
        }
        try {
            var trigger = itemRegistry.getItem("EMS_BatterySizing_Run");
            if (trigger.getState() instanceof OnOffType o && o == OnOffType.ON) {
                logger.info("Battery sizing: manual trigger fired");
                // Run on the scheduler thread so we don't block the tick.
                scheduler.execute(() -> {
                    try {
                        // Use the current flat tariff & injection price from bridge config as defaults.
                        // Lookback 90 d gives a meaningful seasonal sample.
                        svc.compute(0.30, config.injectionPriceEurPerKWh, config.batteryCostEurPerKWhInstalled,
                                config.batteryPaybackYearsHorizon, config.batterySizingLookbackDays,
                                nameOr(config.solarLoadItem, ITEM_SOLAR_LOAD),
                                nameOr(config.houseLoadSumItem, ITEM_HOUSE_LOAD_SUM));
                    } catch (Throwable t) {
                        logger.warn("Battery sizing run failed: {}", t.toString());
                    }
                });
            }
        } catch (Throwable t) {
            // item missing — fine, sizing trigger is optional
        }
    }

    /** Generate the weekly HTML report once on Sunday at/after 18:00. */
    private void maybeRunWeeklyReport(ZonedDateTime now) {
        if (now.getDayOfWeek() != java.time.DayOfWeek.SUNDAY || now.getHour() < 18) {
            return;
        }
        LocalDate today = now.toLocalDate();
        if (today.equals(lastWeeklyReportDate)) {
            return; // already generated this Sunday
        }
        lastWeeklyReportDate = today;
        scheduler.execute(() -> {
            try {
                weeklyReportService.generate(today);
                logger.info("Weekly report generated for week ending {}", today);
            } catch (Throwable t) {
                logger.warn("Weekly report generation failed: {}", t.toString());
            }
        });
    }

    private void observeTariffComparisonTrigger(EmsBridgeConfig config) {
        TariffComparisonService svc = tariffComparisonService;
        if (svc == null) {
            return;
        }
        try {
            var trigger = itemRegistry.getItem("EMS_TariffComparison_Run");
            if (trigger.getState() instanceof OnOffType o && o == OnOffType.ON) {
                logger.info("Tariff comparison: manual trigger fired");
                scheduler.execute(() -> {
                    try {
                        svc.compute(0.30, config.batterySizingLookbackDays,
                                nameOr(config.solarLoadItem, ITEM_SOLAR_LOAD),
                                nameOr(config.houseLoadSumItem, ITEM_HOUSE_LOAD_SUM));
                    } catch (Throwable t) {
                        logger.warn("Tariff comparison run failed: {}", t.toString());
                    }
                });
            }
        } catch (Throwable t) {
            // item missing — optional feature
        }
    }

    private void observePeakShavingManualControls() {
        HardPeakShavingController hard = hardPeakShaving;
        if (hard == null) {
            return;
        }
        try {
            var engage = itemRegistry.getItem("PeakShaving_Manual_Engage");
            if (engage.getState() instanceof OnOffType o && o == OnOffType.ON) {
                hard.requestManualEngage();
                logger.info("PeakShaving_Manual_Engage observed ON → forwarded to the peak-shaving controller");
            }
        } catch (Throwable t) {
            // item missing — fine
        }
        try {
            var reset = itemRegistry.getItem("PeakShaving_Manual_Reset");
            if (reset.getState() instanceof OnOffType o && o == OnOffType.ON) {
                hard.requestManualReset();
                logger.info("PeakShaving_Manual_Reset observed ON → forwarded to the peak-shaving controller");
            }
        } catch (Throwable t) {
            // item missing — fine
        }
    }

    /** Look up whether the named controller is in per-controller shadow mode. */
    private boolean isControllerShadow(String controllerName) {
        for (Controller c : controllerScheduler.controllers()) {
            if (c.name().equals(controllerName)) {
                return c.shadowMode();
            }
        }
        return false;
    }

    private void tick() {
        synchronized (tickLock) {
            tickLocked();
        }
    }

    private void tickLocked() {
        try {
            long n = tickCounter.incrementAndGet();
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());

            updateState(CHANNEL_LAST_TICK_AT, new DateTimeType(now));
            updateState(CHANNEL_TICK_COUNT, new DecimalType(n));
            updateState(CHANNEL_CONTROLLER_COUNT, new DecimalType(controllerScheduler.size()));

            ContextBuilder builder = contextBuilder;
            if (builder == null) {
                return;
            }
            EnergyContext ctx = builder.build(shadowMode);
            EmsBridgeConfig config = getConfigAs(EmsBridgeConfig.class);

            // Observe the PeakShaving manual engage / reset items and forward to
            // the controller. The items use expire="2s,command=OFF" so we only see
            // "ON" briefly after the user taps — we forward the ON state and rely on
            // the controller's "consume-once" flag pattern.
            observePeakShavingManualControls();
            observeBatterySizingTrigger(config);
            observeTariffComparisonTrigger(config);
            maybeRunWeeklyReport(now);
            maybeRunDailyAnalytics(now, config);

            // Discover device-meter Things via ThingRegistry, visit
            // each one's handler to integrate W × dt → kWh.
            long nowEpochMs = System.currentTimeMillis();
            java.util.List<DeviceMeterHandler> meters = new java.util.ArrayList<>();
            for (org.openhab.core.thing.Thing t : thingRegistry.getAll()) {
                if (THING_TYPE_DEVICE_METER.equals(t.getThingTypeUID())) {
                    org.openhab.core.thing.binding.ThingHandler h = t.getHandler();
                    if (h instanceof DeviceMeterHandler dmh) {
                        meters.add(dmh);
                        dmh.update(nowEpochMs);
                    }
                }
            }
            publishDeviceAggregates(ctx, meters);

            List<SetpointRequest> decisions = controllerScheduler.run(ctx);

            // Dispatch step — phase 4+5: per-request effective shadow flag is
            // (bridge.shadowMode || controller.shadowMode()). When the bridge
            // is in master shadow, NOTHING writes. When a single controller is
            // in shadow (e.g. SolarSurplusDispatcher during 5a), only its
            // requests log "would-have-done" while the rest of the binding
            // dispatches normally.
            int dispatched = 0;
            int shadowSkipped = 0;
            for (SetpointRequest req : decisions) {
                AssetHandler handler = assets.get(req.assetId());
                if (handler == null) {
                    if (!"eco-cap-policy".equals(req.assetId())) {
                        logger.debug("No asset handler for assetId='{}' (req from {})", req.assetId(),
                                req.controllerName());
                    }
                    continue;
                }
                boolean controllerShadow = isControllerShadow(req.controllerName());
                boolean effectiveShadow = shadowMode || controllerShadow;
                try {
                    if (handler.apply(req, ctx, effectiveShadow)) {
                        dispatched++;
                    } else if (effectiveShadow) {
                        shadowSkipped++;
                    }
                } catch (Throwable t) {
                    logger.warn("Asset handler '{}' threw on request from '{}'", req.assetId(), req.controllerName(),
                            t);
                }
            }

            publishContext(ctx);
            publishPhase2Channels(ctx, decisions);
            publishCarReasons(ctx, decisions);
            publishMirrorItems(ctx);

            ShadowEmsRunner ems = shadowEms;
            if (ems != null && (n == 1 || n % 12 == 0)) {
                ems.run(ctx, decisions);
            }

            if (n == 1 || n % 12 == 0) {
                logger.info(
                        "EMS tick #{}: grid={}/{}/{} solar={} house={} batt={} SoC={} mode={} excess={} cloud={} modbus={} shadow={} controllers={} decisions={} dispatched={} shadowSkipped={}",
                        n, fmt(ctx.gridLoadRawW()), fmt(ctx.gridLoadSmoothedW()), fmt(ctx.gridLoad5minAvgW()),
                        fmt(ctx.solarLoadW()), fmt(ctx.houseLoadSumW()), fmt(ctx.batteryLoadW()), fmt(ctx.batterySoC()),
                        ctx.mode(), fmt(ctx.availableExcessW()),
                        Double.isNaN(ctx.cloudinessTodayPct()) ? "NaN"
                                : String.format("%.0f", ctx.cloudinessTodayPct()),
                        ctx.modbusFresh(), shadowMode, controllerScheduler.size(), decisions.size(), dispatched,
                        shadowSkipped);
                if (!decisions.isEmpty()) {
                    for (SetpointRequest r : decisions) {
                        logger.info("  {} {} → {} {}={} ({})", shadowMode ? "[SHADOW]" : "[LIVE]", r.controllerName(),
                                r.assetId(), r.kind(), r.value(), r.reason());
                    }
                }
            }
        } catch (Throwable t) {
            logger.warn("EMS tick failed", t);
        }
    }

    private void publishContext(EnergyContext ctx) {
        publishPower(CHANNEL_GRID_LOAD_RAW, ctx.gridLoadRawW());
        publishPower(CHANNEL_GRID_LOAD_SMOOTHED, ctx.gridLoadSmoothedW());
        publishPower(CHANNEL_SOLAR_LOAD, ctx.solarLoadW());
        publishPower(CHANNEL_HOUSE_LOAD_SUM, ctx.houseLoadSumW());
        publishPower(CHANNEL_BATTERY_LOAD, ctx.batteryLoadW());
        publishPower(CHANNEL_AVAILABLE_EXCESS, ctx.availableExcessW());

        publishPercent(CHANNEL_BATTERY_SOC, ctx.batterySoC());
        publishPercent(CHANNEL_BATTERY_RESERVE_TARGET, ctx.batteryReserveTargetPct());

        updateState(CHANNEL_BATTERY_BELOW_RESERVE, OnOffType.from(ctx.batteryBelowReserve()));
        updateState(CHANNEL_ENERGY_MODE, new StringType(ctx.mode().name()));
    }

    private void publishPhase2Channels(EnergyContext ctx, List<SetpointRequest> decisions) {
        SoftPeakShavingController soft = softPeakShaving;
        int capA = (soft != null) ? soft.currentCapA() : NORMAL_ECO_CAP_A;
        updateState(CHANNEL_SOFT_ECO_CAP_A, new DecimalType(capA));

        int headroom = SafetyBreakerController.minHeadroomA(ctx);
        updateState(CHANNEL_BREAKER_HEADROOM_A, new DecimalType(headroom));

        updateState(CHANNEL_MODBUS_FRESH, OnOffType.from(ctx.modbusFresh()));

        HardPeakShavingController hard = hardPeakShaving;
        if (hard != null) {
            updateState(CHANNEL_HARD_PEAK_LEVEL, new DecimalType(hard.level()));
            updateState(CHANNEL_HARD_PEAK_STATUS, new StringType(hard.status()));
            updateState(CHANNEL_HARD_PEAK_DETAIL, new StringType(hard.detail()));
        }

        // Surplus dispatcher diagnostics.
        publishPower(CHANNEL_GRID_5MIN_AVG, ctx.gridLoad5minAvgW());
        SolarSurplusDispatcher surplus = solarSurplus;
        int thresh = (surplus != null) ? surplus.lastOnThresholdW() : SURPLUS_DEFAULT_ON_THRESHOLD_W;
        updateState(CHANNEL_SURPLUS_ON_THRESHOLD, new QuantityType<>(thresh, Units.WATT));
        updateState(CHANNEL_BOILER_USER_OVERRIDE, OnOffType.from(ctx.boilerUserOverride()));

        // Capacity-tariff diagnostics. For display, report imports as positive kW
        // (peakKW). Negate the signed monthlyPeakW / projected.
        double monthlyPeakAbsW = -ctx.monthlyPeakW();
        if (monthlyPeakAbsW < 0) {
            monthlyPeakAbsW = 0; // never show negative peak
            updateState(CHANNEL_CAPACITY_MONTHLY_PEAK_W, new QuantityType<>(monthlyPeakAbsW, Units.WATT));
        }
        publishPower(CHANNEL_CAPACITY_CURRENT_QUARTER_W, ctx.currentQuarterAvgW());
        publishPower(CHANNEL_CAPACITY_PROJECTED_QUARTER_W, CapacityTariffShavingController.projectedQuarterW(ctx));
        CapacityTariffShavingController capCtrl = capacityTariff;
        updateState(CHANNEL_CAPACITY_WOULD_EXCEED,
                OnOffType.from(capCtrl != null && capCtrl.wouldExceedMonthlyPeak(ctx)));
        updateState(CHANNEL_CAPACITY_STATUS, new StringType(capCtrl != null ? capCtrl.lastStatus() : "(initialising)"));

        // Optimizer plan readback.
        SelfConsumptionOptimizer opt = optimizer;
        if (opt != null) {
            updateState(CHANNEL_OPTIMIZER_PLAN_CSV, new StringType(opt.planCsv()));
            updateState(CHANNEL_OPTIMIZER_NEXT_CHARGE_HOUR, new DecimalType(opt.nextChargeHour()));
            updateState(CHANNEL_OPTIMIZER_NEXT_DISCHARGE_HOUR, new DecimalType(opt.nextDischargeHour()));
        }

        String summary;
        if (decisions.isEmpty()) {
            summary = "(idle)";
        } else {
            summary = decisions.stream()
                    .map(r -> r.controllerName() + ":" + r.assetId() + "=" + r.kind() + ":" + r.value())
                    .collect(Collectors.joining("; "));
        }
        updateState(CHANNEL_LAST_DECISION_LOG, new StringType(summary));
    }

    /**
     * Publish a human-readable per-car "why" string to each car's reason
     * channel, derived from this tick's surviving decisions. Lets the UI show
     * exactly which controller is steering a car and why (e.g. a capacity-tariff
     * pause vs. a routine SNEL setpoint), instead of leaving the user guessing.
     */
    private void publishCarReasons(EnergyContext ctx, List<SetpointRequest> decisions) {
        for (int n = 1; n <= 4; n++) {
            String carKey = "car" + n;
            CarSnapshot car = ctx.cars().get(carKey);
            updateState(String.format(CHANNEL_CAR_REASON_FMT, n), new StringType(describeCar(carKey, car, decisions)));
        }
    }

    /**
     * Pick the most salient decision for one car (a pause outranks an amps
     * setpoint outranks a charge-start) and format it; when no controller
     * steered the car this tick, fall back to a neutral status derived from the
     * snapshot (no cable / manual OFF / running).
     */
    private String describeCar(String carKey, @Nullable CarSnapshot car, List<SetpointRequest> decisions) {
        @Nullable
        SetpointRequest pause = null;
        @Nullable
        SetpointRequest amps = null;
        @Nullable
        SetpointRequest start = null;
        for (SetpointRequest r : decisions) {
            if (!carKey.equals(r.assetId())) {
                continue;
            }
            switch (r.kind()) {
                case PAUSE:
                    pause = r;
                    break;
                case AMPS:
                    amps = r;
                    break;
                case CHARGE_START:
                    start = r;
                    break;
                default:
                    break;
            }
        }
        if (pause != null && pause.value() >= 0.5) {
            return pause.controllerName() + ": " + pause.reason() + " — gepauzeerd";
        }
        if (amps != null) {
            return amps.controllerName() + ": " + amps.reason() + " — " + (int) Math.round(amps.value()) + "A";
        }
        if (start != null) {
            return start.controllerName() + ": " + start.reason() + " — start";
        }
        if (pause != null) {
            return pause.controllerName() + ": " + pause.reason() + " — hervat";
        }
        if (car == null) {
            return "geen gegevens";
        }
        if (!car.cableConnected()) {
            return "geen kabel aangesloten";
        }
        switch (car.mode()) {
            case OFF:
                return "handmatige modus (uit)";
            case ECO:
                return "ECO — geen sturing nodig";
            case SNEL:
                return "SNEL — geen sturing nodig";
            default:
                return car.ocppStatus();
        }
    }

    /**
     * Mirror writes for backwards compatibility — only performed when
     * shadowMode is OFF, so the binding does not fight an external actor that
     * may still be writing these items while in shadow.
     */
    private void publishMirrorItems(EnergyContext ctx) {
        if (shadowMode) {
            return;
        }
        // Legacy backwards-compat mirror items are opt-in. A fresh install uses
        // the binding's own channels instead, so skip these unless requested.
        if (!publishLegacyMirrorItems) {
            return;
        }
        HardPeakShavingController hard = hardPeakShaving;
        SoftPeakShavingController soft = softPeakShaving;
        try {
            if (hard != null) {
                eventPublisher.post(
                        ItemEventFactory.createStateEvent("PeakShaving_Level", new DecimalType(hard.level()), null));
                eventPublisher.post(
                        ItemEventFactory.createStateEvent("PeakShaving_Status", new StringType(hard.status()), null));
                eventPublisher.post(
                        ItemEventFactory.createStateEvent("PeakShaving_Detail", new StringType(hard.detail()), null));
            }
            if (soft != null) {
                eventPublisher.post(ItemEventFactory.createStateEvent("PeakShaving_EcoCap_A",
                        new DecimalType(soft.currentCapA()), null));
            }
            // EnergyOrchestrator outputs — bind now owns these too while not in shadow.
            eventPublisher.post(ItemEventFactory.createStateEvent("Available_excess_W",
                    new DecimalType(ctx.availableExcessW()), null));
            eventPublisher.post(ItemEventFactory.createStateEvent("EnergyOrchestrator_Mode",
                    new StringType(ctx.mode().name()), null));
            eventPublisher.post(ItemEventFactory.createStateEvent("Battery_below_reserve",
                    OnOffType.from(ctx.batteryBelowReserve()), null));
        } catch (Throwable t) {
            // Items might not exist on a fresh install — that's fine, no-op.
            logger.debug("Mirror write failed (item missing?): {}", t.getMessage());
        }
    }

    private void publishPower(String channel, double valueW) {
        if (Double.isNaN(valueW)) {
            updateState(channel, UnDefType.UNDEF);
        } else {
            updateState(channel, new QuantityType<>(valueW, Units.WATT));
        }
    }

    private void publishPercent(String channel, double valuePct) {
        if (Double.isNaN(valuePct)) {
            updateState(channel, UnDefType.UNDEF);
        } else {
            updateState(channel, new QuantityType<>(valuePct, Units.PERCENT));
        }
    }

    private static String fmt(double d) {
        return Double.isNaN(d) ? "NaN" : String.format("%.0f", d);
    }
}

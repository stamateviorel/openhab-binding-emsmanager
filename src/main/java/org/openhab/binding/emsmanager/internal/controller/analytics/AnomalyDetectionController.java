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
package org.openhab.binding.emsmanager.internal.controller.analytics;

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.anomaly.AnomalyDetector;
import org.openhab.binding.emsmanager.internal.anomaly.AnomalyState;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.openhab.binding.emsmanager.internal.devicemeter.DeviceMeterHandler;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.binding.ThingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-device anomaly detection.
 *
 * <p>
 * Runs after LongTermStatsController. Each tick checks every
 * device-meter Thing's today_kWh against its 4-week per-DoW baseline.
 * On a fresh anomaly, publishes per-device items + raises a global
 * counter; one alert per device per 12 hours.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class AnomalyDetectionController implements Controller {

    public static final String NAME = "anomaly-detection";

    private static final Logger LOGGER = LoggerFactory.getLogger(AnomalyDetectionController.class);
    private static final long COOLDOWN_MS = 12 * 60 * 60 * 1000L;

    private final EventPublisher eventPublisher;
    private final ItemRegistry itemRegistry;
    private final ThingRegistry thingRegistry;
    private final double absoluteFloorKwh;

    /** Per-device state cache (loaded from disk on first sight). */
    private final Map<String, AnomalyState> states = new HashMap<>();
    /** Last day we did the per-DoW append for each device — keyed (device, dow). */
    private final Map<String, LocalDate> lastAppendDay = new HashMap<>();

    public AnomalyDetectionController(EventPublisher eventPublisher, ItemRegistry itemRegistry,
            ThingRegistry thingRegistry, double absoluteFloorKwh) {
        this.eventPublisher = eventPublisher;
        this.itemRegistry = itemRegistry;
        this.thingRegistry = thingRegistry;
        this.absoluteFloorKwh = absoluteFloorKwh;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_ANOMALY_DETECTION;
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
        long nowMs = ctx.tickAt().toEpochMilli();
        LocalDate today = ZonedDateTime.ofInstant(ctx.tickAt(), ZoneId.systemDefault()).toLocalDate();
        int dow = today.getDayOfWeek().getValue();
        int activeAnomalies = 0;

        for (Thing t : thingRegistry.getAll()) {
            if (!THING_TYPE_DEVICE_METER.equals(t.getThingTypeUID())) {
                continue;
            }
            ThingHandler h = t.getHandler();
            if (!(h instanceof DeviceMeterHandler dmh)) {
                continue;
            }
            try {
                if (evaluateOne(dmh, today, dow, nowMs)) {
                    activeAnomalies++;
                }
            } catch (Throwable th) {
                LOGGER.debug("AnomalyDetection[{}]: {}", dmh.deviceId(), th.toString());
            }
        }
        publish("EMS_Anomaly_Count_Today", new DecimalType(activeAnomalies));
        return List.of();
    }

    private boolean evaluateOne(DeviceMeterHandler dm, LocalDate today, int dow, long nowMs) {
        String id = dm.deviceId();
        AnomalyState state = states.computeIfAbsent(id, AnomalyState::load);

        double todayKwh = dm.kwhToday();
        double[] history = state.historyFor(dow);

        AnomalyDetector.Result r = AnomalyDetector.detect(history, todayKwh, absoluteFloorKwh, 3.5);

        // Publish per-device channels (best-effort).
        String activeItem = "EMS_Anomaly_" + id + "_Active";
        String detailItem = "EMS_Anomaly_" + id + "_Detail";

        if (r.anomaly() && (nowMs - state.lastAlertMs) > COOLDOWN_MS) {
            publish(activeItem, OnOffType.ON);
            String detail = String.format(java.util.Locale.ROOT,
                    "Vandaag %.2f kWh; mediaan deze weekdag %.2f kWh (z=%.1f, MAD=%.2f)", todayKwh, r.median(),
                    r.zScore(), r.mad());
            publish(detailItem, new StringType(detail));
            state.lastAlertMs = nowMs;
            state.save();
            LOGGER.info("Anomaly[{}]: {}", id, detail);
            return true;
        } else if (!r.anomaly()) {
            publish(activeItem, OnOffType.OFF);
        }

        // End-of-day rollover: when the date changes, append yesterday's completed
        // total into the per-day-of-week baseline. The bridge visits DeviceMeterHandler
        // (which rolls its ring at its own midnight) BEFORE controllers run, so by the
        // first new-day tick dm.yesterdayKwh() == yesterday's finished total.
        LocalDate last = lastAppendDay.get(id);
        if (last == null) {
            lastAppendDay.put(id, today);
        } else if (!last.equals(today)) {
            double yesterdayTotal = dm.yesterdayKwh();
            if (!Double.isNaN(yesterdayTotal)) {
                int yesterdayDow = last.getDayOfWeek().getValue();
                state.recordEndOfDay(yesterdayDow, yesterdayTotal);
                state.save();
                LOGGER.debug("Anomaly[{}]: appended {} kWh to {}-baseline (now {} samples)", id,
                        String.format("%.2f", yesterdayTotal), last.getDayOfWeek(),
                        state.historyFor(yesterdayDow).length);
            }
            lastAppendDay.put(id, today);
        }
        return false;
    }

    private void publish(String name, org.openhab.core.types.State value) {
        try {
            itemRegistry.getItem(name);
            eventPublisher.post(ItemEventFactory.createStateEvent(name, value, null));
        } catch (Throwable t) {
            // item missing — skip
        }
    }
}

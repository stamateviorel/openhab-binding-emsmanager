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

    /** Visible for testing: only a finished day can be called unusually low. */
    static boolean reportableFromRunningTotal(AnomalyDetector.Result r) {
        return r.anomaly() && r.delta() > 0;
    }

    static String describeForTest(double todayKwh, AnomalyDetector.Result r, boolean reportable) {
        return describe(todayKwh, r, reportable);
    }

    private static String describe(double todayKwh, AnomalyDetector.Result r, boolean reportable) {
        if (reportable) {
            return String.format(java.util.Locale.ROOT,
                    "Vandaag %.2f kWh; mediaan deze weekdag %.2f kWh (z=%.1f, MAD=%.2f)", todayKwh, r.median(),
                    r.zScore(), r.mad());
        }
        return String.format(java.util.Locale.ROOT, "Vandaag %.2f kWh; mediaan deze weekdag %.2f kWh (tot nu toe)",
                todayKwh, r.median());
    }

    /** Package-private so a test can drive one device without standing up a Thing registry. */
    boolean evaluateOne(DeviceMeterHandler dm, LocalDate today, int dow, long nowMs) {
        String id = dm.deviceId();
        AnomalyState state = states.computeIfAbsent(id, AnomalyState::load);

        double todayKwh = dm.kwhToday();
        double[] history = state.historyFor(dow);

        AnomalyDetector.Result r = AnomalyDetector.detect(history, todayKwh, absoluteFloorKwh, 3.5);

        // Only the high side can be judged from a part-finished day. A running total is always
        // below a distribution of finished ones, so "unusually low" is not a statement this
        // comparison can make until the day is over; it is answered once, at the rollover below.
        boolean reportable = reportableFromRunningTotal(r);

        // Publish per-device channels (best-effort).
        String activeItem = "EMS_Anomaly_" + id + "_Active";
        String detailItem = "EMS_Anomaly_" + id + "_Detail";

        // A device found short yesterday stays flagged for the day, so the finding survives long
        // enough to be seen; it is the reason to go and look at the thing.
        String latched = today.equals(state.lowDay) ? state.lowDetail : null;

        // Always current. Publishing this only while an alert fires left every device carrying the
        // text of its last one indefinitely - car1 read "Vandaag 25.00 kWh" on a day it used none,
        // which is a false statement rather than an out-of-date one.
        publish(detailItem, new StringType(latched != null ? latched : describe(todayKwh, r, reportable)));

        if (reportable && (nowMs - state.lastAlertMs) > COOLDOWN_MS) {
            publish(activeItem, OnOffType.ON);
            state.lastAlertMs = nowMs;
            state.save();
            LOGGER.info("Anomaly[{}]: {}", id, describe(todayKwh, r, true));
            return true;
        }
        if (latched != null) {
            publish(activeItem, OnOffType.ON);
            return true;
        }
        if (!reportable) {
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
                // Judged against the baseline as it stood BEFORE yesterday joins it, otherwise the
                // day being tested is part of what it is tested against.
                AnomalyDetector.Result done = AnomalyDetector.detect(state.historyFor(yesterdayDow), yesterdayTotal,
                        absoluteFloorKwh, 3.5);
                state.lowDay = null;
                state.lowDetail = null;
                if (done.anomaly() && done.delta() < 0) {
                    state.lowDay = today;
                    state.lowDetail = String.format(java.util.Locale.ROOT,
                            "Gisteren %.2f kWh; mediaan deze weekdag %.2f kWh (ongewoon laag)", yesterdayTotal,
                            done.median());
                    LOGGER.info("Anomaly[{}]: {}", id, state.lowDetail);
                }
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

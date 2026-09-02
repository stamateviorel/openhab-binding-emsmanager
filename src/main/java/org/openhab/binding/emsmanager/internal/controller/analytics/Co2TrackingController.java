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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.openhab.binding.emsmanager.internal.emissions.EmissionsTracker;
import org.openhab.binding.emsmanager.internal.util.CachePaths;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * CO₂ tracking. Pure observer at priority 117 (after
 * LongTermStatsController). Integrates grid imports × emission factor into
 * a per-day CO₂ accumulator. The "saved" accumulator credits BOTH avoidance
 * paths: exported solar × avoided-generation factor (offsets the grid mix
 * elsewhere) AND self-consumed solar × grid factor (avoids an own import that
 * would otherwise have emitted) — the larger term on a battery+PV site, and
 * what keeps CO₂-saved consistent with the € savings metric.
 *
 * <p>
 * Defaults are Belgium-sane (140 g/kWh import, 350 g/kWh avoided),
 * but both factors are bridge config so downstream users can plug in
 * their country's grid mix. ElectricityMaps.io publishes real-time
 * country factors as an API — a future enhancement could fetch dynamically.
 *
 * <p>
 * Publishes (when items exist):
 * <ul>
 * <li>{@code EMS_CO2_Today_kg} — kg CO₂ emitted today (imports only, signed: +)</li>
 * <li>{@code EMS_CO2_Saved_Today_kg} — kg CO₂ avoided today (self-consumption + exports)</li>
 * <li>{@code EMS_CO2_Net_Today_kg} — Today − Saved</li>
 * <li>{@code EMS_CO2_Year_kg} — running yearly total</li>
 * </ul>
 *
 * <p>
 * The accumulators are snapshotted to the binding's own cache and restored from it first; item
 * state is a fallback that can only raise a counter, never lower it, and nothing is published until
 * one of the two has yielded a real value. Same discipline as {@link CostAnalyticsController}, for
 * the same reason.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class Co2TrackingController implements Controller {

    public static final String NAME = "co2-tracking";

    private static final Logger LOGGER = LoggerFactory.getLogger(Co2TrackingController.class);
    private static final String CACHE_FILE = "emsmanager-co2-cache.json";
    private static final int SAVE_EVERY_TICKS = 60;
    /**
     * A fresh install has items that are NULL and stay NULL until this controller publishes, so
     * waiting for them forever would be a deadlock; ~5 minutes at a 5 s tick is far past any
     * restore-on-startup.
     */
    private static final int MAX_DEFERRED_TICKS = 60;
    private static final Gson GSON = new Gson();

    private final @Nullable EventPublisher eventPublisher;
    private final @Nullable ItemRegistry itemRegistry;
    private final double gridCo2GramsPerKWh;
    private final double injectionCo2OffsetGramsPerKWh;
    private final @Nullable EmissionsTracker emissions;

    private LocalDate lastSeenDay = LocalDate.MIN;
    private double todayKgEmitted = 0.0;
    private double todaySavedKg = 0.0;
    private double yearKgEmitted = 0.0;
    private double yearSavedKg = 0.0;
    private long lastTickMs = 0L;
    private boolean restored = false;
    private int deferredTicks = 0;
    private int ticksSinceSave = 0;
    private @Nullable LocalDate snapshotDay;
    private int snapshotYear = -1;

    public Co2TrackingController(@Nullable EventPublisher eventPublisher, @Nullable ItemRegistry itemRegistry,
            double gridCo2GramsPerKWh, double injectionCo2OffsetGramsPerKWh, @Nullable EmissionsTracker emissions) {
        this.eventPublisher = eventPublisher;
        this.itemRegistry = itemRegistry;
        this.gridCo2GramsPerKWh = gridCo2GramsPerKWh;
        this.injectionCo2OffsetGramsPerKWh = injectionCo2OffsetGramsPerKWh;
        this.emissions = emissions;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_CO2_TRACKING;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean shadowMode() {
        return false;
    }

    /** kg CO₂ credited as avoided today — self-consumption + exports (exposed for tests). */
    double todaySavedKg() {
        return todaySavedKg;
    }

    /** kg CO₂ emitted today from grid imports (exposed for tests). */
    double todayEmittedKg() {
        return todayKgEmitted;
    }

    double yearEmittedKg() {
        return yearKgEmitted;
    }

    boolean isRestored() {
        return restored;
    }

    @Override
    public List<SetpointRequest> evaluate(EnergyContext ctx) {
        long nowMs = ctx.tickAt().toEpochMilli();
        double gridW = ctx.gridLoadRawW();
        if (Double.isNaN(gridW)) {
            return List.of();
        }

        LocalDate today = ZonedDateTime.ofInstant(ctx.tickAt(), ZoneId.systemDefault()).toLocalDate();
        if (!restored) {
            restored = restore(today);
            if (!restored) {
                return List.of();
            }
        }

        if (!today.equals(lastSeenDay)) {
            todayKgEmitted = 0.0;
            todaySavedKg = 0.0;
            if (today.getYear() != lastSeenDay.getYear()) {
                yearKgEmitted = 0.0;
                yearSavedKg = 0.0;
            }
            lastSeenDay = today;
            saveSnapshot();
        }

        if (lastTickMs > 0L) {
            long dtMs = nowMs - lastTickMs;
            if (dtMs > 0 && dtMs < 60_000L) {
                double hours = dtMs / 3_600_000.0;
                // Prefer live emissions if a provider returns a usable value;
                // fall back to fixed constants.
                double gridFactor = gridCo2GramsPerKWh;
                double offsetFactor = injectionCo2OffsetGramsPerKWh;
                if (emissions != null) {
                    double liveGrid = emissions.currentGridGramsPerKWh();
                    double liveOffset = emissions.currentInjectionOffsetGramsPerKWh();
                    if (!Double.isNaN(liveGrid) && liveGrid > 0) {
                        gridFactor = liveGrid;
                    }
                    if (!Double.isNaN(liveOffset) && liveOffset > 0) {
                        offsetFactor = liveOffset;
                    }
                }
                if (gridW < 0) {
                    // Importing — emit CO₂.
                    double kwh = -gridW / 1000.0 * hours;
                    double kg = kwh * gridFactor / 1000.0;
                    todayKgEmitted += kg;
                    yearKgEmitted += kg;
                } else if (gridW > 0) {
                    // Exporting — save CO₂ (avoid grid generation elsewhere).
                    double kwh = gridW / 1000.0 * hours;
                    double kg = kwh * offsetFactor / 1000.0;
                    todaySavedKg += kg;
                    yearSavedKg += kg;
                }

                // Self-consumed solar also avoids a grid import that would have emitted at
                // the grid factor — credit it too, so CO₂-saved stays consistent with the
                // € savings metric (which counts the same self-consumption). Definition
                // mirrors CostAnalyticsController: self-consumed = solar not fed in.
                double solarW = ctx.solarLoadW();
                if (!Double.isNaN(solarW) && solarW > 0) {
                    double exportW = Math.max(0.0, gridW);
                    double selfConsumedW = Math.max(0.0, solarW - exportW);
                    if (selfConsumedW > 0) {
                        double kwh = selfConsumedW / 1000.0 * hours;
                        double kg = kwh * gridFactor / 1000.0;
                        todaySavedKg += kg;
                        yearSavedKg += kg;
                    }
                }
            }
        }
        lastTickMs = nowMs;

        publish("EMS_CO2_Today_kg", todayKgEmitted);
        publish("EMS_CO2_Saved_Today_kg", todaySavedKg);
        publish("EMS_CO2_Net_Today_kg", todayKgEmitted - todaySavedKg);
        publish("EMS_CO2_Year_kg", yearKgEmitted);
        publish("EMS_CO2_Saved_Year_kg", yearSavedKg);
        if (++ticksSinceSave >= SAVE_EVERY_TICKS) {
            ticksSinceSave = 0;
            saveSnapshot();
        }

        return List.of();
    }

    /**
     * @return true once the accumulators hold real values; false to try again next tick without
     *         publishing anything
     */
    private boolean restore(LocalDate today) {
        boolean fromSnapshot = loadSnapshot();
        double yearFromItem = readNumber("EMS_CO2_Year_kg");
        if (Double.isNaN(yearFromItem) && !fromSnapshot && itemRegistry != null) {
            if (++deferredTicks < MAX_DEFERRED_TICKS) {
                LOGGER.debug("Co2Tracking restore deferred — no snapshot and items not readable yet");
                return false;
            }
            LOGGER.warn("Co2Tracking: no snapshot and items still unreadable after {} ticks, starting from 0",
                    deferredTicks);
        }
        // Within its period each counter only ever rises, so of two readings the higher is the
        // later one; a half-restored registry can therefore not pull a counter down.
        todayKgEmitted = highest(readNumber("EMS_CO2_Today_kg"), todayKgEmitted);
        todaySavedKg = highest(readNumber("EMS_CO2_Saved_Today_kg"), todaySavedKg);
        yearKgEmitted = highest(yearFromItem, yearKgEmitted);
        yearSavedKg = highest(readNumber("EMS_CO2_Saved_Year_kg"), yearSavedKg);
        // Items carry no date, so the snapshot's period decides for both of them.
        LocalDate savedDay = snapshotDay;
        if (savedDay != null && !savedDay.equals(today)) {
            todayKgEmitted = 0.0;
            todaySavedKg = 0.0;
        }
        if (snapshotYear != -1 && snapshotYear != today.getYear()) {
            yearKgEmitted = 0.0;
            yearSavedKg = 0.0;
        }
        lastSeenDay = today;
        LOGGER.info("Co2Tracking restored (snapshot={}): today emitted={} saved={}, year emitted={} saved={}",
                fromSnapshot, fmt(todayKgEmitted), fmt(todaySavedKg), fmt(yearKgEmitted), fmt(yearSavedKg));
        return true;
    }

    private static double highest(double fromItem, double current) {
        return Double.isNaN(fromItem) ? current : Math.max(fromItem, current);
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }

    /** @return true if a snapshot was found and applied. */
    private boolean loadSnapshot() {
        try {
            Path path = CachePaths.cacheFile(CACHE_FILE);
            if (!Files.exists(path)) {
                return false;
            }
            JsonObject o = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (o == null) {
                return false;
            }
            todayKgEmitted = num(o, "todayKgEmitted");
            todaySavedKg = num(o, "todaySavedKg");
            yearKgEmitted = num(o, "yearKgEmitted");
            yearSavedKg = num(o, "yearSavedKg");
            snapshotDay = o.has("day") ? LocalDate.parse(o.get("day").getAsString()) : null;
            snapshotYear = o.has("year") ? o.get("year").getAsInt() : -1;
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Co2Tracking snapshot unreadable, falling back to item state: {}", t.getMessage());
            return false;
        }
    }

    void saveSnapshot() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("todayKgEmitted", todayKgEmitted);
            o.addProperty("todaySavedKg", todaySavedKg);
            o.addProperty("yearKgEmitted", yearKgEmitted);
            o.addProperty("yearSavedKg", yearSavedKg);
            if (lastSeenDay != LocalDate.MIN) {
                o.addProperty("day", lastSeenDay.toString());
                o.addProperty("year", lastSeenDay.getYear());
            }
            CachePaths.writeAtomic(CachePaths.cacheFile(CACHE_FILE), GSON.toJson(o));
        } catch (Throwable t) {
            LOGGER.warn("Co2Tracking snapshot save failed: {}", t.getMessage());
        }
    }

    private static double num(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : 0.0;
    }

    /** {@link Double#NaN} for NULL/UNDEF/missing, so "not readable yet" is not mistaken for 0. */
    private double readNumber(String name) {
        ItemRegistry reg = itemRegistry;
        if (reg == null) {
            return Double.NaN;
        }
        try {
            var item = reg.getItem(name);
            var state = item.getState();
            if (state instanceof UnDefType) {
                return Double.NaN;
            }
            if (state instanceof DecimalType d) {
                return d.doubleValue();
            }
            String s = state.toString();
            if (s == null || s.isEmpty() || "NULL".equals(s) || "UNDEF".equals(s)) {
                return Double.NaN;
            }
            int sp = s.indexOf(' ');
            return Double.parseDouble(sp > 0 ? s.substring(0, sp) : s);
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    private void publish(String item, double value) {
        EventPublisher ep = eventPublisher;
        ItemRegistry reg = itemRegistry;
        if (ep == null || reg == null) {
            return;
        }
        try {
            reg.getItem(item);
            ep.post(ItemEventFactory.createStateEvent(item, new DecimalType(Math.round(value * 1000.0) / 1000.0),
                    null));
        } catch (Throwable t) {
            // item missing — skip silently
        }
    }
}

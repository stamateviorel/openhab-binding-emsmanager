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
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.openhab.binding.emsmanager.internal.emissions.EmissionsTracker;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.types.UnDefType;

/**
 * CO₂ tracking. Pure observer at priority 117 (after
 * LongTermStatsController). Integrates grid imports × emission factor into
 * a per-day CO₂ accumulator; integrates grid exports × avoided-generation
 * factor into a "saved" accumulator.
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
 * <li>{@code EMS_CO2_Saved_Today_kg} — kg CO₂ avoided today via exports</li>
 * <li>{@code EMS_CO2_Net_Today_kg} — Today − Saved</li>
 * <li>{@code EMS_CO2_Year_kg} — running yearly total</li>
 * </ul>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class Co2TrackingController implements Controller {

    public static final String NAME = "co2-tracking";

    private final EventPublisher eventPublisher;
    private final ItemRegistry itemRegistry;
    private final double gridCo2GramsPerKWh;
    private final double injectionCo2OffsetGramsPerKWh;
    private final @Nullable EmissionsTracker emissions;

    private LocalDate lastSeenDay = LocalDate.MIN;
    private double todayKgEmitted = 0.0;
    private double todaySavedKg = 0.0;
    private double yearKgEmitted = 0.0;
    private double yearSavedKg = 0.0;
    private long lastTickMs = 0L;

    public Co2TrackingController(EventPublisher eventPublisher, ItemRegistry itemRegistry, double gridCo2GramsPerKWh,
            double injectionCo2OffsetGramsPerKWh, @Nullable EmissionsTracker emissions) {
        this.eventPublisher = eventPublisher;
        this.itemRegistry = itemRegistry;
        this.gridCo2GramsPerKWh = gridCo2GramsPerKWh;
        this.injectionCo2OffsetGramsPerKWh = injectionCo2OffsetGramsPerKWh;
        this.emissions = emissions;

        // Restore today's value from persisted item state if available.
        this.todayKgEmitted = readSafe("EMS_CO2_Today_kg");
        this.todaySavedKg = readSafe("EMS_CO2_Saved_Today_kg");
        this.yearKgEmitted = readSafe("EMS_CO2_Year_kg");
        this.yearSavedKg = readSafe("EMS_CO2_Saved_Year_kg");
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

    @Override
    public List<SetpointRequest> evaluate(EnergyContext ctx) {
        long nowMs = ctx.tickAt().toEpochMilli();
        double gridW = ctx.gridLoadRawW();
        if (Double.isNaN(gridW)) {
            return List.of();
        }

        LocalDate today = ZonedDateTime.ofInstant(ctx.tickAt(), ZoneId.systemDefault()).toLocalDate();
        if (!today.equals(lastSeenDay)) {
            if (lastSeenDay != LocalDate.MIN) {
                // Day rollover — year totals already accumulated; reset today.
                todayKgEmitted = 0.0;
                todaySavedKg = 0.0;
            }
            // Year rollover — when Jan 1 of a new year.
            if (lastSeenDay != LocalDate.MIN && today.getYear() != lastSeenDay.getYear()) {
                yearKgEmitted = 0.0;
                yearSavedKg = 0.0;
            }
            lastSeenDay = today;
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
                    // Exporting — save CO₂ (avoid grid generation).
                    double kwh = gridW / 1000.0 * hours;
                    double kg = kwh * offsetFactor / 1000.0;
                    todaySavedKg += kg;
                    yearSavedKg += kg;
                }
            }
        }
        lastTickMs = nowMs;

        publish("EMS_CO2_Today_kg", todayKgEmitted);
        publish("EMS_CO2_Saved_Today_kg", todaySavedKg);
        publish("EMS_CO2_Net_Today_kg", todayKgEmitted - todaySavedKg);
        publish("EMS_CO2_Year_kg", yearKgEmitted);
        publish("EMS_CO2_Saved_Year_kg", yearSavedKg);

        return List.of();
    }

    private double readSafe(String name) {
        try {
            var item = itemRegistry.getItem(name);
            var state = item.getState();
            if (state instanceof UnDefType) {
                return 0.0;
            }
            if (state instanceof DecimalType d) {
                return d.doubleValue();
            }
            String s = state.toString();
            if (s == null || s.isEmpty() || "NULL".equals(s) || "UNDEF".equals(s)) {
                return 0.0;
            }
            int sp = s.indexOf(' ');
            return Double.parseDouble(sp > 0 ? s.substring(0, sp) : s);
        } catch (Throwable t) {
            return 0.0;
        }
    }

    private void publish(String item, double value) {
        try {
            itemRegistry.getItem(item);
            eventPublisher.post(ItemEventFactory.createStateEvent(item,
                    new DecimalType(Math.round(value * 1000.0) / 1000.0), null));
        } catch (Throwable t) {
            // item missing — skip silently
        }
    }
}

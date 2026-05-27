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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Statistics rollup — the binding-level "long-term statistics tier".
 *
 * <p>
 * openHAB charts fetch every raw persisted point in the requested window
 * and aggregate client-side. The power source items persist on
 * {@code everyChange} (~20k points/day), so a day window is fine but a
 * month/year window would return millions of points and choke the browser.
 * Home Assistant solves this with a separate downsampled statistics table;
 * this controller is the binding's equivalent.
 *
 * <p>
 * Once per day, just before midnight (23:58 zone-local), it reads the
 * finalized daily accumulators published by {@link CostAnalyticsController}
 * and {@link Co2TrackingController} and re-publishes them to dedicated
 * {@code EMS_Stat_*} items. Because those items change exactly once per day,
 * their InfluxDB series is one point per day — a month is ~30 points, a year
 * ~365 — so month/year/multi-year bar charts stay fast.
 *
 * <p>
 * Snapshotting at 23:58 (rather than at the midnight rollover) keeps each
 * day's value timestamped <em>within that calendar day</em>, so per-month and
 * per-year aggregations bucket correctly (a midnight snapshot would push
 * Dec 31 into January).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class StatisticsRollupController implements Controller {

    public static final String NAME = "statistics-rollup";

    /** Snapshot just before midnight so the value lands inside the right calendar day. */
    private static final LocalTime SNAPSHOT_TIME = LocalTime.of(23, 58);
    private static final Path CACHE_PATH = Path.of("/var/lib/openhab/cache/emsmanager-rollup-cache.json");
    private static final Gson GSON = new Gson();

    private static final Logger LOGGER = LoggerFactory.getLogger(StatisticsRollupController.class);

    private final EventPublisher eventPublisher;
    private final ItemRegistry itemRegistry;
    private final boolean enabled;
    private LocalDate lastSnapshotDay = LocalDate.MIN;

    public StatisticsRollupController(EventPublisher eventPublisher, ItemRegistry itemRegistry, boolean enabled) {
        this.eventPublisher = eventPublisher;
        this.itemRegistry = itemRegistry;
        this.enabled = enabled;
        loadFromDisk();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_STATS_ROLLUP;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public boolean shadowMode() {
        return false; // observer; only writes its own EMS_Stat_* items
    }

    @Override
    public List<SetpointRequest> evaluate(EnergyContext ctx) {
        ZonedDateTime now = ZonedDateTime.ofInstant(ctx.tickAt(), ZoneId.systemDefault());
        LocalDate today = now.toLocalDate();

        // Once per day, at/after 23:58, snapshot the finalized daily totals.
        if (!today.equals(lastSnapshotDay) && !now.toLocalTime().isBefore(SNAPSHOT_TIME)) {
            snapshot();
            lastSnapshotDay = today;
            saveToDisk();
            LOGGER.info("StatisticsRollup: captured daily totals for {} into EMS_Stat_* items", today);
        }
        return List.of();
    }

    private void snapshot() {
        double selfConsumption = readNumber(ITEM_EMS_SELFCONSUMPTION_KWH_DAY);
        double feedIn = readNumber(ITEM_EMS_FEEDIN_KWH_DAY);
        double supply = readNumber(ITEM_EMS_SUPPLY_KWH_DAY);
        double co2Net = readNumber(ITEM_EMS_CO2_NET_TODAY_KG);

        publishKwh(ITEM_EMS_STAT_SELFCONSUMPTION_KWH, selfConsumption);
        publishKwh(ITEM_EMS_STAT_FEEDIN_KWH, feedIn);
        publishKwh(ITEM_EMS_STAT_SUPPLY_KWH, supply);
        // Derived: total solar produced = self-used + exported; home consumption = self-used + imported.
        publishKwh(ITEM_EMS_STAT_SOLAR_KWH, selfConsumption + feedIn);
        publishKwh(ITEM_EMS_STAT_HOUSE_KWH, selfConsumption + supply);
        publishNumber(ITEM_EMS_STAT_CO2_KG, co2Net);
    }

    private void publishKwh(String itemName, double kwh) {
        try {
            eventPublisher.post(
                    ItemEventFactory.createStateEvent(itemName, new QuantityType<>(kwh, Units.KILOWATT_HOUR), null));
        } catch (Throwable t) {
            LOGGER.debug("publish {} failed: {}", itemName, t.getMessage());
        }
    }

    private void publishNumber(String itemName, double value) {
        try {
            eventPublisher.post(ItemEventFactory.createStateEvent(itemName, new DecimalType(value), null));
        } catch (Throwable t) {
            LOGGER.debug("publish {} failed: {}", itemName, t.getMessage());
        }
    }

    private double readNumber(String itemName) {
        try {
            Item item = itemRegistry.getItem(itemName);
            State state = item.getState();
            if (state instanceof UnDefType) {
                return 0.0;
            }
            String s = state.toString();
            if (s == null || "NULL".equals(s) || "UNDEF".equals(s)) {
                return 0.0;
            }
            int sp = s.indexOf(' ');
            String numPart = (sp > 0) ? s.substring(0, sp) : s;
            return Double.parseDouble(numPart);
        } catch (ItemNotFoundException | NumberFormatException e) {
            return 0.0;
        }
    }

    private void loadFromDisk() {
        try {
            if (!Files.exists(CACHE_PATH)) {
                return;
            }
            JsonObject obj = GSON.fromJson(Files.readString(CACHE_PATH), JsonObject.class);
            if (obj != null && obj.has("lastSnapshotDay")) {
                lastSnapshotDay = LocalDate.parse(obj.get("lastSnapshotDay").getAsString());
            }
        } catch (Throwable t) {
            LOGGER.debug("StatisticsRollup.load: {}", t.getMessage());
        }
    }

    private void saveToDisk() {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("lastSnapshotDay", lastSnapshotDay.toString());
            Files.writeString(CACHE_PATH, GSON.toJson(obj), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            LOGGER.debug("StatisticsRollup.save: {}", e.getMessage());
        }
    }
}

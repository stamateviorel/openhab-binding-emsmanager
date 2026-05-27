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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Long-term statistics rollups. Pure observer at priority 115 — reads the
 * day/month accumulator items published by {@link CostAnalyticsController}
 * and computes the longer-horizon items the EMS page displays.
 *
 * <p>
 * Per metric it keeps a {@link DailyRollup} that turns the cumulative
 * source counter into correct per-day amounts and a 365-day ring. Each
 * calendar-day rollover appends the <em>completed</em> day's amount (computed
 * from the last reading before the counter reset) — not the post-reset value.
 *
 * <p>
 * State persists to {@code /var/lib/openhab/cache/emsmanager-stats-cache.json}
 * (ring + baseline + last reading per metric + lastSeenDay) so a restart
 * neither loses history nor re-triggers a rollover for an already-closed day.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class LongTermStatsController implements Controller {

    public static final String NAME = "long-term-stats";

    private static final int RING_DAYS = 365;
    private static final Path CACHE_PATH = Path.of("/var/lib/openhab/cache/emsmanager-stats-cache.json");
    private static final Gson GSON = new Gson();

    /** {sourceItem, derived-items-prefix, unit} — kWh sources reset daily, EUR sources reset monthly. */
    private static final List<String[]> METRICS = List.of(
            new String[] { ITEM_EMS_SELFCONSUMPTION_KWH_DAY, "EMS_SelfConsumption_kWh", "kWh" },
            new String[] { ITEM_EMS_FEEDIN_KWH_DAY, "EMS_FeedIn_kWh", "kWh" },
            new String[] { ITEM_EMS_SUPPLY_KWH_DAY, "EMS_Supply_kWh", "kWh" },
            new String[] { ITEM_EMS_COST_EUR_MONTH, "EMS_Cost_EUR", "eur" },
            new String[] { ITEM_EMS_SAVINGS_EUR_MONTH, "EMS_Savings_EUR", "eur" },
            new String[] { ITEM_EMS_EARNINGS_EUR_MONTH, "EMS_Earnings_EUR", "eur" });

    private static final Logger LOGGER = LoggerFactory.getLogger(LongTermStatsController.class);

    private final EventPublisher eventPublisher;
    private final ItemRegistry itemRegistry;
    private LocalDate lastSeenDay = LocalDate.MIN;
    private final Map<String, DailyRollup> rollups = new HashMap<>();

    public LongTermStatsController(EventPublisher eventPublisher, ItemRegistry itemRegistry) {
        this.eventPublisher = eventPublisher;
        this.itemRegistry = itemRegistry;
        for (String[] m : METRICS) {
            rollups.put(m[1], new DailyRollup(RING_DAYS, isEnergy(m))); // kWh resets daily, EUR monthly
        }
        loadFromDisk();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_LONG_TERM_STATS;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean shadowMode() {
        return false; // observer, only writes its own items
    }

    @Override
    public List<SetpointRequest> evaluate(EnergyContext ctx) {
        LocalDate today = ZonedDateTime.ofInstant(ctx.tickAt(), ZoneId.systemDefault()).toLocalDate();

        boolean rollover = lastSeenDay != LocalDate.MIN && !today.equals(lastSeenDay);
        if (rollover) {
            for (String[] m : METRICS) {
                DailyRollup r = rollups.get(m[1]);
                if (r == null) {
                    continue;
                }
                double yesterday = r.rollover(readNumber(m[0]));
                publish(m[1] + "_Yesterday", yesterday, isEnergy(m));
            }
            lastSeenDay = today; // update BEFORE persisting so a restart won't re-roll this day
            saveToDisk();
            LOGGER.info("LongTermStats: rolled over to {}; updated yesterday/week/month/year items", today);
        } else {
            for (String[] m : METRICS) {
                DailyRollup r = rollups.get(m[1]);
                if (r != null) {
                    r.observe(readNumber(m[0]));
                }
            }
            if (lastSeenDay == LocalDate.MIN) {
                lastSeenDay = today; // anchor first day and persist so a same-day restart doesn't re-roll
                saveToDisk();
            }
        }

        publishAllDerived(ctx);
        return List.of();
    }

    /** Each tick — push rollups derived from the ring + today's running partial. */
    private void publishAllDerived(EnergyContext ctx) {
        int dayOfYear = ZonedDateTime.ofInstant(ctx.tickAt(), ZoneId.systemDefault()).getDayOfYear();
        for (String[] m : METRICS) {
            DailyRollup r = rollups.get(m[1]);
            if (r == null) {
                continue;
            }
            boolean energy = isEnergy(m);
            double today = r.dayAmount();
            publish(m[1] + "_Last7Days", r.sumLast(7) + today, energy);
            publish(m[1] + "_Last30Days", r.sumLast(30) + today, energy);
            publish(m[1] + "_Year", r.sumLast(dayOfYear - 1) + today, energy);
        }
    }

    private void publish(String itemName, double value, boolean isEnergy) {
        try {
            if (isEnergy) {
                eventPublisher.post(ItemEventFactory.createStateEvent(itemName,
                        new QuantityType<>(value, Units.KILOWATT_HOUR), null));
            } else {
                eventPublisher.post(ItemEventFactory.createStateEvent(itemName, new DecimalType(value), null));
            }
        } catch (Throwable t) {
            LOGGER.debug("publish {} failed: {}", itemName, t.getMessage());
        }
    }

    private static boolean isEnergy(String[] metric) {
        return "kWh".equals(metric[2]);
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
            if (obj == null) {
                return;
            }
            if (obj.has("lastSeenDay")) {
                lastSeenDay = LocalDate.parse(obj.get("lastSeenDay").getAsString());
            }
            JsonObject rj = obj.has("rollups") ? obj.getAsJsonObject("rollups") : null;
            if (rj != null) {
                for (Map.Entry<String, JsonElement> e : rj.entrySet()) {
                    DailyRollup r = rollups.get(e.getKey());
                    if (r == null) {
                        continue;
                    }
                    JsonObject mo = e.getValue().getAsJsonObject();
                    java.util.List<Double> vals = new java.util.ArrayList<>();
                    if (mo.has("ring")) {
                        for (JsonElement v : mo.getAsJsonArray("ring")) {
                            vals.add(v.getAsDouble());
                        }
                    }
                    double baseline = mo.has("baseline") ? mo.get("baseline").getAsDouble() : Double.NaN;
                    double last = mo.has("last") ? mo.get("last").getAsDouble() : Double.NaN;
                    r.restore(vals, baseline, last);
                }
            }
            LOGGER.info("LongTermStats: restored from disk, lastSeenDay={}, ringSizes={}", lastSeenDay,
                    rollups.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().size()).toList());
        } catch (Throwable t) {
            LOGGER.debug("LongTermStats.load: {}", t.getMessage());
        }
    }

    private void saveToDisk() {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("lastSeenDay", lastSeenDay.toString());
            JsonObject rj = new JsonObject();
            for (Map.Entry<String, DailyRollup> e : rollups.entrySet()) {
                DailyRollup r = e.getValue();
                JsonObject mo = new JsonObject();
                JsonArray arr = new JsonArray();
                for (Double v : r.ring()) {
                    arr.add(v);
                }
                mo.add("ring", arr);
                if (!Double.isNaN(r.baseline())) {
                    mo.addProperty("baseline", r.baseline());
                }
                if (!Double.isNaN(r.last())) {
                    mo.addProperty("last", r.last());
                }
                rj.add(e.getKey(), mo);
            }
            obj.add("rollups", rj);
            Files.writeString(CACHE_PATH, GSON.toJson(obj), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            LOGGER.debug("LongTermStats.save: {}", e.getMessage());
        }
    }
}

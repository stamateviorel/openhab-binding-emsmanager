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
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.openhab.binding.emsmanager.internal.util.CachePaths;
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
 * Cost / savings analytics. Pure observer - emits no setpoint requests.
 * Accumulates each tick:
 * <ul>
 * <li>Supply kWh = int max(0, -grid) dt</li>
 * <li>Feed-in kWh = int max(0, grid) dt</li>
 * <li>Self-consumption kWh = int max(0, solar - feed_in) dt</li>
 * <li>Cost EUR = supply_kWh x tariff_price (per-tick, integrates time-of-use)</li>
 * <li>Savings EUR = self_consumption_kWh x tariff_price</li>
 * <li>Earnings EUR = feed_in_kWh x injection_price (config param)</li>
 * </ul>
 *
 * <p>
 * These are running totals with no other source of truth, so they are snapshotted to the binding's
 * own cache and restored from it first. Item state is only a fallback: it restores asynchronously
 * and partially, and seeding an all-time counter from an item that has not been restored yet reads
 * as zero and then overwrites the real value. Day/month rollovers reset their respective
 * accumulators at zone-local midnight / month-start.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class CostAnalyticsController implements Controller {

    public static final String NAME = "cost-analytics";

    private static final Logger LOGGER = LoggerFactory.getLogger(CostAnalyticsController.class);

    private final EventPublisher eventPublisher;
    private final double injectionPriceEurPerKWh;

    private long lastTickMs = 0L;
    private LocalDate lastDay = LocalDate.MIN;
    private int lastMonth = -1;
    private int lastYear = -1;

    // Restore-on-(re)init: defer the first publish until the persisted items are
    // readable, so a transient UNDEF during a registry reload never overwrites good
    // totals with 0 (root cause of the 2026-05-31 daily-counter wipe).
    private static final String CACHE_FILE = "emsmanager-cost-cache.json";
    /** ~5 minutes at a 5 s tick: often enough to lose almost nothing, rare enough not to churn the disk. */
    private static final int SAVE_EVERY_TICKS = 60;
    private static final Gson GSON = new Gson();

    private int ticksSinceSave = 0;
    private boolean restored = false;
    private @Nullable ItemRegistry itemRegistry;

    // Accumulators (kWh + €).
    private double selfConsumptionKwhDay = 0.0;
    private double selfConsumptionKwhMonth = 0.0;
    private double feedInKwhDay = 0.0;
    private double feedInKwhMonth = 0.0;
    private double supplyKwhDay = 0.0;
    private double supplyKwhMonth = 0.0;
    private double costEurMonth = 0.0;
    private double costEurTotal = 0.0;
    private double savingsEurMonth = 0.0;
    private double savingsEurTotal = 0.0;
    private double earningsEurMonth = 0.0;
    private double earningsEurTotal = 0.0;

    public CostAnalyticsController(EventPublisher eventPublisher, double injectionPriceEurPerKWh) {
        this.eventPublisher = eventPublisher;
        this.injectionPriceEurPerKWh = injectionPriceEurPerKWh;
    }

    /**
     * Remember the registry and try to restore the accumulators from persisted item
     * state so we resume cleanly across (re)init. If the source items aren't readable
     * yet (transient UNDEF during a registry reload), DEFER — {@link #evaluate} retries
     * every tick and we never publish 0 over a good total.
     */
    public void initFromItems(ItemRegistry items) {
        this.itemRegistry = items;
        this.restored = restoreFrom(items);
    }

    /** @return true once the accumulators were restored from readable items. */
    private boolean restoreFrom(ItemRegistry items) {
        // Own snapshot first. Item restore is asynchronous and partial: a counter still UNDEF when
        // this runs used to be seeded to zero and then written straight back over the good value,
        // which is how 465 EUR of all-time savings was destroyed by a restart on 2026-08-30 while
        // cost and earnings - read microseconds earlier - survived.
        boolean fromSnapshot = loadSnapshot();

        double scDay = readNumber(items, ITEM_EMS_SELFCONSUMPTION_KWH_DAY);
        if (Double.isNaN(scDay) && !fromSnapshot) {
            LOGGER.info("CostAnalytics restore deferred — no snapshot and source items not ready (UNDEF)");
            return false;
        }

        selfConsumptionKwhDay = pick(scDay, selfConsumptionKwhDay);
        selfConsumptionKwhMonth = pick(readNumber(items, ITEM_EMS_SELFCONSUMPTION_KWH_MONTH), selfConsumptionKwhMonth);
        feedInKwhDay = pick(readNumber(items, ITEM_EMS_FEEDIN_KWH_DAY), feedInKwhDay);
        feedInKwhMonth = pick(readNumber(items, ITEM_EMS_FEEDIN_KWH_MONTH), feedInKwhMonth);
        supplyKwhDay = pick(readNumber(items, ITEM_EMS_SUPPLY_KWH_DAY), supplyKwhDay);
        supplyKwhMonth = pick(readNumber(items, ITEM_EMS_SUPPLY_KWH_MONTH), supplyKwhMonth);
        costEurMonth = pick(readNumber(items, ITEM_EMS_COST_EUR_MONTH), costEurMonth);
        costEurTotal = highest(readNumber(items, ITEM_EMS_COST_EUR_TOTAL), costEurTotal);
        savingsEurMonth = pick(readNumber(items, ITEM_EMS_SAVINGS_EUR_MONTH), savingsEurMonth);
        savingsEurTotal = highest(readNumber(items, ITEM_EMS_SAVINGS_EUR_TOTAL), savingsEurTotal);
        earningsEurMonth = pick(readNumber(items, ITEM_EMS_EARNINGS_EUR_MONTH), earningsEurMonth);
        earningsEurTotal = highest(readNumber(items, ITEM_EMS_EARNINGS_EUR_TOTAL), earningsEurTotal);
        LOGGER.info(
                "CostAnalytics restored (snapshot={}): dayKWh sc={} fi={} sup={}, monthEUR cost={} sav={} earn={}, totalEUR cost={} sav={}",
                fromSnapshot, fmt(selfConsumptionKwhDay), fmt(feedInKwhDay), fmt(supplyKwhDay), fmt(costEurMonth),
                fmt(savingsEurMonth), fmt(earningsEurMonth), fmt(costEurTotal), fmt(savingsEurTotal));
        return true;
    }

    // Visible for testing - the running totals whose loss is not recoverable from item history.

    double savingsEurTotal() {
        return savingsEurTotal;
    }

    double costEurTotal() {
        return costEurTotal;
    }

    boolean isRestored() {
        return restored;
    }

    /** An unreadable item leaves whatever the snapshot already gave us, rather than zeroing it. */
    private static double pick(double fromItem, double current) {
        return Double.isNaN(fromItem) ? current : fromItem;
    }

    /**
     * All-time counters only ever go up, so restoring can only ever raise one. This is what makes a
     * half-restored registry survivable: the worse of the two readings cannot win.
     */
    private static double highest(double fromItem, double current) {
        return Double.isNaN(fromItem) ? current : Math.max(fromItem, current);
    }

    private static double nz(double v) {
        return Double.isNaN(v) ? 0.0 : v;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_COST_ANALYTICS;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean shadowMode() {
        return false; // observer — shadow has no meaning here
    }

    @Override
    public List<SetpointRequest> evaluate(EnergyContext ctx) {
        // Resume guard: until the accumulators are restored from readable items, do NOT
        // publish — a fresh controller starts at 0 and publishing would wipe good totals.
        // Retry the restore each tick until the items become available.
        if (!restored) {
            ItemRegistry ir = itemRegistry;
            if (ir == null || !(restored = restoreFrom(ir))) {
                return List.of();
            }
        }
        long nowMs = ctx.tickAt().toEpochMilli();
        if (lastTickMs == 0L) {
            lastTickMs = nowMs;
            return List.of();
        }
        long dtMs = nowMs - lastTickMs;
        lastTickMs = nowMs;
        if (dtMs <= 0L || dtMs > 60_000L) {
            // Skip suspicious dt (e.g. clock skew, very long gap after sleep).
            return List.of();
        }

        // Day / month rollover BEFORE we add the new tick's contribution.
        ZonedDateTime zdt = ZonedDateTime.ofInstant(ctx.tickAt(), ZoneId.systemDefault());
        LocalDate today = zdt.toLocalDate();
        int month = zdt.getMonthValue();
        int year = zdt.getYear();
        if (!today.equals(lastDay) && lastDay != LocalDate.MIN) {
            selfConsumptionKwhDay = 0.0;
            feedInKwhDay = 0.0;
            supplyKwhDay = 0.0;
            LOGGER.info("CostAnalytics day rollover — kWh_Day accumulators reset");
        }
        if ((month != lastMonth || year != lastYear) && lastMonth != -1) {
            selfConsumptionKwhMonth = 0.0;
            feedInKwhMonth = 0.0;
            supplyKwhMonth = 0.0;
            costEurMonth = 0.0;
            savingsEurMonth = 0.0;
            earningsEurMonth = 0.0;
            LOGGER.info("CostAnalytics month rollover — Month accumulators reset");
            saveSnapshot();
        }
        lastDay = today;
        lastMonth = month;
        lastYear = year;

        // Increments — convert W × ms → kWh: W × (dtMs / 3_600_000_000) for kWh.
        double hours = dtMs / 3_600_000.0;
        double grid = ctx.gridLoadRawW();
        double solar = ctx.solarLoadW();
        if (Double.isNaN(grid) || Double.isNaN(solar)) {
            return List.of();
        }

        double feedInW = Math.max(0.0, grid);
        double supplyW = Math.max(0.0, -grid);
        double selfConsumptionW = Math.max(0.0, solar - feedInW);

        double dFeedInKwh = (feedInW / 1000.0) * hours;
        double dSupplyKwh = (supplyW / 1000.0) * hours;
        double dSelfConsumptionKwh = (selfConsumptionW / 1000.0) * hours;

        feedInKwhDay += dFeedInKwh;
        feedInKwhMonth += dFeedInKwh;
        supplyKwhDay += dSupplyKwh;
        supplyKwhMonth += dSupplyKwh;
        selfConsumptionKwhDay += dSelfConsumptionKwh;
        selfConsumptionKwhMonth += dSelfConsumptionKwh;

        // € — use the live tariff price (may vary tick-by-tick for ToU/dynamic).
        double tariff = ctx.tariffPriceNowEurPerKWh();
        if (!Double.isNaN(tariff)) {
            double dCost = dSupplyKwh * tariff;
            double dSavings = dSelfConsumptionKwh * tariff;
            costEurMonth += dCost;
            costEurTotal += dCost;
            savingsEurMonth += dSavings;
            savingsEurTotal += dSavings;
        }
        double dEarnings = dFeedInKwh * injectionPriceEurPerKWh;
        earningsEurMonth += dEarnings;
        earningsEurTotal += dEarnings;

        publish();
        if (++ticksSinceSave >= SAVE_EVERY_TICKS) {
            ticksSinceSave = 0;
            saveSnapshot();
        }
        return List.of();
    }

    /** Field names are the wire format; keep them stable or a snapshot silently reads as zeros. */
    private JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("selfConsumptionKwhDay", selfConsumptionKwhDay);
        o.addProperty("selfConsumptionKwhMonth", selfConsumptionKwhMonth);
        o.addProperty("feedInKwhDay", feedInKwhDay);
        o.addProperty("feedInKwhMonth", feedInKwhMonth);
        o.addProperty("supplyKwhDay", supplyKwhDay);
        o.addProperty("supplyKwhMonth", supplyKwhMonth);
        o.addProperty("costEurMonth", costEurMonth);
        o.addProperty("costEurTotal", costEurTotal);
        o.addProperty("savingsEurMonth", savingsEurMonth);
        o.addProperty("savingsEurTotal", savingsEurTotal);
        o.addProperty("earningsEurMonth", earningsEurMonth);
        o.addProperty("earningsEurTotal", earningsEurTotal);
        return o;
    }

    /** @return true if a snapshot was found and applied. */
    boolean loadSnapshot() {
        try {
            Path path = CachePaths.cacheFile(CACHE_FILE);
            if (!Files.exists(path)) {
                return false;
            }
            JsonObject o = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (o == null) {
                return false;
            }
            selfConsumptionKwhDay = num(o, "selfConsumptionKwhDay");
            selfConsumptionKwhMonth = num(o, "selfConsumptionKwhMonth");
            feedInKwhDay = num(o, "feedInKwhDay");
            feedInKwhMonth = num(o, "feedInKwhMonth");
            supplyKwhDay = num(o, "supplyKwhDay");
            supplyKwhMonth = num(o, "supplyKwhMonth");
            costEurMonth = num(o, "costEurMonth");
            costEurTotal = num(o, "costEurTotal");
            savingsEurMonth = num(o, "savingsEurMonth");
            savingsEurTotal = num(o, "savingsEurTotal");
            earningsEurMonth = num(o, "earningsEurMonth");
            earningsEurTotal = num(o, "earningsEurTotal");
            return true;
        } catch (Throwable t) {
            LOGGER.warn("CostAnalytics snapshot unreadable, falling back to item state: {}", t.getMessage());
            return false;
        }
    }

    void saveSnapshot() {
        try {
            Path path = CachePaths.cacheFile(CACHE_FILE);
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(toJson()), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Throwable t) {
            LOGGER.debug("CostAnalytics snapshot save failed: {}", t.getMessage());
        }
    }

    private static double num(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : 0.0;
    }

    private void publish() {
        publishKwh(ITEM_EMS_SELFCONSUMPTION_KWH_DAY, selfConsumptionKwhDay);
        publishKwh(ITEM_EMS_SELFCONSUMPTION_KWH_MONTH, selfConsumptionKwhMonth);
        publishKwh(ITEM_EMS_FEEDIN_KWH_DAY, feedInKwhDay);
        publishKwh(ITEM_EMS_FEEDIN_KWH_MONTH, feedInKwhMonth);
        publishKwh(ITEM_EMS_SUPPLY_KWH_DAY, supplyKwhDay);
        publishKwh(ITEM_EMS_SUPPLY_KWH_MONTH, supplyKwhMonth);
        publishEur(ITEM_EMS_COST_EUR_MONTH, costEurMonth);
        publishEur(ITEM_EMS_COST_EUR_TOTAL, costEurTotal);
        publishEur(ITEM_EMS_SAVINGS_EUR_MONTH, savingsEurMonth);
        publishEur(ITEM_EMS_SAVINGS_EUR_TOTAL, savingsEurTotal);
        publishEur(ITEM_EMS_EARNINGS_EUR_MONTH, earningsEurMonth);
        publishEur(ITEM_EMS_EARNINGS_EUR_TOTAL, earningsEurTotal);
    }

    private void publishKwh(String itemName, double kwh) {
        try {
            eventPublisher.post(
                    ItemEventFactory.createStateEvent(itemName, new QuantityType<>(kwh, Units.KILOWATT_HOUR), null));
        } catch (Throwable t) {
            LOGGER.debug("publish kWh to {} failed: {}", itemName, t.getMessage());
        }
    }

    private void publishEur(String itemName, double eur) {
        try {
            eventPublisher.post(ItemEventFactory.createStateEvent(itemName, new DecimalType(eur), null));
        } catch (Throwable t) {
            LOGGER.debug("publish € to {} failed: {}", itemName, t.getMessage());
        }
    }

    /**
     * Reads an item's numeric value. Returns {@link Double#NaN} (NOT 0) when the item
     * is UNDEF/NULL/missing, so callers can tell "not readable yet" from "genuinely 0"
     * and avoid clobbering good totals during a transient registry reload.
     */
    private static double readNumber(ItemRegistry items, String itemName) {
        try {
            Item item = items.getItem(itemName);
            State state = item.getState();
            if (state instanceof UnDefType) {
                return Double.NaN;
            }
            String s = state.toString();
            if (s == null || "NULL".equals(s) || "UNDEF".equals(s) || s.isEmpty()) {
                return Double.NaN;
            }
            int sp = s.indexOf(' ');
            String numPart = (sp > 0) ? s.substring(0, sp) : s;
            return Double.parseDouble(numPart);
        } catch (ItemNotFoundException | NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String fmt(double d) {
        return String.format("%.3f", d);
    }
}

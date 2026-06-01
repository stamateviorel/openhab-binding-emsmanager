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

/**
 * Cost / savings analytics. Pure observer — emits no setpoint requests.
 * Accumulates each tick:
 * <ul>
 * <li>Supply kWh = ∫ max(0, −grid) dt</li>
 * <li>Feed-in kWh = ∫ max(0, grid) dt</li>
 * <li>Self-consumption kWh = ∫ max(0, solar − feed_in) dt</li>
 * <li>Cost € = supply_kWh × tariff_price (per-tick, integrates time-of-use)</li>
 * <li>Savings € = self_consumption_kWh × tariff_price</li>
 * <li>Earnings € = feed_in_kWh × injection_price (config param)</li>
 * </ul>
 *
 * <p>
 * On {@link #initFromItems(ItemRegistry)} the accumulators load from the
 * persisted item state (requires a persistence service with restore-on-startup),
 * so a bridge restart doesn't double-count. Day/month rollovers reset their
 * respective accumulators at zone-local midnight / month-start.
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
        double scDay = readNumber(items, ITEM_EMS_SELFCONSUMPTION_KWH_DAY);
        if (Double.isNaN(scDay)) {
            LOGGER.info("CostAnalytics restore deferred — source items not ready (UNDEF)");
            return false;
        }
        selfConsumptionKwhDay = scDay;
        selfConsumptionKwhMonth = nz(readNumber(items, ITEM_EMS_SELFCONSUMPTION_KWH_MONTH));
        feedInKwhDay = nz(readNumber(items, ITEM_EMS_FEEDIN_KWH_DAY));
        feedInKwhMonth = nz(readNumber(items, ITEM_EMS_FEEDIN_KWH_MONTH));
        supplyKwhDay = nz(readNumber(items, ITEM_EMS_SUPPLY_KWH_DAY));
        supplyKwhMonth = nz(readNumber(items, ITEM_EMS_SUPPLY_KWH_MONTH));
        costEurMonth = nz(readNumber(items, ITEM_EMS_COST_EUR_MONTH));
        costEurTotal = nz(readNumber(items, ITEM_EMS_COST_EUR_TOTAL));
        savingsEurMonth = nz(readNumber(items, ITEM_EMS_SAVINGS_EUR_MONTH));
        savingsEurTotal = nz(readNumber(items, ITEM_EMS_SAVINGS_EUR_TOTAL));
        earningsEurMonth = nz(readNumber(items, ITEM_EMS_EARNINGS_EUR_MONTH));
        earningsEurTotal = nz(readNumber(items, ITEM_EMS_EARNINGS_EUR_TOTAL));
        LOGGER.info("CostAnalytics restored from items: dayKWh sc={} fi={} sup={}, monthEUR cost={} sav={} earn={}",
                fmt(selfConsumptionKwhDay), fmt(feedInKwhDay), fmt(supplyKwhDay), fmt(costEurMonth),
                fmt(savingsEurMonth), fmt(earningsEurMonth));
        return true;
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
        return List.of();
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

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
package org.openhab.binding.emsmanager.internal.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.ems.EnergyConsumer;
import org.openhab.binding.emsmanager.internal.ems.EnergyProvider;
import org.openhab.binding.emsmanager.internal.ems.MetadataParticipantScanner;
import org.openhab.binding.emsmanager.internal.ems.ProviderRole;
import org.openhab.core.common.registry.AbstractProvider;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.MetadataRegistry;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.ui.components.RootUIComponent;
import org.openhab.core.ui.components.UIComponent;
import org.openhab.core.ui.components.UIComponentProvider;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ships the Energy section's MainUI pages <b>from the binding</b> — a read-only
 * {@link UIComponentProvider} for the {@code ui:page} namespace. MainUI's component registry
 * aggregates every registered provider (managed + these), so the pages are served at
 * {@code /rest/ui/components/ui:page} and rendered natively, appearing in the left sidebar via
 * {@code config.sidebar}. Provider-served (never written to JSONDB) so they are read-only and
 * vanish cleanly when the binding stops — nothing to orphan, no openhab-webui fork.
 *
 * <p>
 * The dashboard is <b>driven by the site's {@code energy:}-tagged participants</b> (discovered via
 * {@link MetadataParticipantScanner}) plus the engine's published site energy level
 * ({@code EMS_EnergyLevel}) — no hardcoded item names. A two-tab layout: <em>Now</em> (energy-level
 * hero + status banner, live gauges, today's KPIs, a card per producer/consumer, and a money/CO2
 * row) and <em>Charts</em> (the power series with the solar forecast drawn ahead of now).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(service = UIComponentProvider.class, immediate = true)
public class EnergyUiProvider extends AbstractProvider<RootUIComponent> implements UIComponentProvider {

    /** MainUI pages live in the {@code ui:page} component namespace. */
    public static final String NAMESPACE = "ui:page";

    private static final String P_ROOT = "emsmanager_energy";
    private static final String P_PAST = "emsmanager_energy_past";
    private static final String P_FUTURE = "emsmanager_energy_future";
    private static final String P_NOW = "emsmanager_energy_now";
    private static final String P_CHARTS = "emsmanager_energy_charts";

    /** The engine-published site energy level items (see EmsManagerBridgeHandler). */
    private static final String ITEM_LEVEL_TEXT = "EMS_EnergyLevel_Text";
    private static final String ITEM_LEVEL = "EMS_EnergyLevel";
    /** Number:Power item on the forecast-solar forecastSeries channel, persisted with `forecast`. */
    private static final String ITEM_FORECAST_SERIES = "EMS_Forecast_Series";

    // Binding-published items ported from the retired hand-built EMS pages (all owned by the
    // emsmanager services, so this stays portable — no site-specific item names).
    private static final String I_FORECAST_TODAY = "EMS_Forecast_Today_kWh";
    private static final String I_SELFCONS_DAY = "EMS_SelfConsumption_kWh_Day";
    private static final String I_SUPPLY_DAY = "EMS_Supply_kWh_Day";
    private static final String I_TARIFF_NOW = "EMS_Tariff_Now_EurPerKWh";
    private static final String I_CAP_PEAK = "EMS_Capacity_Monthly_Peak";

    // Sun ahead.
    private static final String I_FORECAST_NOW = "EMS_Forecast_Now";
    private static final String I_FORECAST_1H = "EMS_Forecast_Next_1h";
    private static final String I_FORECAST_3H = "EMS_Forecast_Next_3h";
    private static final String I_FORECAST_PEAK_AT = "EMS_Forecast_Peak_Today_At";

    // Battery and the optimizer's own plan for the next 24 hours.
    private static final String I_BATTERY_SETPOINT = "EMS_Battery_Setpoint_W";
    private static final String I_OPT_NEXT_CHARGE = "EMS_Optimizer_Next_Charge";
    private static final String I_OPT_NEXT_DISCHARGE = "EMS_Optimizer_Next_Discharge";
    private static final String I_OPT_PLAN_24H = "EMS_Optimizer_Plan_24h";
    private static final String I_CLOCK_HOUR = "EMS_Clock_Hour";

    // Live setpoints - a command on these rewrites Thing config, so the dashboard can tune the EMS.
    private static final String I_SET_BOILER_TARGET = "EMS_Set_Boiler_Target_kWh";
    private static final String I_SET_BOILER_READY_BY = "EMS_Set_Boiler_ReadyBy_Hour";
    private static final String I_SET_GRID_MARGIN = "EMS_Set_Grid_Margin_W";
    private static final String I_SET_CAPACITY_BUDGET = "EMS_Set_Capacity_Budget_W";
    private static final String I_BROWSE_SUPPLY_DELTA = "EMS_Browse_Supply_Delta_Pct";
    private static final String I_BROWSE_SELFCONS_DELTA = "EMS_Browse_SelfConsumption_Delta_Pct";
    private static final String I_BROWSE_FEEDIN_DELTA = "EMS_Browse_FeedIn_Delta_Pct";
    private static final String I_BROWSE_COST_DELTA = "EMS_Browse_Cost_Delta_Pct";

    // The capacity tariff - the quarter-hour peak this country bills on.
    private static final String I_CAP_QUARTER = "EMS_Capacity_Current_Quarter";
    private static final String I_CAP_PROJECTED = "EMS_Capacity_Projected";
    private static final String I_CAP_STATUS = "EMS_Capacity_Status";
    private static final String I_CAP_WOULD_EXCEED = "EMS_Capacity_Would_Exceed";

    // Money and carbon over the periods the services actually keep.
    private static final String I_COST_MONTH = "EMS_Cost_EUR_Month";
    private static final String I_SAVINGS_MONTH = "EMS_Savings_EUR_Month";
    private static final String I_EARNINGS_MONTH = "EMS_Earnings_EUR_Month";
    private static final String I_CO2_SAVED_TODAY = "EMS_CO2_Saved_Today_kg";

    // Things worth surfacing only when they have something to say.
    private static final String I_ANOMALY_COUNT = "EMS_Anomaly_Count_Today";
    private static final String I_BOILER_PLAN = "EMS_BoilerPlan_Status";
    private static final String I_BOILER_DELIVERED = "EMS_BoilerPlan_Delivered_kWh";
    private static final String I_TARIFF_CHEAPEST_AT = "EMS_Tariff_Cheapest_Hour_Start";
    private static final String I_TARIFF_DEAREST_AT = "EMS_Tariff_Expensive_Hour_Start";
    private static final String I_TARIFF_NEXT_1H = "EMS_Tariff_Next_1h_Price";
    private static final String I_TARIFF_SCHEDULE = "EMS_Tariff_Schedule24h_CSV";
    private static final String I_TARIFF_MIN = "EMS_Tariff_Today_Min";
    private static final String I_TARIFF_MAX = "EMS_Tariff_Today_Max";
    private static final String I_TARIFF_SOURCE = "EMS_Tariff_Source";
    private static final String I_FORECAST_HOURLY = "EMS_Forecast_Today_Hourly_CSV";
    private static final String I_FORECAST_TOMORROW = "EMS_Forecast_Tomorrow_kWh";
    private static final String I_FORECAST_6H = "EMS_Forecast_Next_6h";
    private static final String I_BOILER_WINDOW = "EMS_BoilerPlan_Window";
    private static final String I_HP_PREHEAT_AT = "EMS_HeatPump_Plan_PreheatAt";

    /**
     * The spans this page reports, past and future alike, in the order a person asks about them.
     * <p>
     * Day, month, year - the same three everywhere, so a figure means the same thing on whichever page it appears.
     */
    private static final String[][] PERIODS = { { "_Day", "Today" }, { "_Yesterday", "Yesterday" },
            { "_Month", "This month" }, { "_Year", "This year" } };

    private static final String P_CONTROL = "emsmanager_energy_control";

    private static final String P_CIRCUITS = "emsmanager_energy_circuits";
    private static final String P_CARS = "emsmanager_energy_cars";

    /** Binding-published switches the control page offers. */
    private static final String I_BOILER_OVERRIDE = "EMS_Boiler_User_Override";
    private static final String I_LAST_DECISION = "EMS_Bridge_Last_Decision";
    private static final String I_SHADOW_MODE = "EMS_Bridge_Shadow_Mode";
    private static final String I_SIZING_RUN = "EMS_BatterySizing_Run";
    private static final String I_SIZING_KWH = "EMS_BatterySizing_OptimalKwh";
    private static final String I_SIZING_PAYBACK = "EMS_BatterySizing_PaybackYears";
    private static final String I_COMPARE_RUN = "EMS_TariffComparison_Run";
    private static final String I_COMPARE_CHEAPEST = "EMS_TariffComparison_Cheapest";
    private static final String I_COMPARE_SUMMARY = "EMS_TariffComparison_Summary";
    private static final String I_PEAK_ENABLED = "PeakShaving_Enabled";
    private static final String I_PEAK_ENGAGE = "PeakShaving_Manual_Engage";
    private static final String I_PEAK_RESET = "PeakShaving_Manual_Reset";
    private static final String I_DM_TRACKED = "EMS_DeviceMeter_Tracked_W";
    private static final String I_BROWSE_SCALE = "EMS_Browse_Scale";
    private static final String I_BROWSE_BACK = "EMS_Browse_Back";
    private static final String I_BROWSE_LABEL = "EMS_Browse_Label";
    private static final String ITEM_BROWSE_SUPPLY = "EMS_Browse_Supply_kWh";
    private static final String ITEM_BROWSE_SELFCONS = "EMS_Browse_SelfConsumption_kWh";
    private static final String ITEM_BROWSE_FEEDIN = "EMS_Browse_FeedIn_kWh";
    private static final String ITEM_BROWSE_COST = "EMS_Browse_Cost_EUR";
    private static final String I_DM_UNTRACKED = "EMS_DeviceMeter_Untracked_W";

    private final Logger logger = LoggerFactory.getLogger(EnergyUiProvider.class);
    private final MetadataRegistry metadataRegistry;
    private final ItemRegistry itemRegistry;
    private final @org.eclipse.jdt.annotation.Nullable ThingRegistry thingRegistry;
    private final @org.eclipse.jdt.annotation.Nullable ItemChannelLinkRegistry linkRegistry;
    private volatile List<RootUIComponent> pages = new ArrayList<>();

    @Activate
    public EnergyUiProvider(@Reference MetadataRegistry metadataRegistry, @Reference ItemRegistry itemRegistry,
            @Reference ThingRegistry thingRegistry, @Reference ItemChannelLinkRegistry linkRegistry) {
        this.metadataRegistry = metadataRegistry;
        this.itemRegistry = itemRegistry;
        this.thingRegistry = thingRegistry;
        this.linkRegistry = linkRegistry;
        this.pages = computePages();
        metadataRegistry.addRegistryChangeListener(metadataListener);
        thingRegistry.addRegistryChangeListener(thingListener);
        logger.info("EnergyUiProvider activated — Energy section served from the binding (namespace {})", NAMESPACE);
    }

    /** A provider that only knows the Items: what a test builds, and what a site without Things gets. */
    EnergyUiProvider(MetadataRegistry metadataRegistry, ItemRegistry itemRegistry) {
        this.metadataRegistry = metadataRegistry;
        this.itemRegistry = itemRegistry;
        this.thingRegistry = null;
        this.linkRegistry = null;
        this.pages = computePages();
        metadataRegistry.addRegistryChangeListener(metadataListener);
    }

    @Deactivate
    public void deactivate() {
        metadataRegistry.removeRegistryChangeListener(metadataListener);
        ThingRegistry things = thingRegistry;
        if (things != null) {
            things.removeRegistryChangeListener(thingListener);
        }
    }

    private final org.openhab.core.common.registry.RegistryChangeListener<org.openhab.core.thing.Thing> thingListener = new org.openhab.core.common.registry.RegistryChangeListener<>() {
        @Override
        public void added(org.openhab.core.thing.Thing element) {
            if (isOurs(element)) {
                refresh();
            }
        }

        @Override
        public void removed(org.openhab.core.thing.Thing element) {
            if (isOurs(element)) {
                refresh();
            }
        }

        @Override
        public void updated(org.openhab.core.thing.Thing oldElement, org.openhab.core.thing.Thing element) {
            if (isOurs(element)) {
                refresh();
            }
        }
    };

    private static boolean isOurs(org.openhab.core.thing.Thing thing) {
        return org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.BINDING_ID
                .equals(thing.getThingTypeUID().getBindingId());
    }

    @Override
    public String getNamespace() {
        return NAMESPACE;
    }

    @Override
    public Collection<RootUIComponent> getAll() {
        return pages;
    }

    /** Rebuild every page from the CURRENT energy: tags — the dashboard is the tagged model. */
    private List<RootUIComponent> computePages() {
        MetadataParticipantScanner scanner = new MetadataParticipantScanner(metadataRegistry);
        List<EnergyProvider> providers = scanner.providers();
        List<EnergyConsumer> consumers = scanner.consumers();
        SiteModel site = SiteModel.from(thingRegistry, linkRegistry);
        List<RootUIComponent> out = new ArrayList<>();
        out.add(buildTabsPage());
        out.add(buildPastPage());
        out.add(buildFuturePage(site));
        out.add(buildNowPage(providers, site));
        out.add(buildControlPage(consumers, site));
        out.add(buildCarsPage(site));
        out.add(buildChartsPage(providers, consumers));
        out.add(buildCircuitsChartPage());
        return out;
    }

    /** On any energy: tag change, rebuild + notify the registry so MainUI re-fetches — live config. */
    private void refresh() {
        List<RootUIComponent> old = pages;
        List<RootUIComponent> now = computePages();
        pages = now;
        java.util.Map<String, RootUIComponent> byUid = new java.util.HashMap<>();
        for (RootUIComponent o : old) {
            byUid.put(o.getUID(), o);
        }
        for (RootUIComponent np : now) {
            RootUIComponent op = byUid.remove(np.getUID());
            if (op != null) {
                notifyListenersAboutUpdatedElement(op, np);
            } else {
                notifyListenersAboutAddedElement(np);
            }
        }
        for (RootUIComponent gone : byUid.values()) {
            notifyListenersAboutRemovedElement(gone);
        }
        logger.debug("EnergyUiProvider refreshed from an energy: tag change");
    }

    private final org.openhab.core.common.registry.RegistryChangeListener<org.openhab.core.items.Metadata> metadataListener = new org.openhab.core.common.registry.RegistryChangeListener<>() {
        @Override
        public void added(org.openhab.core.items.Metadata element) {
            if (isEnergy(element)) {
                refresh();
            }
        }

        @Override
        public void removed(org.openhab.core.items.Metadata element) {
            if (isEnergy(element)) {
                refresh();
            }
        }

        @Override
        public void updated(org.openhab.core.items.Metadata oldElement, org.openhab.core.items.Metadata element) {
            if (isEnergy(oldElement) || isEnergy(element)) {
                refresh();
            }
        }
    };

    private static boolean isEnergy(org.openhab.core.items.Metadata m) {
        return "energy".equals(m.getUID().getNamespace());
    }

    // --- pages -----------------------------------------------------------------------------------

    private RootUIComponent buildTabsPage() {
        // Two-arg ctor = (uid, component). The one-arg ctor assigns a RANDOM uid (changes every
        // reboot, breaks deep-links) — the uid must be stable and namespaced.
        RootUIComponent page = new RootUIComponent(P_ROOT, "oh-tabs-page");
        page.addConfig("label", "Energy");
        page.addConfig("sidebar", Boolean.TRUE);
        page.addConfig("icon", "f7:bolt_fill");
        page.updateTimestamp();
        List<UIComponent> tabs = page.addSlot("default");
        tabs.add(tab("Past", "f7:clock_fill", P_PAST));
        tabs.add(tab("Future", "f7:arrow_right_circle_fill", P_FUTURE));
        tabs.add(tab("Now", "f7:gauge", P_NOW));
        tabs.add(tab("Cars", "f7:car_fill", P_CARS));
        tabs.add(tab("Control", "f7:slider_horizontal_3", P_CONTROL));
        tabs.add(tab("Power", "f7:chart_bar_alt_fill", P_CHARTS));
        tabs.add(tab("By circuit", "f7:chart_pie_fill", P_CIRCUITS));
        return page;
    }

    /**
     * What is happening, in as few things as possible.
     * <p>
     * The earlier version of this page was thirty-four tiles under eight headings, which is a readout rather than an
     * answer: you had to know what "self-used" or "capacity projected" meant, and scroll past most of it to find the
     * number you came for. This one leads with a sentence, then the four figures that describe the building right
     * now, then the two or three things worth acting on. Everything else moved to the tab it belongs to.
     */
    /**
     * What already happened, over the same spans everywhere: today, yesterday, this month, this year.
     * <p>
     * One card per span with the same four figures in the same order, so the eye compares by reading down the same
     * position rather than hunting. Bought and sold in kWh, what it cost and what it saved in euro.
     */
    private RootUIComponent buildPastPage() {
        RootUIComponent page = layoutPage(P_PAST, "Past");
        List<UIComponent> root = shell(page);

        UIComponent browser = browserCard();
        if (browser != null) {
            root.add(cardRow(browser));
        }

        // Every period on one scale, so the bars compare as well as describe.
        List<String> totals = new ArrayList<>();
        for (String[] period : PERIODS) {
            for (String metric : List.of("EMS_SelfConsumption_kWh", "EMS_Supply_kWh")) {
                String item = metric + period[0];
                if (has(item)) {
                    totals.add(item);
                }
            }
        }
        if (!totals.isEmpty()) {
            String scale = largestOf(totals);
            List<UIComponent> bars = new ArrayList<>();
            for (String[] period : PERIODS) {
                UIComponent bar = periodBar(period[0], period[1], scale);
                if (bar != null) {
                    bars.add(bar);
                }
            }
            if (!bars.isEmpty()) {
                root.add(cardRow(barCard("Where your energy came from", bars)));
            }
        }

        // Circuits as bars, longest first by value at a glance: this is the "what should I look at" card.
        List<String> meters = new ArrayList<>();
        for (String circuit : trackedCircuits()) {
            String kwh = "EMS_DM_" + circuit + "_kWh";
            if (has(kwh)) {
                meters.add(kwh);
            }
        }
        if (!meters.isEmpty()) {
            String scale = largestOf(meters);
            List<UIComponent> bars = new ArrayList<>();
            int hue = 0;
            for (String kwh : meters) {
                String circuit = kwh.substring("EMS_DM_".length(), kwh.length() - "_kWh".length());
                bars.add(barRow(kwh, prettyCircuit(circuit), CIRCUIT_COLORS[hue % CIRCUIT_COLORS.length], scale));
                hue++;
            }
            root.add(cardRow(barCard("Today, circuit by circuit", bars)));
        }

        UIComponent money = figureCard("Money this month",
                figureIfPresent(I_COST_MONTH, "paid for power", "money_euro", "red"),
                figureIfPresent(I_SAVINGS_MONTH, "saved by the roof", "sun_max", "orange"),
                figureIfPresent(I_EARNINGS_MONTH, "earned selling", "arrow_up_right_circle", "green"),
                figureIfPresent("EMS_Cost_EUR_Year", "paid this year", "calendar", "red"));
        if (money != null) {
            root.add(cardRow(money));
        }

        UIComponent coverage = figureCard("How much of the building this covers",
                figureIfPresent(I_DM_TRACKED, "measured", "checkmark_seal_fill", "green"),
                figureIfPresent(I_DM_UNTRACKED, "not measured", "questionmark_circle", "orange"));
        if (coverage != null) {
            root.add(cardRow(coverage));
        }
        return page;
    }

    /**
     * What is still to come, over the spans it can be known for.
     * <p>
     * A forecast only reaches as far as the data does, so this is honest about its horizon: the rest of today and
     * tomorrow for sun, the day's prices, and the decisions already taken for the hours ahead.
     */
    private RootUIComponent buildFuturePage(SiteModel site) {
        RootUIComponent page = layoutPage(P_FUTURE, "Future");
        List<UIComponent> root = shell(page);

        if (has(I_TARIFF_SCHEDULE) && has(I_FORECAST_HOURLY)) {
            root.add(cardRow(dayStrip()));
        } else if (has(I_OPT_PLAN_24H)) {
            root.add(cardRow(planCard()));
        }

        UIComponent sun = figureCard("Sun expected",
                figureIfPresent(I_FORECAST_TODAY, "rest of today", "sun_max_fill", "orange"),
                figureIfPresent(I_FORECAST_TOMORROW, "tomorrow", "sun_max", "orange"),
                figureIfPresent(I_FORECAST_6H, "next 6 hours", "sun_min", "orange"),
                figureIfPresent(I_FORECAST_PEAK_AT, "sunniest hour", "clock", "orange"));
        if (sun != null) {
            root.add(cardRow(sun));
        }

        UIComponent prices = figureCard("Prices today", figureIfPresent(I_TARIFF_NOW, "now", "money_euro", "blue"),
                figureIfPresent(I_TARIFF_NEXT_1H, "next hour", "money_euro", "blue"),
                figureIfPresent(I_TARIFF_CHEAPEST_AT, "cheapest hour", "arrow_down_circle_fill", "green"),
                figureIfPresent(I_TARIFF_DEAREST_AT, "dearest hour", "arrow_up_circle_fill", "red"));
        if (prices != null) {
            root.add(cardRow(prices));
            UIComponent note = estimateNote();
            if (note != null) {
                root.add(cardRow(note));
            }
        }

        UIComponent plan = figureCard("Already decided",
                figureIfPresent(I_OPT_NEXT_CHARGE, "battery charges", "arrow_down_circle", "blue"),
                figureIfPresent(I_OPT_NEXT_DISCHARGE, "battery discharges", "arrow_up_circle", "purple"),
                figureIfPresent(I_BOILER_WINDOW, "water heated by", "drop_fill", "blue"),
                figureIfPresent(I_CAP_PROJECTED, "month heading for", "gauge", "purple"));
        if (plan != null) {
            root.add(cardRow(plan));
        }

        return page;
    }

    private RootUIComponent buildNowPage(List<EnergyProvider> providers, SiteModel site) {
        RootUIComponent page = layoutPage(P_NOW, "Now");
        List<UIComponent> root = shell(page);

        UIComponent headline = headlineCard(site);
        if (headline != null) {
            root.add(cardRow(headline));
        }

        UIComponent flow = new EnergyFlowCard(site, this::has).build();
        if (flow != null) {
            root.add(item(flow, "wide"));
        }

        List<UIComponent> dials = new ArrayList<>();
        if (has(I_SELFCONS_DAY) && has(I_SUPPLY_DAY)) {
            dials.add(gaugeColumn(selfSufficiencyExpression(), "ran on sun today", selfSufficiencyExpression() + "+'%'",
                    "#43a047"));
        }
        org.openhab.binding.emsmanager.internal.config.EmsBridgeConfig bridge = site.bridge();
        if (bridge != null && has(bridge.batteryPercentageItem)) {
            String soc = "(items." + bridge.batteryPercentageItem + ".numericState||0)";
            String reserve = has(bridge.batteryReserveTargetItem)
                    ? "(items." + bridge.batteryReserveTargetItem + ".numericState||0)"
                    : "0";
            // red under the reserve the owner asked to keep, amber near it, green above
            String colour = "=" + soc + "<" + reserve + "?'#ef5350':" + soc + "<" + reserve + "+10?'#ffa726':'#3bb273'";
            dials.add(gaugeColumn("=Math.max(0,Math.min(100," + soc + "))", "battery", "=Math.round(" + soc + ")+'%'",
                    colour));
        }
        if (has(I_CAP_QUARTER) && has(I_SET_CAPACITY_BUDGET)) {
            String quarter = "Math.max(0,-(items." + I_CAP_QUARTER + ".numericState||0))";
            String budget = "Math.max(1,(items." + I_SET_CAPACITY_BUDGET + ".numericState||0))";
            String share = "Math.round(100*" + quarter + "/" + budget + ")";
            // the one dial that should draw the eye: over the budget is money
            String colour = "=" + share + ">=100?'#ef5350':" + share + ">=70?'#ffa726':'#5b8def'";
            // the quarter-hour is what the grid bills; minutes left says how long the average can still move
            UIComponent dial = gaugeColumn("=Math.max(0,Math.min(100," + share + "))",
                    "=(15-(dayjs().minute()%15))+' min left in the billing quarter'",
                    "=(" + quarter + "/1000).toFixed(1)+' kW'", colour);
            if (has(I_CAP_WOULD_EXCEED)) {
                UIComponent gauge = dial.getSlots().get("default").get(0);
                gauge.addConfig("style", java.util.Map.of("border-radius", "50%", "animation",
                        "=items." + I_CAP_WOULD_EXCEED + ".state==='ON'?'ems-attn 1.6s ease-in-out infinite':'none'"));
            }
            dials.add(dial);
        }
        if (has(I_DM_TRACKED) && has(I_DM_UNTRACKED)) {
            String tracked = "(items." + I_DM_TRACKED + ".numericState||0)";
            String untracked = "(items." + I_DM_UNTRACKED + ".numericState||0)";
            String share = "=Math.max(0,Math.min(100,Math.round(100*" + tracked + "/((" + tracked + "+" + untracked
                    + ")||1))))";
            dials.add(gaugeColumn(share, "of the building measured", share + "+'%'", "#5b8def"));
        }
        if (!dials.isEmpty()) {
            UIComponent dialCard = card(null, dials);
            root.add(item(dialCard, flow != null ? "side" : "full"));
        }

        if (has(I_TARIFF_SCHEDULE) && has(I_TARIFF_MIN) && has(I_TARIFF_MAX) && has(I_TARIFF_NOW)) {
            root.add(item(priceAheadCard(), "half"));
        }

        List<UIComponent> live = new ArrayList<>();
        if (flow == null) {
            // without the picture, the numbers have to say it
            for (EnergyProvider provider : providers) {
                String caption = switch (provider.role()) {
                    case PV -> "roof is making";
                    case GRID -> "grid";
                    case BATTERY -> "battery";
                };
                live.add(figure(provider.id(), caption, providerGlyph(provider.role()), providerHue(provider.role())));
            }
            if (has(I_DM_TRACKED)) {
                live.add(figure(I_DM_TRACKED, "building is using", "house_fill", "purple"));
            }
        }
        if (has(I_TARIFF_NOW)) {
            live.add(tariffNowFigure());
        }
        if (has(I_CO2_SAVED_TODAY)) {
            live.add(figure(I_CO2_SAVED_TODAY, "CO₂ avoided today", "leaf_arrow_circlepath", "green"));
        }
        if (bridge != null && has(I_BATTERY_SETPOINT)) {
            live.add(figure(I_BATTERY_SETPOINT, "battery asked to", "battery_25", "green"));
        }
        if (!live.isEmpty()) {
            root.add(item(card("Right now", live), "half"));
        }

        UIComponent today = figureCard("Today so far",
                figureIfPresent("EMS_Supply_kWh_Day", "bought", "arrow_down_left_circle", "red"),
                figureIfPresent(I_SELFCONS_DAY, "sun used", "sun_max", "orange"),
                figureIfPresent("EMS_FeedIn_kWh_Day", "sold", "arrow_up_right_circle", "green"),
                figureIfPresent("EMS_Cost_EUR_Day", "cost", "money_euro", "red"));
        if (today != null) {
            root.add(cardRow(today));
        }
        return page;
    }

    /**
     * One sentence about what the building is doing, from the live powers rather than a status
     * word. "Selling 3.2 kW" is what a person wants to know; "SOLAR_EXCESS" is what the engine calls it.
     */
    private @org.eclipse.jdt.annotation.Nullable UIComponent headlineCard(SiteModel site) {
        org.openhab.binding.emsmanager.internal.config.EmsBridgeConfig bridge = site.bridge();
        List<UIComponent> lines = new ArrayList<>();
        if (bridge != null && has(bridge.gridLoadItem)) {
            String grid = (bridge.invertGrid ? "-" : "") + "(items." + bridge.gridLoadItem + ".numericState||0)";
            String exp = "Math.max(0," + grid + ")";
            String imp = "Math.max(0,-(" + grid + "))";
            String sentence = "=" + exp + ">100?'Selling '+(" + exp + "/1000).toFixed(1)+' kW to the grid':" + imp
                    + ">100?'Buying '+(" + imp
                    + "/1000).toFixed(1)+' kW from the grid':'Running on the roof and the battery'";
            UIComponent big = new UIComponent("Label");
            big.addConfig("text", sentence);
            big.addConfig("style", java.util.Map.of("display", "block", "font-size", "20px", "font-weight", "700",
                    "line-height", "1.2", "color", "=" + exp + ">100?'#3bb273':" + imp + ">100?'#7d6cd6':'#f0a83c'"));
            lines.add(big);
        }
        UIComponent advice = adviceLine(bridge);
        if (advice != null) {
            lines.add(advice);
        }
        if (has(I_LAST_DECISION)) {
            UIComponent sub = new UIComponent("Label");
            sub.addConfig("text",
                    "=items." + I_LAST_DECISION
                            + ".state==='(idle)'?'Nothing needs steering right now':'Last decision: '+items."
                            + I_LAST_DECISION + ".state");
            sub.addConfig("style", java.util.Map.of("display", "block", "font-size", "12px", "opacity", "0.7",
                    "margin-top", "4px", "overflow", "hidden", "text-overflow", "ellipsis", "white-space", "nowrap"));
            lines.add(sub);
        }
        UIComponent chips = statusChipsRow();
        if (lines.isEmpty() && chips == null) {
            return null;
        }
        UIComponent body = new UIComponent("div");
        body.addConfig("style", java.util.Map.of("padding", "14px 14px 10px 14px"));
        List<UIComponent> slot = body.addSlot("default");
        slot.addAll(lines);
        if (chips != null) {
            slot.add(chips);
        }
        UIComponent card = new UIComponent("f7-card");
        card.addSlot("default").add(body);
        return card;
    }

    /**
     * What to do about it, in one line.
     * <p>
     * Spare power now, a cheaper hour coming, or a dear hour to sit out: the same signals the engine
     * plans on, said in words a person can act on with the dishwasher. Built only from Items this
     * site has, so a site without a tariff simply gets the surplus half.
     */
    private @org.eclipse.jdt.annotation.Nullable UIComponent adviceLine(
            org.openhab.binding.emsmanager.internal.config.@org.eclipse.jdt.annotation.Nullable EmsBridgeConfig bridge) {
        if (bridge == null || !has(bridge.gridLoadItem)) {
            return null;
        }
        String grid = (bridge.invertGrid ? "-" : "") + "(items." + bridge.gridLoadItem + ".numericState||0)";
        String exp = "Math.max(0," + grid + ")";
        String imp = "Math.max(0,-(" + grid + "))";
        String sun3 = has(I_FORECAST_3H) ? "(items." + I_FORECAST_3H + ".numericState||0)" : "0";
        StringBuilder e = new StringBuilder("=");
        e.append(exp).append(">1500?'Spare '+(").append(exp).append("/1000).toFixed(1)+' kW right now'+(").append(sun3)
                .append(">3?' and sun for the next three hours':'')+' - a good moment for the big loads':");
        if (has(I_TARIFF_NOW) && has(I_TARIFF_MIN) && has(I_TARIFF_MAX)) {
            String now = "(items." + I_TARIFF_NOW + ".numericState||0)";
            String low = "(items." + I_TARIFF_MIN + ".numericState||0)";
            String span = "((items." + I_TARIFF_MAX + ".numericState||0)-" + low + ")";
            String pos = "((" + span + ">0)?((" + now + "-" + low + ")/" + span + "):0.5)";
            if (has(I_TARIFF_CHEAPEST_AT)) {
                String cheapAt = "dayjs(items." + I_TARIFF_CHEAPEST_AT + ".state).hour()";
                e.append("(").append(pos).append(">=0.34&&").append(cheapAt)
                        .append(">dayjs().hour())?'Cheapest power at '+").append(cheapAt)
                        .append("+':00 - wait with the heavy loads if you can':");
            }
            e.append("(").append(pos).append(">=0.67&&").append(imp).append(">1500)?'Dear hour - buying '+(")
                    .append(imp).append("/1000).toFixed(1)+' kW; best to hold the heavy loads':");
            e.append("(").append(pos).append("<0.34)?'Cheap hour - a fine time for the heavy loads':");
        }
        e.append("'Nothing to do - the house is running as planned'");

        UIComponent box = new UIComponent("div");
        box.addConfig("style", java.util.Map.of("display", "flex", "align-items", "center", "gap", "8px", "margin-top",
                "8px", "padding", "9px 11px", "border-radius", "10px", "background", "rgba(240,168,60,0.13)"));
        List<UIComponent> parts = box.addSlot("default");
        UIComponent icon = new UIComponent("f7-icon");
        icon.addConfig("f7", "lightbulb_fill");
        icon.addConfig("size", Integer.valueOf(16));
        icon.addConfig("style", java.util.Map.of("color", "#f0a83c", "flex", "0 0 auto"));
        parts.add(icon);
        UIComponent text = new UIComponent("Label");
        text.addConfig("text", e.toString());
        text.addConfig("style", java.util.Map.of("font-size", "13px", "line-height", "1.3"));
        parts.add(text);
        return box;
    }

    /**
     * The next six hours of price, from the schedule the tariff already publishes.
     * <p>
     * Six columns from the current hour, coloured by where each sits between the day's cheapest
     * and dearest, so "should I wait an hour" is answered by shape.
     */
    private UIComponent priceAheadCard() {
        UIComponent card = new UIComponent("f7-card");
        card.addConfig("title", "Price, next six hours");
        String low = "(items." + I_TARIFF_MIN + ".numericState||0)";
        String high = "(items." + I_TARIFF_MAX + ".numericState||0)";
        String span = "(" + high + "-" + low + ")";

        UIComponent body = new UIComponent("div");
        body.addConfig("style", java.util.Map.of("padding", "8px 14px 12px 14px"));
        List<UIComponent> bodySlot = body.addSlot("default");

        UIComponent head = new UIComponent("div");
        head.addConfig("style", java.util.Map.of("display", "flex", "justify-content", "space-between", "font-size",
                "12px", "margin-bottom", "6px"));
        List<UIComponent> headSlot = head.addSlot("default");
        UIComponent nowLabel = new UIComponent("Label");
        nowLabel.addConfig("text", "=(items." + I_TARIFF_NOW + ".numericState||0).toFixed(2)+' now'");
        nowLabel.addConfig("style", java.util.Map.of("font-weight", "700"));
        headSlot.add(nowLabel);
        UIComponent range = new UIComponent("Label");
        range.addConfig("text", "='today '+" + low + ".toFixed(2)+' to '+" + high + ".toFixed(2)+' per kWh'");
        range.addConfig("style", java.util.Map.of("opacity", "0.6"));
        headSlot.add(range);
        bodySlot.add(head);

        UIComponent bars = new UIComponent("div");
        bars.addConfig("style", java.util.Map.of("display", "grid", "grid-template-columns",
                "repeat(6, minmax(0, 1fr))", "gap", "4px", "align-items", "end", "height", "46px"));
        List<UIComponent> barSlot = bars.addSlot("default");
        UIComponent hours = new UIComponent("div");
        hours.addConfig("style",
                java.util.Map.of("display", "grid", "grid-template-columns", "repeat(6, minmax(0, 1fr))", "gap", "4px",
                        "font-size", "9px", "opacity", "0.6", "text-align", "center", "margin-top", "3px"));
        List<UIComponent> hourSlot = hours.addSlot("default");
        for (int i = 0; i < 6; i++) {
            String hour = "((dayjs().hour()+" + i + ")%24)";
            String price = "Number((items." + I_TARIFF_SCHEDULE + ".state||'').split(',')[" + hour + "]||0)";
            String pos = "((" + span + ">0)?((" + price + "-" + low + ")/" + span + "):0.5)";
            UIComponent bar = new UIComponent("div");
            bar.addConfig("class", List.of("bar"));
            bar.addConfig("style",
                    java.util.Map.of("border-radius", "4px 4px 0 0", "height",
                            "=Math.round(8+38*Math.max(0,Math.min(1," + pos + ")))+'px'", "background",
                            "=" + pos + "<0.34?'#43a047':" + pos + "<0.67?'#ffa726':'#ef5350'", "outline",
                            i == 0 ? "2px solid var(--f7-text-color)" : "none", "outline-offset", "1px"));
            barSlot.add(bar);
            UIComponent label = new UIComponent("Label");
            label.addConfig("text", i == 0 ? "now" : "=" + hour + "+':00'");
            hourSlot.add(label);
        }
        bodySlot.add(bars);
        bodySlot.add(hours);
        card.addSlot("default").add(body);
        return card;
    }

    /** The current price with a word on where it sits in the day. */
    private UIComponent tariffNowFigure() {
        UIComponent column = figure(I_TARIFF_NOW, "per kWh now", "money_euro", "blue");
        if (!has(I_TARIFF_MIN) || !has(I_TARIFF_MAX)) {
            return column;
        }
        String now = "(items." + I_TARIFF_NOW + ".numericState||0)";
        String low = "(items." + I_TARIFF_MIN + ".numericState||0)";
        String span = "((items." + I_TARIFF_MAX + ".numericState||0)-" + low + ")";
        String position = "((" + span + ">0)?((" + now + "-" + low + ")/" + span + "):0.5)";
        UIComponent word = new UIComponent("Label");
        word.addConfig("text", "=" + position + "<0.34?'cheap hour':" + position + "<0.67?'average hour':'dear hour'");
        word.addConfig("style", java.util.Map.of("font-size", "10px", "font-weight", "600", "line-height", "13px",
                "color", "=" + position + "<0.34?'#43a047':" + position + "<0.67?'#ffa726':'#ef5350'"));
        column.addComponent("default", word);
        return column;
    }

    /** Framework7 glyph names, which are not the same as the oh: icon set the cards used. */
    private String providerGlyph(ProviderRole role) {
        return switch (role) {
            case PV -> "sun_max_fill";
            case GRID -> "bolt_horizontal_fill";
            case BATTERY -> "battery_25";
        };
    }

    private String providerHue(ProviderRole role) {
        return switch (role) {
            case PV -> "orange";
            case GRID -> "blue";
            case BATTERY -> "green";
        };
    }

    /**
     * The battery's own plan for the next 24 hours, one character per hour.
     * <p>
     * Monospaced, because the characters only line up with the hours if they are all the same width.
     */
    private UIComponent planCard() {
        UIComponent card = new UIComponent("f7-card");
        card.addConfig("title", "Battery, next 24 hours");
        UIComponent value = new UIComponent("oh-label-item");
        value.addConfig("item", I_OPT_PLAN_24H);
        value.addConfig("class", List.of("text-align-center", "padding-vertical-half"));
        value.addConfig("style", java.util.Map.of("font-family", "monospace", "font-size", "15px", "letter-spacing",
                "2px", "font-weight", "bold"));
        card.addSlot("default").add(value);
        return card;
    }

    // --- control -------------------------------------------------------------------------------

    /**
     * The heat-pump advice Items, discovered rather than named.
     * <p>
     * A pump's Items are named after the Thing the site created, so there is no fixed name to look for. A site with
     * two pumps gets two cards and a site with none gets no card, which is what discovery buys over a constant.
     */
    private List<String> heatPumpAdviceItems() {
        List<String> out = new ArrayList<>();
        for (org.openhab.core.items.Item item : itemRegistry.getItems()) {
            String name = item.getName();
            if (name.startsWith("EMS_HP_") && name.endsWith("_Reason")) {
                out.add(name);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** {@code EMS_HP_Workshop_Reason} reads as "Workshop heat pump". */
    private String heatPumpTitle(String reasonItem) {
        String id = reasonItem.substring("EMS_HP_".length(), reasonItem.length() - "_Reason".length());
        return id.isEmpty() ? "Heat pump advice" : prettyCircuit(id) + " heat pump";
    }

    // --- where the energy goes -----------------------------------------------------------------

    /**
     * The circuits this site actually meters, taken from the Items the device-meter Things publish.
     * <p>
     * Discovered rather than configured: the meters are this binding's own Items, so what exists is the answer. The
     * two roll-ups it also publishes are left out, because a total next to its own parts reads as double counting.
     */
    private List<String> trackedCircuits() {
        java.util.Set<String> rollups = java.util.Set.of("Cars", "Lights");
        List<String> out = new ArrayList<>();
        for (org.openhab.core.items.Item item : itemRegistry.getItems()) {
            String name = item.getName();
            if (!name.startsWith("EMS_DM_") || !name.endsWith("_W")) {
                continue;
            }
            String circuit = name.substring("EMS_DM_".length(), name.length() - "_W".length());
            if (circuit.isEmpty() || rollups.contains(circuit)) {
                continue;
            }
            out.add(circuit);
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** {@code HeaterKitchen} reads badly on a card; {@code Heater kitchen} does not. */
    private String prettyCircuit(String circuit) {
        String spaced = circuit.replaceAll("(?<=[a-z])(?=[A-Z])", " ").replace('_', ' ');
        return spaced.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                + spaced.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private RootUIComponent buildChartsPage(List<EnergyProvider> providers, List<EnergyConsumer> consumers) {
        // ONE time-axis chart: live power today for every tagged participant (smooth, area-filled)
        // plus the solar forecast drawn dashed into tomorrow (future=1 extends the window ahead of
        // now). Standalone oh-chart-page (embedded charts render blank); no persistence service set
        // → the site default is used (portable). Drag the slider to pan across time.
        RootUIComponent page = new RootUIComponent(P_CHARTS, "oh-chart-page");
        page.addConfig("label", "Power");
        page.addConfig("sidebar", Boolean.FALSE);
        // chartType "day" anchors the window to midnight..midnight TODAY, so it holds both today's
        // actuals (past) AND today's solar forecast (future part of today). `future` alone shifts the
        // whole window into the future and hides the actuals — do not use it here.
        page.addConfig("chartType", "day");
        page.addConfig("period", "D");
        page.updateTimestamp();

        UIComponent grid = new UIComponent("oh-chart-grid");
        grid.addConfig("includeLabels", Boolean.TRUE);
        grid.addConfig("top", "12%");
        grid.addConfig("height", "70%");
        grid.addConfig("left", "12%");
        grid.addConfig("right", "5%");
        page.addSlot("grid").add(grid);

        UIComponent xAxis = new UIComponent("oh-time-axis");
        xAxis.addConfig("gridIndex", Integer.valueOf(0));
        page.addSlot("xAxis").add(xAxis);

        UIComponent yAxis = new UIComponent("oh-value-axis");
        yAxis.addConfig("gridIndex", Integer.valueOf(0));
        yAxis.addConfig("name", "W");
        page.addSlot("yAxis").add(yAxis);

        List<UIComponent> series = page.addSlot("series");
        // Solar forecast: orange dashed line (no fill) so it reads distinctly against the yellow
        // solar actual beneath it.
        if (has(ITEM_FORECAST_SERIES)) {
            series.add(powerLine("Solar forecast", ITEM_FORECAST_SERIES, "#ff9800", true, false));
        }
        // what the building drew, so the chart carries both halves of the story rather than only supply
        if (has(I_DM_TRACKED)) {
            series.add(powerLine("Building", I_DM_TRACKED, "#7e57c2", false, true));
        }
        for (EnergyProvider p : providers) {
            series.add(
                    powerLine(providerTitle(p), p.id(), providerColor(p.role()), false, p.role() != ProviderRole.GRID));
        }
        // Consumers only chart if they expose a measured power item — a plain on/off switch has no
        // power series to draw.
        for (EnergyConsumer c : consumers) {
            String measure = c.measureItem();
            if (measure != null) {
                series.add(powerLine(consumerTitle(c), measure, "#42a5f5", false, true));
            }
        }

        chartControls(page);
        return page;
    }

    /** A power time series (smooth line); area-filled for production/consumption, dashed for forecast. */

    /**
     * Today's energy, circuit by circuit, as rising curves.
     * <p>
     * Deliberately built on the cumulative {@code _kWh} meters rather than the live {@code _W} ones. Instantaneous
     * power is what the other chart already shows, and it is spiky and hard to read a day off; a rising line answers
     * the question this page is for - <em>which circuit actually used the energy today</em> - because the one that
     * climbs fastest is the one spending it, and the height at the end is the day's total.
     * <p>
     * The {@code _kWh} meters are also the ones a site is told to persist, so this chart draws where the live power
     * items would leave it empty.
     */
    private RootUIComponent buildCircuitsChartPage() {
        RootUIComponent page = new RootUIComponent(P_CIRCUITS, "oh-chart-page");
        page.addConfig("label", "Today by circuit");
        page.addConfig("sidebar", Boolean.FALSE);
        page.addConfig("chartType", "day");
        page.addConfig("period", "D");
        page.updateTimestamp();

        UIComponent grid = new UIComponent("oh-chart-grid");
        grid.addConfig("includeLabels", Boolean.TRUE);
        grid.addConfig("top", "12%");
        grid.addConfig("height", "70%");
        grid.addConfig("left", "12%");
        grid.addConfig("right", "5%");
        page.addSlot("grid").add(grid);

        UIComponent xAxis = new UIComponent("oh-time-axis");
        xAxis.addConfig("gridIndex", Integer.valueOf(0));
        page.addSlot("xAxis").add(xAxis);

        UIComponent yAxis = new UIComponent("oh-value-axis");
        yAxis.addConfig("gridIndex", Integer.valueOf(0));
        yAxis.addConfig("name", "kWh");
        page.addSlot("yAxis").add(yAxis);

        List<UIComponent> series = page.addSlot("series");
        List<String> circuits = trackedCircuits();
        int index = 0;
        for (String circuit : circuits) {
            String kwh = "EMS_DM_" + circuit + "_kWh";
            if (!has(kwh)) {
                continue;
            }
            series.add(stackedBand(prettyCircuit(circuit), kwh, CIRCUIT_COLORS[index % CIRCUIT_COLORS.length]));
            index++;
        }
        chartControls(page);
        return page;
    }

    /** Enough distinct hues that no two circuits on a normal site share one. */
    private static final String[] CIRCUIT_COLORS = { "#5b8def", "#43a047", "#ff9800", "#ef5350", "#7e57c2", "#26a69a",
            "#ec407a", "#8d6e63", "#42a5f5", "#9ccc65", "#ffa726", "#ab47bc", "#78909c" };

    /**
     * One band of a stacked area chart.
     * <p>
     * Twelve cumulative lines drawn over each other is unreadable - they all rise, they all cross, and the eye cannot
     * tell which is which. Stacked, the same twelve become bands: the height of the whole is the day's total and the
     * thickness of each band is what that circuit spent, which is the question the page is for.
     */
    private UIComponent stackedBand(String name, String item, String colour) {
        UIComponent series = new UIComponent("oh-time-series");
        series.addConfig("name", name);
        series.addConfig("item", item);
        series.addConfig("type", "line");
        series.addConfig("color", colour);
        series.addConfig("stack", "total");
        series.addConfig("showSymbol", Boolean.FALSE);
        series.addConfig("lineStyle", java.util.Map.of("width", Integer.valueOf(1), "opacity", Double.valueOf(0.6)));
        series.addConfig("areaStyle", java.util.Map.of("opacity", Double.valueOf(0.75)));
        return series;
    }

    private UIComponent powerLine(String name, String item, String color, boolean dashed, boolean area) {
        UIComponent s = new UIComponent("oh-time-series");
        s.addConfig("name", name);
        s.addConfig("item", item);
        s.addConfig("type", "line");
        s.addConfig("color", color);
        s.addConfig("smooth", Boolean.TRUE);
        s.addConfig("showSymbol", Boolean.FALSE);
        if (area) {
            s.addConfig("areaStyle", java.util.Map.of("opacity", Double.valueOf(0.15)));
        }
        s.addConfig("lineStyle", dashed ? java.util.Map.of("type", "dashed", "width", Integer.valueOf(2))
                : java.util.Map.of("width", Integer.valueOf(2)));
        return s;
    }

    /** On-brand series colours (matches the site palette: solar=yellow, grid=purple, battery=green). */
    private String providerColor(ProviderRole role) {
        return switch (role) {
            case PV -> "#ffd54f";
            case GRID -> "#9575cd";
            case BATTERY -> "#66bb6a";
        };
    }

    /**
     * Legend, axis tooltip, and inside + slider data-zoom. The slider is a draggable time scrollbar
     * — the native way to navigate across days in a chart tab (openHAB tabs have no prev/next).
     */
    private void chartControls(RootUIComponent page) {
        UIComponent legend = new UIComponent("oh-chart-legend");
        legend.addConfig("show", Boolean.TRUE);
        legend.addConfig("top", Integer.valueOf(0));
        page.addSlot("legend").add(legend);
        UIComponent tooltip = new UIComponent("oh-chart-tooltip");
        tooltip.addConfig("trigger", "axis");
        page.addSlot("tooltip").add(tooltip);
        List<UIComponent> zoom = page.addSlot("dataZoom");
        UIComponent inside = new UIComponent("oh-chart-datazoom");
        inside.addConfig("type", "inside");
        inside.addConfig("xAxisIndex", List.of(Integer.valueOf(0)));
        zoom.add(inside);
        UIComponent slider = new UIComponent("oh-chart-datazoom");
        slider.addConfig("type", "slider");
        slider.addConfig("xAxisIndex", List.of(Integer.valueOf(0)));
        slider.addConfig("bottom", Integer.valueOf(8));
        zoom.add(slider);
    }

    // --- component builders ----------------------------------------------------------------------

    private RootUIComponent layoutPage(String uid, String label) {
        RootUIComponent page = new RootUIComponent(uid, "oh-layout-page");
        page.addConfig("label", label);
        page.addConfig("sidebar", Boolean.FALSE);
        page.updateTimestamp();
        return page;
    }

    /**
     * The look of the section, once, as a scoped stylesheet on the root of every page.
     * <p>
     * MainUI scopes {@code stylesheet} on any component to that component's subtree, so one sheet on
     * the page root styles every card in it without touching the rest of the UI. Theme colours come
     * from the Framework7 variables so the same sheet reads in light and dark. Motion is limited to
     * the flow dots, a bar settling into its new width, and one pulse that only runs while the house
     * is about to set a new billing peak.
     */
    private static final String STYLE = """
            .ems{display:grid;grid-template-columns:repeat(12,minmax(0,1fr));gap:10px;padding:6px 10px 18px 10px}
            .ems-item{grid-column:span 12;min-width:0}
            @media(min-width:768px){.ems-item.half{grid-column:span 6}.ems-item.wide{grid-column:span 7}.ems-item.side{grid-column:span 5}}
            .ems .card{margin:0;border-radius:14px;background:var(--f7-card-bg-color);border:1px solid rgba(127,127,127,.14);box-shadow:0 1px 2px rgba(0,0,0,.04),0 12px 28px -18px rgba(0,0,0,.25)}
            .ems .card-header{font-size:11px;font-weight:600;letter-spacing:.08em;text-transform:uppercase;opacity:.6;min-height:0;padding:12px 14px 0 14px}
            .ems .card-header:after{display:none}
            .ems .card-content{padding-top:2px}
            .ems .list ul{background:transparent}
            .ems .list ul:before,.ems .list ul:after{display:none}
            .ems .item-title{font-weight:500;font-size:14px}
            .ems .bar{transition:width .5s ease,height .5s ease}
            @keyframes ems-attn{0%,100%{box-shadow:0 0 0 0 rgba(239,83,80,0)}50%{box-shadow:0 0 0 7px rgba(239,83,80,.28)}}
            """;

    /** The page's content root: everything a page shows goes into the list this returns. */
    private List<UIComponent> shell(RootUIComponent page) {
        UIComponent root = new UIComponent("div");
        root.addConfig("class", List.of("ems"));
        root.addConfig("stylesheet", STYLE);
        page.addSlot("default").add(root);
        return root.addSlot("default");
    }

    /** A card taking part of the row on a tablet: {@code half}, {@code wide} or {@code side}. */
    private UIComponent item(UIComponent card, String span) {
        UIComponent cell = new UIComponent("div");
        cell.addConfig("class", List.of("ems-item", span));
        cell.addSlot("default").add(card);
        return cell;
    }

    private UIComponent tab(String title, String icon, String pageUid) {
        UIComponent t = new UIComponent("oh-tab");
        t.addConfig("title", title);
        t.addConfig("icon", icon);
        t.addConfig("page", pageUid);
        return t;
    }

    /**
     * The things you can actually change, and the answers to the things you can ask it.
     */
    private RootUIComponent buildControlPage(List<EnergyConsumer> consumers, SiteModel site) {
        RootUIComponent page = layoutPage(P_CONTROL, "Control");
        List<UIComponent> root = shell(page);

        UIComponent status = statusChips();
        if (status != null) {
            root.add(cardRow(status));
        }

        UIComponent hotWater = hotWaterProgress();
        if (hotWater != null) {
            root.add(cardRow(hotWater));
        }

        UIComponent settings = settingsCard(site);
        if (settings != null) {
            root.add(cardRow(settings));
        }

        List<UIComponent> switches = new ArrayList<>();
        if (has(I_BOILER_OVERRIDE)) {
            switches.add(switchRow(I_BOILER_OVERRIDE, "Heat the water now"));
        }
        if (has(I_PEAK_ENABLED)) {
            switches.add(switchRow(I_PEAK_ENABLED, "Protect against peaks"));
        }
        for (EnergyConsumer consumer : consumers) {
            String item = consumer.profile().itemName();
            if (has(item)) {
                switches.add(switchRow(item, consumerTitle(consumer)));
            }
        }
        if (!switches.isEmpty()) {
            root.add(cardRow(listCard("Switches", switches)));
        }

        List<UIComponent> actions = new ArrayList<>();
        if (has(I_PEAK_ENGAGE)) {
            actions.add(actionButton(I_PEAK_ENGAGE, "Turn things down now", "orange"));
        }
        if (has(I_PEAK_RESET)) {
            actions.add(actionButton(I_PEAK_RESET, "Turn everything back on", "green"));
        }
        if (has(I_SIZING_RUN)) {
            actions.add(actionButton(I_SIZING_RUN, "What size battery suits me?", "purple"));
        }
        if (has(I_COMPARE_RUN)) {
            actions.add(actionButton(I_COMPARE_RUN, "Am I on the right tariff?", "blue"));
        }
        if (!actions.isEmpty()) {
            root.add(cardRow(listCard("Ask it to do something", actions)));
        }

        UIComponent answers = figureCard("Answers",
                figureIfPresent(I_SIZING_KWH, "battery size that fits", "battery_100", "purple"),
                figureIfPresent(I_SIZING_PAYBACK, "pays back in, years", "calendar", "purple"),
                figureIfPresent(I_COMPARE_CHEAPEST, "best tariff", "money_euro_circle", "blue"),
                figureIfPresent(I_BATTERY_SETPOINT, "battery set to", "battery_25", "green"));
        if (answers != null) {
            root.add(cardRow(answers));
        }
        if (has(I_COMPARE_SUMMARY)) {
            UIComponent summary = new UIComponent("oh-label-item");
            summary.addConfig("item", I_COMPARE_SUMMARY);
            summary.addConfig("style", java.util.Map.of("font-size", "12px", "line-height", "1.3"));
            root.add(cardRow(listCard("Tariffs compared", List.of(summary))));
        }

        for (String reason : heatPumpAdviceItems()) {
            UIComponent advice = new UIComponent("oh-label-item");
            advice.addConfig("item", reason);
            advice.addConfig("style", java.util.Map.of("font-size", "12px", "line-height", "1.3"));
            root.add(cardRow(listCard(heatPumpTitle(reason), List.of(advice))));
        }

        if (has(I_SHADOW_MODE)) {
            root.add(cardRow(listCard("Stop button",
                    List.of(switchRow(I_SHADOW_MODE, "Stop the system controlling anything")))));
        }
        return page;
    }

    /**
     * The chargers on their own tab: one card per car, nothing else, because a person setting up a
     * departure wants the four cars side by side and not under the boiler.
     */
    private RootUIComponent buildCarsPage(SiteModel site) {
        RootUIComponent page = layoutPage(P_CARS, "Cars");
        List<UIComponent> root = shell(page);
        List<UIComponent> cards = new ArrayList<>();
        for (SiteModel.Car car : site.cars()) {
            UIComponent card = carCard(car);
            if (card != null) {
                cards.add(card);
            }
        }
        if (cards.isEmpty()) {
            UIComponent note = new UIComponent("oh-label-item");
            note.addConfig("title", "No chargers");
            note.addConfig("subtitle",
                    "Set carCount and the per-car item patterns on the EMS bridge to get a card per car here.");
            root.add(cardRow(listCard("Cars", List.of(note))));
            return page;
        }
        for (UIComponent card : cards) {
            root.add(item(card, "half"));
        }
        return page;
    }

    /**
     * One car: how it is charging now, and the plan for when it has to be ready.
     * <p>
     * The plan Items are the engine's own contract (target, departure, strategy, on/off in; status,
     * required, hours, cost, feasible out), so this is the one place a person sets them. Cars whose
     * plan Items do not exist get no card rather than a card of dashes.
     */
    private @org.eclipse.jdt.annotation.Nullable UIComponent carCard(SiteModel.Car car) {
        String enabled = car.planPrefix() + "_Plan_Enabled";
        if (!has(enabled) && !has(car.modeItem())) {
            return null;
        }
        UIComponent card = new UIComponent("f7-card");
        card.addConfig("title", carTitle(car));
        List<UIComponent> slot = card.addSlot("default");

        UIComponent state = carStateChip(car);
        if (state != null) {
            slot.add(state);
        }

        List<UIComponent> now = new ArrayList<>();
        if (has(car.statusItem())) {
            now.add(figure(car.statusItem(), "charger says", "bolt_circle", "blue"));
        }
        String power = has(car.powerWItem()) ? car.powerWItem() : has(car.powerKwItem()) ? car.powerKwItem() : null;
        if (power != null) {
            now.add(figure(power, "charging at", "speedometer", "orange"));
        }
        if (has(car.currentLimitItem())) {
            now.add(figure(car.currentLimitItem(), "allowed", "gauge", "purple"));
        }
        if (!now.isEmpty()) {
            UIComponent row = new UIComponent("f7-row");
            row.addConfig("class", List.of("padding-vertical-half"));
            row.addSlot("default").addAll(now);
            slot.add(row);
        }

        if (has(car.modeItem())) {
            slot.add(segmentedRow(car.modeItem(), "Charging",
                    new String[][] { { "ECO", "Sun first" }, { "SNEL", "Full speed" }, { "OFF", "Manual" } }));
        }
        if (has(enabled)) {
            UIComponent list = new UIComponent("oh-list-card");
            list.addConfig("style", java.util.Map.of("margin", "0", "box-shadow", "none"));
            List<UIComponent> rows = list.addSlot("default");
            rows.add(switchRow(enabled, "Charge to a target by a departure time"));
            if (has(car.planPrefix() + "_Plan_Target_kWh")) {
                rows.add(sliderRow(car.planPrefix() + "_Plan_Target_kWh", "Energy wanted by then", 0, 100, 5));
            }
            if (has(car.planPrefix() + "_Plan_Departure_At")) {
                UIComponent departure = new UIComponent("oh-input-item");
                departure.addConfig("item", car.planPrefix() + "_Plan_Departure_At");
                departure.addConfig("title", "Leaving at");
                departure.addConfig("type", "datetime-local");
                departure.addConfig("sendButton", Boolean.TRUE);
                rows.add(departure);
            }
            slot.add(list);
            if (has(car.planPrefix() + "_Plan_Target_kWh") && has(car.planPrefix() + "_Plan_Required_kWh")) {
                slot.add(deliveredBar(car, enabled));
            }
            if (has(car.planPrefix() + "_Plan_Strategy")) {
                slot.add(segmentedRow(car.planPrefix() + "_Plan_Strategy", "Get there by", new String[][] {
                        { "now", "Charging now" }, { "cheapest", "Cheapest hours" }, { "solar-first", "Sun only" } }));
            }
            if (has(car.planPrefix() + "_Plan_Status")) {
                UIComponent status = new UIComponent("oh-label-item");
                status.addConfig("item", car.planPrefix() + "_Plan_Status");
                // the plan's own verdict, shown only while a plan is on
                status.addConfig("style", java.util.Map.of("font-size", "12px", "opacity", "0.8", "display",
                        "=items." + enabled + ".state==='ON'?'block':'none'"));
                slot.add(status);
            }
            List<UIComponent> figures = new ArrayList<>();
            for (String[] f : new String[][] { { "_Plan_Required_kWh", "still needed", "battery_25", "orange" },
                    { "_Plan_Hours_Remaining", "hours left", "clock", "blue" },
                    { "_Plan_Projected_Cost_EUR", "will cost", "money_euro", "red" } }) {
                if (has(car.planPrefix() + f[0])) {
                    figures.add(figure(car.planPrefix() + f[0], f[1], f[2], f[3]));
                }
            }
            if (!figures.isEmpty()) {
                UIComponent planRow = new UIComponent("f7-row");
                planRow.addConfig("class", List.of("padding-vertical-half"));
                planRow.addConfig("style",
                        java.util.Map.of("display", "=items." + enabled + ".state==='ON'?'flex':'none'"));
                planRow.addSlot("default").addAll(figures);
                slot.add(planRow);
            }
            if (has(car.planPrefix() + "_Plan_Feasible")) {
                String late = "items." + enabled + ".state==='ON'&&items." + car.planPrefix()
                        + "_Plan_Feasible.state==='OFF'";
                UIComponent warn = new UIComponent("div");
                warn.addConfig("style",
                        java.util.Map.of("display", "=" + late + "?'block':'none'", "margin", "0 14px 12px 14px",
                                "padding", "9px 11px", "border-radius", "10px", "background", "rgba(239,83,80,0.12)",
                                "font-size", "12px", "line-height", "1.3"));
                UIComponent text = new UIComponent("Label");
                text.addConfig("text",
                        "This plan will not make it in time. Switch to Full speed, or move the departure later.");
                warn.addSlot("default").add(text);
                slot.add(warn);
            }
        }
        return card;
    }

    /** Cable, charging or idle - one chip on the card's first line. */
    private @org.eclipse.jdt.annotation.Nullable UIComponent carStateChip(SiteModel.Car car) {
        if (!has(car.cableItem()) && !has(car.statusItem())) {
            return null;
        }
        String power = has(car.powerWItem()) ? "(items." + car.powerWItem() + ".numericState||0)"
                : has(car.powerKwItem()) ? "((items." + car.powerKwItem() + ".numericState||0)*1000)" : "0";
        String cable = has(car.cableItem()) ? "items." + car.cableItem() + ".state==='ON'" : "true";
        String text = "=" + power + ">200?'charging '+(" + power + "/1000).toFixed(1)+' kW':" + cable
                + "?'plugged in, not charging':'no cable'";
        String colour = "=" + power + ">200?'orange':" + cable + "?'blue':'gray'";
        UIComponent row = new UIComponent("div");
        row.addConfig("style", java.util.Map.of("padding", "6px 14px 0 14px"));
        row.addSlot("default").add(chip(text, colour, null));
        return row;
    }

    /** How far the plan has got: delivered out of wanted, with what is still to come. */
    private UIComponent deliveredBar(SiteModel.Car car, String enabled) {
        String target = "(items." + car.planPrefix() + "_Plan_Target_kWh.numericState||0)";
        String required = "(items." + car.planPrefix() + "_Plan_Required_kWh.numericState||0)";
        String delivered = "Math.max(0," + target + "-" + required + ")";
        UIComponent box = new UIComponent("div");
        box.addConfig("style", java.util.Map.of("padding", "4px 14px 8px 14px", "display",
                "=items." + enabled + ".state==='ON'?'block':'none'"));
        List<UIComponent> parts = box.addSlot("default");
        UIComponent caption = new UIComponent("Label");
        caption.addConfig("text", "=" + delivered + ".toFixed(1)+' of '+" + target + ".toFixed(0)+' kWh delivered'");
        caption.addConfig("style", java.util.Map.of("font-size", "11px", "opacity", "0.7"));
        parts.add(caption);
        UIComponent track = new UIComponent("div");
        track.addConfig("style", java.util.Map.of("height", "10px", "border-radius", "5px", "background",
                "rgba(127,127,127,0.18)", "overflow", "hidden", "margin-top", "4px"));
        UIComponent fill = new UIComponent("div");
        fill.addConfig("class", List.of("bar"));
        fill.addConfig("style", java.util.Map.of("height", "10px", "border-radius", "5px", "background", "#ef7b3e",
                "width", "=Math.max(0,Math.min(100,100*" + delivered + "/(" + target + "||1)))+'%'"));
        track.addSlot("default").add(fill);
        parts.add(track);
        return box;
    }

    /** The mode Item's label names the car on this site; the number is the fallback. */
    private String carTitle(SiteModel.Car car) {
        Item mode = itemRegistry.get(car.modeItem());
        String label = mode != null ? mode.getLabel() : null;
        return label != null && !label.isBlank() ? label : "Car " + car.number();
    }

    /** A labelled row of choices, the chosen one filled. */
    private UIComponent segmentedRow(String item, String label, String[][] options) {
        UIComponent row = new UIComponent("div");
        row.addConfig("style",
                java.util.Map.of("display", "flex", "align-items", "center", "gap", "10px", "padding", "6px 14px"));
        List<UIComponent> parts = row.addSlot("default");
        UIComponent name = new UIComponent("Label");
        name.addConfig("text", label);
        name.addConfig("style", java.util.Map.of("font-size", "12px", "flex", "0 0 34%", "opacity", "0.8"));
        parts.add(name);
        UIComponent segmented = new UIComponent("f7-segmented");
        segmented.addConfig("raised", Boolean.TRUE);
        segmented.addConfig("style", java.util.Map.of("flex", "1 1 auto", "margin", "0"));
        List<UIComponent> buttons = segmented.addSlot("default");
        for (String[] option : options) {
            UIComponent button = new UIComponent("oh-button");
            button.addConfig("text", option[1]);
            button.addConfig("small", Boolean.TRUE);
            button.addConfig("fill", "=items." + item + ".state === '" + option[0] + "'");
            button.addConfig("action", "command");
            button.addConfig("actionItem", item);
            button.addConfig("actionCommand", option[0]);
            buttons.add(button);
        }
        parts.add(segmented);
        return row;
    }

    // --- compact widget vocabulary ---------------------------------------------------------------
    //
    // Framework7 primitives rather than the stock oh-*-card tiles. A tile is one number in a large box, so a page of
    // them is a page of boxes: tall, repetitive and impossible to scan. A card holding a row of small columns puts
    // six related figures in the space one tile used, which is how the widgets already on this site are built.

    /**
     * One period as a single bar split into what it was made of.
     * <p>
     * Four numbers make you do the arithmetic - how much of that was sun? - and comparing two periods means doing it
     * twice and holding both. One bar answers it by shape: the green length is the share that came off the roof, and
     * the bar's own length says how the period compares to the others because they all share a scale.
     *
     * @param suffix the period's Item suffix
     * @param label the period's name
     * @param scale a javascript expression for the largest period, so every bar is drawn to one scale
     * @return the row, or {@code null} where the period has no Items
     */
    private @org.eclipse.jdt.annotation.Nullable UIComponent periodBar(String suffix, String label, String scale) {
        String sun = "EMS_SelfConsumption_kWh" + suffix;
        String bought = "EMS_Supply_kWh" + suffix;
        if (!has(sun) && !has(bought)) {
            return null;
        }
        UIComponent row = new UIComponent("div");
        row.addConfig("style", java.util.Map.of("padding", "7px 2px"));
        List<UIComponent> parts = row.addSlot("default");

        UIComponent header = new UIComponent("div");
        header.addConfig("style", java.util.Map.of("display", "flex", "justify-content", "space-between", "align-items",
                "baseline", "margin-bottom", "4px"));
        UIComponent name = new UIComponent("Label");
        name.addConfig("text", label);
        name.addConfig("style", java.util.Map.of("font-size", "13px", "font-weight", "bold"));
        List<UIComponent> headerSlot = header.addSlot("default");
        headerSlot.add(name);
        if (has(sun) && has(bought)) {
            String s = "(items." + sun + ".numericState||0)";
            String b = "(items." + bought + ".numericState||0)";
            UIComponent share = new UIComponent("Label");
            share.addConfig("text", "=Math.round(100*" + s + "/((" + s + "+" + b + ")||1))+'% on sun'");
            share.addConfig("style", java.util.Map.of("font-size", "11px", "opacity", "0.7"));
            headerSlot.add(share);
        }
        parts.add(header);

        UIComponent track = new UIComponent("div");
        track.addConfig("style", java.util.Map.of("display", "flex", "height", "14px", "border-radius", "7px",
                "background", "rgba(140,140,140,0.16)", "overflow", "hidden"));
        List<UIComponent> segments = track.addSlot("default");
        if (has(sun)) {
            segments.add(segment(sun, scale, "#43a047"));
        }
        if (has(bought)) {
            segments.add(segment(bought, scale, "#ef5350"));
        }
        parts.add(track);

        UIComponent footer = new UIComponent("div");
        footer.addConfig("style", java.util.Map.of("display", "flex", "gap", "12px", "margin-top", "4px", "font-size",
                "11px", "opacity", "0.8"));
        List<UIComponent> readings = footer.addSlot("default");
        readings.add(reading(sun, "sun", "#43a047"));
        readings.add(reading(bought, "grid", "#ef5350"));
        readings.add(reading("EMS_FeedIn_kWh" + suffix, "sold", "#66bb6a"));
        readings.add(reading("EMS_Cost_EUR" + suffix, "cost", "#ef5350"));
        parts.add(footer);
        return row;
    }

    /** A length of the stacked bar. */
    private UIComponent segment(String item, String scale, String colour) {
        UIComponent part = new UIComponent("div");
        part.addConfig("style",
                java.util.Map.of("height", "14px", "background", colour, "transition", "width 0.6s ease", "width",
                        "=Math.max(0,Math.min(100,100*(items." + item + ".numericState||0)/(" + scale + ")))+'%'"));
        return part;
    }

    /** A small coloured reading under the bar, or nothing where the Item is absent. */
    private UIComponent reading(String item, String label, String colour) {
        UIComponent wrapper = new UIComponent("div");
        if (!has(item)) {
            return wrapper;
        }
        wrapper.addConfig("style", java.util.Map.of("display", "flex", "gap", "4px", "align-items", "baseline"));
        List<UIComponent> parts = wrapper.addSlot("default");
        UIComponent dot = new UIComponent("Label");
        dot.addConfig("text", label);
        dot.addConfig("style", java.util.Map.of("color", colour, "font-size", "10px"));
        parts.add(dot);
        UIComponent value = new UIComponent("oh-label-item");
        value.addConfig("item", item);
        value.addConfig("style", java.util.Map.of("font-size", "11px", "font-weight", "bold"));
        parts.add(value);
        return wrapper;
    }

    /**
     * Pick a period and read it, instead of being handed four the binding chose.
     * <p>
     * Day, month or year, and a stepper for how far back - so "what did last August cost" is two taps rather than a
     * question the dashboard could not answer at all. The figures underneath are republished by the history browser
     * for whatever is selected.
     */
    private @org.eclipse.jdt.annotation.Nullable UIComponent browserCard() {
        if (!has(I_BROWSE_SCALE) || !has(I_BROWSE_BACK)) {
            return null;
        }
        UIComponent card = new UIComponent("f7-card");
        List<UIComponent> slot = card.addSlot("default");

        UIComponent header = new UIComponent("div");
        header.addConfig("style", java.util.Map.of("display", "flex", "align-items", "center", "justify-content",
                "space-between", "padding", "12px 14px 6px 14px"));
        List<UIComponent> headerSlot = header.addSlot("default");
        UIComponent title = new UIComponent("oh-label-item");
        title.addConfig("item", I_BROWSE_LABEL);
        title.addConfig("style", java.util.Map.of("font-size", "17px", "font-weight", "bold"));
        headerSlot.add(title);

        UIComponent segmented = new UIComponent("f7-segmented");
        segmented.addConfig("raised", Boolean.TRUE);
        segmented.addConfig("tag", "p");
        segmented.addConfig("style", java.util.Map.of("margin", "0", "width", "auto"));
        List<UIComponent> buttons = segmented.addSlot("default");
        for (String[] scale : new String[][] { { "day", "Day" }, { "month", "Month" }, { "year", "Year" } }) {
            UIComponent button = new UIComponent("oh-button");
            button.addConfig("text", scale[1]);
            button.addConfig("small", Boolean.TRUE);
            button.addConfig("fill", "=items." + I_BROWSE_SCALE + ".state === '" + scale[0] + "'");
            button.addConfig("action", "command");
            button.addConfig("actionItem", I_BROWSE_SCALE);
            button.addConfig("actionCommand", scale[0]);
            buttons.add(button);
        }
        headerSlot.add(segmented);
        slot.add(header);

        UIComponent stepper = new UIComponent("oh-stepper-item");
        stepper.addConfig("item", I_BROWSE_BACK);
        stepper.addConfig("title", "Periods back");
        stepper.addConfig("min", Integer.valueOf(0));
        stepper.addConfig("max", Integer.valueOf(365));
        stepper.addConfig("step", Integer.valueOf(1));
        slot.add(stepper);

        UIComponent figures = card("", List.of(
                comparedFigure(ITEM_BROWSE_SUPPLY, I_BROWSE_SUPPLY_DELTA, "bought", "arrow_down_left_circle", "red",
                        false),
                comparedFigure(ITEM_BROWSE_SELFCONS, I_BROWSE_SELFCONS_DELTA, "sun used", "sun_max", "orange", true),
                comparedFigure(ITEM_BROWSE_FEEDIN, I_BROWSE_FEEDIN_DELTA, "sold", "arrow_up_right_circle", "green",
                        true),
                comparedFigure(ITEM_BROWSE_COST, I_BROWSE_COST_DELTA, "cost", "money_euro", "red", false)));
        slot.add(figures);
        return card;
    }

    /**
     * The whole day in one strip: twenty-four columns, one per hour.
     * <p>
     * Height is the sun forecast for that hour, colour is what the electricity costs then, and the marker underneath
     * is what the battery intends to do. Three series a person would otherwise have to read separately and hold in
     * their head, laid over each other so the answer to "when should I run something" is a shape rather than a
     * comparison.
     * <p>
     * Built from the hourly series the services already publish and nothing drew: the tariff schedule, the solar
     * forecast and the optimiser's plan string.
     */
    private UIComponent dayStrip() {
        return timelineCard();
    }

    /**
     * The day as lanes on one clock: sun, price, battery plan. Three things a person would read
     * off three widgets, aligned so that "charge when it is cheap and sunny" is a glance.
     */
    private UIComponent timelineCard() {
        UIComponent card = new UIComponent("f7-card");
        card.addConfig("title", "Today, hour by hour");
        UIComponent body = new UIComponent("div");
        body.addConfig("style", java.util.Map.of("padding", "8px 14px 12px 14px", "display", "flex", "flex-direction",
                "column", "gap", "4px"));
        List<UIComponent> slot = body.addSlot("default");

        String peak = hourlyPeakExpression();
        slot.add(laneLabel("Sun expected"));
        UIComponent sun = lane("46px");
        for (int h = 0; h < 24; h++) {
            UIComponent bar = new UIComponent("div");
            bar.addConfig("class", List.of("bar"));
            bar.addConfig("style",
                    java.util.Map.of("align-self", "end", "width", "100%", "border-radius", "2px 2px 0 0", "background",
                            "#f0a83c", "opacity", "=" + isNowExpr(h) + "?'1':(" + h + "<dayjs().hour()?'0.35':'0.8')",
                            "height", "=Math.round(2+44*" + hourlySunExpression(h) + "/" + peak + ")+'px'"));
            sun.getSlots().get("default").add(bar);
        }
        slot.add(sun);

        String low = "(items." + I_TARIFF_MIN + ".numericState||0)";
        String span = "((items." + I_TARIFF_MAX + ".numericState||0)-" + low + ")";
        slot.add(laneLabel("Price - green cheap, red dear"));
        UIComponent price = lane("14px");
        for (int h = 0; h < 24; h++) {
            String p = "Number((items." + I_TARIFF_SCHEDULE + ".state||'').split(',')[" + h + "]||0)";
            String pos = "((" + span + ">0)?((" + p + "-" + low + ")/" + span + "):0)";
            UIComponent cell = new UIComponent("div");
            cell.addConfig("style",
                    java.util.Map.of("border-radius", "2px", "height", "14px", "background",
                            "=" + pos + "<0.34?'#43a047':" + pos + "<0.67?'#ffa726':'#ef5350'", "opacity",
                            "=" + h + "<dayjs().hour()?'0.35':'1'", "outline",
                            "=" + isNowExpr(h) + "?'2px solid var(--f7-text-color)':'none'", "outline-offset", "1px"));
            price.getSlots().get("default").add(cell);
        }
        slot.add(price);

        if (has(I_OPT_PLAN_24H)) {
            slot.add(laneLabel("Battery plan - green charges, purple discharges"));
            UIComponent plan = lane("10px");
            for (int h = 0; h < 24; h++) {
                String c = "((items." + I_OPT_PLAN_24H + ".state||'')[" + h + "]||'.')";
                UIComponent cell = new UIComponent("div");
                cell.addConfig("style", java.util.Map.of("border-radius", "2px", "height", "10px", "background",
                        "=" + c + "==='c'?'#3bb273':" + c + "==='d'?'#7d6cd6':'rgba(127,127,127,0.18)'"));
                plan.getSlots().get("default").add(cell);
            }
            slot.add(plan);
        }

        UIComponent scale = new UIComponent("div");
        scale.addConfig("style", java.util.Map.of("display", "flex", "justify-content", "space-between", "font-size",
                "9px", "opacity", "0.55", "margin-top", "2px"));
        List<UIComponent> marks = scale.addSlot("default");
        for (String mark : List.of("00", "06", "12", "18", "24")) {
            UIComponent label = new UIComponent("Label");
            label.addConfig("text", mark);
            marks.add(label);
        }
        slot.add(scale);
        card.addSlot("default").add(body);
        return card;
    }

    private static String isNowExpr(int hour) {
        return "(dayjs().hour()===" + hour + ")";
    }

    private UIComponent laneLabel(String text) {
        UIComponent label = new UIComponent("Label");
        label.addConfig("text", text);
        label.addConfig("style", java.util.Map.of("font-size", "10px", "opacity", "0.6", "margin-top", "6px"));
        return label;
    }

    /** Twenty-four equal columns, one per hour, for any lane. */
    private UIComponent lane(String height) {
        UIComponent row = new UIComponent("div");
        row.addConfig("style", java.util.Map.of("display", "grid", "grid-template-columns",
                "repeat(24, minmax(0, 1fr))", "gap", "2px", "height", height, "align-items", "end"));
        row.addSlot("default");
        return row;
    }

    /** This hour's forecast watts, out of the {@code HH:MM=watts} series. */
    private String hourlySunExpression(int hour) {
        return "Number(((items." + I_FORECAST_HOURLY + ".state||'').split(',')[" + hour + "]||'=0').split('=')[1]||0)";
    }

    /**
     * The sunniest hour of the day, so the strip is drawn to its own scale.
     * <p>
     * Written out as one {@code Math.max} over all twenty-four rather than a loop, because a MainUI expression
     * cannot declare a function and there is nothing to sort with.
     */
    private String hourlyPeakExpression() {
        StringBuilder peak = new StringBuilder("Math.max(1");
        for (int hour = 0; hour < 24; hour++) {
            peak.append(',').append(hourlySunExpression(hour));
        }
        return peak.append(')').toString();
    }

    /**
     * A circular gauge.
     * <p>
     * A percentage is the one figure a dial says better than a number: full, half or nearly empty reads before the
     * digits do. Everything else on these pages is a quantity, where a dial would be guesswork about the maximum.
     *
     * @param valueExpression a javascript expression yielding 0..100
     * @param caption the label under the dial
     * @param valueText what to print in the middle
     * @param colour the arc colour
     * @return the column
     */
    private UIComponent gaugeColumn(String valueExpression, String caption, String valueText, String colour) {
        UIComponent column = new UIComponent("f7-col");
        column.addConfig("class",
                List.of("display-flex", "flex-direction-column", "align-items-center", "padding-vertical-half"));
        column.addConfig("width", "50");
        column.addConfig("medium", "25");
        List<UIComponent> slot = column.addSlot("default");

        UIComponent gauge = new UIComponent("oh-gauge");
        gauge.addConfig("type", "circle");
        gauge.addConfig("value", valueExpression);
        gauge.addConfig("min", Integer.valueOf(0));
        gauge.addConfig("max", Integer.valueOf(100));
        gauge.addConfig("valueText", valueText);
        gauge.addConfig("valueFontSize", Integer.valueOf(19));
        gauge.addConfig("valueTextColor", colour);
        gauge.addConfig("borderColor", colour);
        gauge.addConfig("borderWidth", Integer.valueOf(9));
        gauge.addConfig("size", Integer.valueOf(96));
        gauge.addConfig("bgColor", "rgba(140,140,140,0.14)");
        slot.add(gauge);

        UIComponent label = new UIComponent("Label");
        label.addConfig("text", caption);
        label.addConfig("style", java.util.Map.of("font-size", "10px", "opacity", "0.65", "margin-top", "4px"));
        slot.add(label);
        return column;
    }

    /** The share of what the building used today that came off the roof rather than the grid. */
    private String selfSufficiencyExpression() {
        String sun = "(items." + I_SELFCONS_DAY + ".numericState||0)";
        String grid = "(items." + I_SUPPLY_DAY + ".numericState||0)";
        return "=Math.max(0,Math.min(100,Math.round(100*" + sun + "/((" + sun + "+" + grid + ")||1))))";
    }

    /**
     * A labelled bar: name on the left, a bar as long as its share, the value on the right.
     * <p>
     * A column of numbers tells you what each circuit used; a column of bars tells you which one to do something
     * about, without reading any of them. The bar is a plain {@code div} whose width is an expression over the same
     * Items, which is how the widgets on this site draw - MainUI accepts raw HTML elements as components.
     *
     * @param item the Item whose value the bar is drawn from
     * @param label the name shown on the left
     * @param colour the bar's colour
     * @param scale a javascript expression for the value the bar is measured against
     * @return the row
     */
    private UIComponent barRow(String item, String label, String colour, String scale) {
        UIComponent row = new UIComponent("div");
        row.addConfig("style",
                java.util.Map.of("display", "flex", "align-items", "center", "gap", "10px", "padding", "5px 2px"));
        List<UIComponent> cells = row.addSlot("default");

        UIComponent name = new UIComponent("Label");
        name.addConfig("text", label);
        name.addConfig("style", java.util.Map.of("flex", "0 0 33%", "font-size", "12px", "opacity", "0.75",
                "white-space", "nowrap", "overflow", "hidden", "text-overflow", "ellipsis"));
        cells.add(name);

        // the track the bar sits in, so short bars still read as "small share" rather than "missing"
        UIComponent track = new UIComponent("div");
        track.addConfig("style", java.util.Map.of("flex", "1 1 auto", "height", "10px", "border-radius", "5px",
                "background", "rgba(140,140,140,0.18)", "overflow", "hidden"));
        UIComponent fill = new UIComponent("div");
        String value = "(items." + item + ".numericState||0)";
        fill.addConfig("style",
                java.util.Map.of("height", "10px", "border-radius", "5px", "background", colour, "transition",
                        "width 0.6s ease", "width",
                        "=Math.max(1,Math.min(100,Math.round(100*" + value + "/(" + scale + "))))+'%'"));
        track.addSlot("default").add(fill);
        cells.add(track);

        UIComponent reading = new UIComponent("oh-label-item");
        reading.addConfig("item", item);
        reading.addConfig("style",
                java.util.Map.of("flex", "0 0 22%", "text-align", "right", "font-size", "12px", "font-weight", "bold"));
        cells.add(reading);
        return row;
    }

    /** A javascript expression for the largest of the given Items, so bars are drawn against the biggest one. */
    private String largestOf(List<String> items) {
        StringBuilder expression = new StringBuilder("Math.max(0.001");
        for (String item : items) {
            expression.append(",(items.").append(item).append(".numericState||0)");
        }
        return expression.append(")").toString();
    }

    /** A card of bars rather than a row of figures. */
    private UIComponent barCard(String title, List<UIComponent> rows) {
        UIComponent card = new UIComponent("f7-card");
        card.addConfig("title", title);
        UIComponent body = new UIComponent("div");
        body.addConfig("style", java.util.Map.of("padding", "4px 14px 12px 14px"));
        body.addSlot("default").addAll(rows);
        card.addSlot("default").add(body);
        return card;
    }

    /** A card with a heading and one row of columns inside it. */
    private UIComponent card(@org.eclipse.jdt.annotation.Nullable String title, List<UIComponent> columns) {
        UIComponent card = new UIComponent("f7-card");
        if (title != null) {
            card.addConfig("title", title);
        }
        UIComponent row = new UIComponent("f7-row");
        row.addConfig("class", List.of("padding-vertical-half"));
        row.addSlot("default").addAll(columns);
        card.addSlot("default").add(row);
        return card;
    }

    /** One figure in a column: an icon, the value, and a small caption under it. */
    /**
     * A figure with how it compares to the same span of the previous period.
     *
     * @param moreIsBetter whether a rise is good news - selling more is, buying more is not, and a
     *            green arrow on a rising bill would be worse than showing nothing
     */
    private UIComponent comparedFigure(String item, String deltaItem, String caption, String icon, String colour,
            boolean moreIsBetter) {
        UIComponent column = figure(item, caption, icon, colour);
        if (!has(deltaItem)) {
            return column;
        }
        String pct = "(items." + deltaItem + ".numericState||0)";
        String good = moreIsBetter ? pct + ">0" : pct + "<0";

        UIComponent chip = new UIComponent("Label");
        chip.addConfig("text", "=" + pct + ">0?'\u25b2 '+Math.round(" + pct + ")+'%':" + pct
                + "<0?'\u25bc '+Math.round(-" + pct + ")+'%':'\u2014'");
        chip.addConfig("style", java.util.Map.of("font-size", "10px", "font-weight", "600", "line-height", "13px",
                "color", "=" + good + "?'#43a047':'#ef5350'",
                // no comparison at all is different from no change, and must not read as a flat zero
                "display",
                "=items." + deltaItem + ".state==='UNDEF'||items." + deltaItem + ".state===null?'none':'block'"));
        // addSlot() allocates a fresh list and overwrites the slot, so appending to an existing one
        // has to go through addComponent - calling addSlot here would erase the icon and the value.
        column.addComponent("default", chip);
        return column;
    }

    private UIComponent figure(String item, String caption, String icon, String colour) {
        UIComponent column = new UIComponent("f7-col");
        column.addConfig("class",
                List.of("display-flex", "flex-direction-column", "align-items-center", "padding-vertical-half"));
        column.addConfig("width", "50");
        column.addConfig("medium", "25");
        List<UIComponent> slot = column.addSlot("default");

        UIComponent glyph = new UIComponent("f7-icon");
        glyph.addConfig("f7", icon);
        glyph.addConfig("color", colour);
        glyph.addConfig("size", Integer.valueOf(22));
        slot.add(glyph);

        UIComponent value = new UIComponent("oh-label-item");
        value.addConfig("item", item);
        value.addConfig("class", List.of("text-align-center"));
        value.addConfig("style", java.util.Map.of("font-weight", "bold", "font-size", "17px", "line-height", "1.25"));
        slot.add(value);

        UIComponent label = new UIComponent("oh-label-item");
        label.addConfig("title", caption);
        label.addConfig("class", List.of("text-align-center"));
        label.addConfig("style", java.util.Map.of("font-size", "10px", "line-height", "1.15", "opacity", "0.65"));
        slot.add(label);
        return column;
    }

    /** A figure, or nothing where its Item is absent. */
    private @org.eclipse.jdt.annotation.Nullable UIComponent figureIfPresent(String item, String caption, String icon,
            String colour) {
        return has(item) ? figure(item, caption, icon, colour) : null;
    }

    /** A card built from whichever figures this site actually has, or nothing if it has none of them. */
    private @org.eclipse.jdt.annotation.Nullable UIComponent figureCard(
            @org.eclipse.jdt.annotation.Nullable String title,
            @org.eclipse.jdt.annotation.Nullable UIComponent... figures) {
        List<UIComponent> present = new ArrayList<>();
        for (UIComponent figure : figures) {
            if (figure != null) {
                present.add(figure);
            }
        }
        return present.isEmpty() ? null : card(title, present);
    }

    /** A whole card as one page row. */
    private UIComponent cardRow(UIComponent card) {
        return item(card, "full");
    }

    /** A switch as a compact row rather than a card of its own. */
    /**
     * One line answering "what is it doing right now" before the page asks you to change anything.
     * Chips rather than another table of rows: these are short states, and a chip that only appears
     * when it matters says more by its absence than a row reading "inactive" ever does.
     */
    private @org.eclipse.jdt.annotation.Nullable UIComponent statusChips() {
        List<UIComponent> chips = new ArrayList<>();
        if (has(ITEM_LEVEL_TEXT)) {
            chips.add(chip(
                    "=items." + ITEM_LEVEL_TEXT + ".state", "=items." + ITEM_LEVEL_TEXT
                            + ".state==='critical'?'red':items." + ITEM_LEVEL_TEXT + ".state==='high'?'orange':'green'",
                    null));
        }
        if (has(I_SHADOW_MODE)) {
            // only worth saying while it is on, because then nothing else on this page takes effect
            chips.add(chip("watching only, not acting", "orange",
                    "=items." + I_SHADOW_MODE + ".state==='ON'?'inline-flex':'none'"));
        }
        if (has(I_TARIFF_SOURCE)) {
            chips.add(chip("=items." + I_TARIFF_SOURCE + ".state",
                    "=items." + I_TARIFF_SOURCE + ".state.indexOf('market')===0?'blue':'orange'", null));
        }
        if (has(I_CAP_WOULD_EXCEED)) {
            chips.add(chip("about to set a new monthly peak", "red",
                    "=items." + I_CAP_WOULD_EXCEED + ".state==='ON'?'inline-flex':'none'"));
        }
        if (has(I_ANOMALY_COUNT)) {
            chips.add(chip("=items." + I_ANOMALY_COUNT + ".numericState+' device(s) behaving oddly'", "orange",
                    "=(items." + I_ANOMALY_COUNT + ".numericState||0)>0?'inline-flex':'none'"));
        }
        if (chips.isEmpty()) {
            return null;
        }
        UIComponent row = new UIComponent("div");
        row.addConfig("style",
                java.util.Map.of("display", "flex", "flex-wrap", "wrap", "gap", "6px", "padding", "12px 14px"));
        row.addSlot("default").addAll(chips);

        UIComponent card = new UIComponent("f7-card");
        card.addSlot("default").add(row);
        return card;
    }

    /** The same chips without a card of their own, for the headline. */
    private @org.eclipse.jdt.annotation.Nullable UIComponent statusChipsRow() {
        UIComponent card = statusChips();
        if (card == null) {
            return null;
        }
        UIComponent row = card.getSlots().get("default").get(0);
        row.addConfig("style",
                java.util.Map.of("display", "flex", "flex-wrap", "wrap", "gap", "6px", "padding", "10px 0 0 0"));
        return row;
    }

    private UIComponent chip(String text, String colour, @org.eclipse.jdt.annotation.Nullable String display) {
        UIComponent chip = new UIComponent("f7-chip");
        chip.addConfig("text", text);
        chip.addConfig("color", colour);
        if (display != null) {
            chip.addConfig("style", java.util.Map.of("display", display));
        }
        return chip;
    }

    /**
     * How the day's hot water is going against the target the slider below sets - the one number on
     * this page where progress matters more than the value.
     */
    private @org.eclipse.jdt.annotation.Nullable UIComponent hotWaterProgress() {
        if (!has(I_BOILER_DELIVERED) || !has(I_SET_BOILER_TARGET)) {
            return null;
        }
        String delivered = "(items." + I_BOILER_DELIVERED + ".numericState||0)";
        String target = "(items." + I_SET_BOILER_TARGET + ".numericState||0)";

        UIComponent bar = new UIComponent("f7-progressbar");
        bar.addConfig("progress", "=" + target + ">0?Math.min(1," + delivered + "/" + target + "):0");
        bar.addConfig("color", "=" + delivered + ">=" + target + "?'green':'blue'");
        bar.addConfig("style", java.util.Map.of("margin", "6px 14px 4px 14px"));

        UIComponent caption = new UIComponent("Label");
        caption.addConfig("text", "=" + delivered + ".toFixed(1)+' of '+" + target + ".toFixed(1)+' kWh heated'");
        caption.addConfig("style",
                java.util.Map.of("padding", "0 14px 12px 14px", "font-size", "11px", "opacity", "0.65"));

        UIComponent card = new UIComponent("f7-card");
        card.addConfig("title", "Hot water today");
        List<UIComponent> slot = card.addSlot("default");
        slot.add(bar);
        slot.add(caption);
        return card;
    }

    /**
     * The settings the EMS actually runs on. These used to be reachable only by editing a Thing and
     * restarting, which is why the control page had nothing but on/off switches on it.
     */
    private @org.eclipse.jdt.annotation.Nullable UIComponent settingsCard(SiteModel site) {
        List<UIComponent> rows = new ArrayList<>();
        org.openhab.binding.emsmanager.internal.config.EmsBridgeConfig bridge = site.bridge();
        if (bridge != null && has(bridge.batteryReserveTargetItem)) {
            rows.add(sliderRow(bridge.batteryReserveTargetItem, "Battery reserve to keep", 0, 100, 5));
        }
        if (has(I_SET_BOILER_TARGET)) {
            rows.add(sliderRow(I_SET_BOILER_TARGET, "Hot water wanted today", 0, 30, 0.5));
        }
        if (has(I_SET_BOILER_READY_BY)) {
            rows.add(stepperRow(I_SET_BOILER_READY_BY, "Hot water ready by", 0, 23, 1));
        }
        if (has(I_SET_GRID_MARGIN)) {
            rows.add(sliderRow(I_SET_GRID_MARGIN, "Grid headroom kept spare", 0, 3000, 50));
        }
        if (has(I_SET_CAPACITY_BUDGET)) {
            rows.add(sliderRow(I_SET_CAPACITY_BUDGET, "Peak budget to stay under", 0, 15000, 250));
        }
        if (rows.isEmpty()) {
            return null;
        }
        UIComponent card = new UIComponent("oh-list-card");
        card.addConfig("title", "How it should behave");
        card.addConfig("footer", "Changes apply within a couple of seconds and survive a restart");
        card.addSlot("default").addAll(rows);
        return card;
    }

    private UIComponent sliderRow(String item, String label, double min, double max, double step) {
        UIComponent row = new UIComponent("oh-slider-item");
        row.addConfig("item", item);
        row.addConfig("title", label);
        row.addConfig("min", min);
        row.addConfig("max", max);
        row.addConfig("step", step);
        row.addConfig("unit", "");
        row.addConfig("label", Boolean.TRUE);
        row.addConfig("scale", Boolean.FALSE);
        // without this the item is commanded on every pixel of the drag
        row.addConfig("releaseOnly", Boolean.TRUE);
        return row;
    }

    private UIComponent stepperRow(String item, String label, int min, int max, int step) {
        UIComponent row = new UIComponent("oh-stepper-item");
        row.addConfig("item", item);
        row.addConfig("title", label);
        row.addConfig("min", min);
        row.addConfig("max", max);
        row.addConfig("step", step);
        return row;
    }

    private UIComponent switchRow(String item, String label) {
        UIComponent toggle = new UIComponent("oh-toggle-item");
        toggle.addConfig("item", item);
        toggle.addConfig("title", label);
        return toggle;
    }

    /** A momentary action as a full-width button. */
    private UIComponent actionButton(String item, String label, String colour) {
        UIComponent button = new UIComponent("oh-button");
        button.addConfig("text", label);
        button.addConfig("large", Boolean.TRUE);
        button.addConfig("fill", Boolean.TRUE);
        button.addConfig("color", colour);
        button.addConfig("action", "command");
        button.addConfig("actionItem", item);
        button.addConfig("actionCommand", "ON");
        button.addConfig("style", java.util.Map.of("margin", "4px 0"));
        return button;
    }

    /** A card holding a list of rows (switches, buttons) rather than a row of figures. */
    /**
     * A warning shown only while the published prices are a stand-in for a feed that could not be
     * reached. Prices drive what the EMS decides to run and when, so a figure the user reads as the
     * market price when it is really a configured guess is the one number here worth interrupting for.
     */
    private @org.eclipse.jdt.annotation.Nullable UIComponent estimateNote() {
        if (!has(I_TARIFF_SOURCE)) {
            return null;
        }
        UIComponent label = new UIComponent("Label");
        label.addConfig("text", "=items." + I_TARIFF_SOURCE + ".state");
        label.addConfig("style", java.util.Map.of("font-size", "13px", "color", "var(--f7-theme-color)"));

        UIComponent box = new UIComponent("div");
        box.addConfig("style",
                java.util.Map.of("display",
                        "=items." + I_TARIFF_SOURCE + ".state.indexOf('market') === 0 ? 'none' : 'block'", "padding",
                        "10px 14px", "margin", "0 8px 8px 8px", "border-radius", "10px", "background",
                        "rgba(255,149,0,0.12)", "border-left", "3px solid #ff9500"));
        box.addSlot("default").add(label);
        return box;
    }

    private UIComponent listCard(String title, List<UIComponent> rows) {
        UIComponent card = new UIComponent("f7-card");
        card.addConfig("title", title);
        card.addSlot("default").addAll(rows);
        return card;
    }

    // --- rendering only what exists -------------------------------------------------------------

    /**
     * Whether an Item is actually present.
     * <p>
     * The pages name items this binding's own services publish, but which of those exist depends on which services a
     * site runs - a site with no tariff has no tariff items. A card whose item is absent renders as a dash on a page
     * somebody is trying to read a number off, so an absent item means no card rather than an empty one.
     */
    private boolean has(String item) {
        return itemRegistry.get(item) != null;
    }

    // --- participant → presentation --------------------------------------------------------------

    private String providerTitle(EnergyProvider p) {
        return switch (p.role()) {
            case PV -> "Solar";
            case GRID -> "Grid";
            case BATTERY -> "Battery";
        };
    }

    private String consumerTitle(EnergyConsumer c) {
        return friendly(c.id());
    }

    /** The item's display label, falling back to a de-underscored item name. */
    private String friendly(String itemName) {
        Item item = itemRegistry.get(itemName);
        if (item != null) {
            String label = item.getLabel();
            if (label != null && !label.isBlank()) {
                return label;
            }
        }
        return itemName.replace('_', ' ');
    }
}

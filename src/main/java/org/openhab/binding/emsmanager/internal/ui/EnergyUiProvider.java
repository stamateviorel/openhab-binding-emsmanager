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
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
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
    private static final String P_AHEAD = "emsmanager_energy_ahead";
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
    private static final String I_TARIFF_CHEAPEST_AT = "EMS_Tariff_Cheapest_Hour_Start";
    private static final String I_TARIFF_DEAREST_AT = "EMS_Tariff_Expensive_Hour_Start";
    private static final String I_TARIFF_NEXT_1H = "EMS_Tariff_Next_1h_Price";
    private static final String I_FORECAST_TOMORROW = "EMS_Forecast_Tomorrow_kWh";
    private static final String I_FORECAST_6H = "EMS_Forecast_Next_6h";
    private static final String I_BOILER_WINDOW = "EMS_BoilerPlan_Window";
    private static final String I_HP_PREHEAT_AT = "EMS_HeatPump_Plan_PreheatAt";

    /** The period suffixes the services publish, in the order a person reads them. */
    private static final String[][] PERIODS = { { "_Yesterday", "Yesterday" }, { "_Last7Days", "Last 7 days" },
            { "_Last30Days", "Last 30 days" }, { "_Year", "This year" } };

    private static final String P_CONTROL = "emsmanager_energy_control";
    private static final String P_HISTORY = "emsmanager_energy_history";
    private static final String P_CIRCUITS = "emsmanager_energy_circuits";

    /** Binding-published switches the control page offers. */
    private static final String I_BOILER_OVERRIDE = "EMS_Boiler_User_Override";
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
    private static final String I_DM_UNTRACKED = "EMS_DeviceMeter_Untracked_W";

    private final Logger logger = LoggerFactory.getLogger(EnergyUiProvider.class);
    private final MetadataRegistry metadataRegistry;
    private final ItemRegistry itemRegistry;
    private final ThingRegistry thingRegistry;
    private volatile List<RootUIComponent> pages = new ArrayList<>();

    @Activate
    public EnergyUiProvider(@Reference MetadataRegistry metadataRegistry, @Reference ItemRegistry itemRegistry,
            @Reference ThingRegistry thingRegistry) {
        this.metadataRegistry = metadataRegistry;
        this.itemRegistry = itemRegistry;
        this.thingRegistry = thingRegistry;
        this.pages = computePages();
        metadataRegistry.addRegistryChangeListener(metadataListener);
        logger.info("EnergyUiProvider activated — Energy section served from the binding (namespace {})", NAMESPACE);
    }

    @Deactivate
    public void deactivate() {
        metadataRegistry.removeRegistryChangeListener(metadataListener);
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
        List<RootUIComponent> out = new ArrayList<>();
        out.add(buildTabsPage());
        out.add(buildAheadPage());
        out.add(buildControlPage(consumers));
        out.add(buildHistoryPage());
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
        tabs.add(tab("Ahead", "f7:arrow_right_circle_fill", P_AHEAD));
        tabs.add(tab("Control", "f7:slider_horizontal_3", P_CONTROL));
        tabs.add(tab("History", "f7:clock_fill", P_HISTORY));
        tabs.add(tab("Power", "f7:chart_bar_alt_fill", P_CHARTS));
        tabs.add(tab("Today by circuit", "f7:chart_pie_fill", P_CIRCUITS));
        return page;
    }

    /** Live gradient tint that tracks the energy level (over the theme card bg). */
    private String levelTintExpr() {
        String n = "items." + ITEM_LEVEL + ".numericState";
        return "=" + n + ">=3?'linear-gradient(135deg,#43a04742,transparent 80%)':" + n
                + ">=2?'linear-gradient(135deg,#7cb34242,transparent 80%)':" + n
                + ">=1?'linear-gradient(135deg,#42a5f542,transparent 80%)':'linear-gradient(135deg,#ef535042,transparent 80%)'";
    }

    /** An intelligent, self-updating status line: solar share + grid flow + energy level. */
    private UIComponent statusBanner(List<EnergyProvider> providers) {
        String grid = null;
        for (EnergyProvider p : providers) {
            if (p.role() == ProviderRole.GRID) {
                grid = p.id();
            }
        }
        String sc = "items." + I_SELFCONS_DAY + ".numericState";
        String sup = "items." + I_SUPPLY_DAY + ".numericState";
        String pct = "Math.round(100*" + sc + "/((" + sc + "+" + sup + ")||1))";
        StringBuilder sentence = new StringBuilder("=" + pct + "+'% solar-powered today'");
        if (grid != null) {
            String g = "items." + grid + ".numericState";
            sentence.append(
                    "+'   \u00b7   '+(" + g + ">=0?'exporting ':'importing ')+Math.round(Math.abs(" + g + "))+' W'");
        }
        sentence.append("+'   \u00b7   energy '+items." + ITEM_LEVEL_TEXT + ".state");

        UIComponent c = new UIComponent("oh-label-card");
        c.addConfig("icon", "f7:bolt_fill");
        c.addConfig("iconColor", "#ffb300");
        c.addConfig("iconSize", Integer.valueOf(34));
        c.addConfig("label", sentence.toString());
        c.addConfig("fontSize", "19px");
        c.addConfig("fontWeight", "600");
        c.addConfig("background", levelTintExpr());
        c.addConfig("style", tileStyle());
        return c;
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
     * What is about to happen, which is the half of an energy system you can still do something about.
     * <p>
     * Deliberately not a live readout. What the roof is making this second is visible on any meter and cannot be
     * acted on; what it will make this afternoon, when the cheap hours fall and what the battery intends to do are
     * the things that change a decision.
     */
    private RootUIComponent buildAheadPage() {
        RootUIComponent page = layoutPage(P_AHEAD, "Ahead");
        List<UIComponent> root = page.addSlot("default");

        UIComponent sun = figureCard("Sun expected",
                figureIfPresent(I_FORECAST_TODAY, "rest of today", "sun_max_fill", "orange"),
                figureIfPresent(I_FORECAST_TOMORROW, "tomorrow", "sun_max", "orange"),
                figureIfPresent(I_FORECAST_6H, "next 6 hours", "sun_min", "orange"),
                figureIfPresent(I_FORECAST_PEAK_AT, "sunniest hour", "clock", "orange"));
        if (sun != null) {
            root.add(cardRow(sun));
        }

        UIComponent prices = figureCard("Prices", figureIfPresent(I_TARIFF_NOW, "now", "money_euro", "blue"),
                figureIfPresent(I_TARIFF_NEXT_1H, "next hour", "money_euro", "blue"),
                figureIfPresent(I_TARIFF_CHEAPEST_AT, "cheapest hour", "arrow_down_circle_fill", "green"),
                figureIfPresent(I_TARIFF_DEAREST_AT, "dearest hour", "arrow_up_circle_fill", "red"));
        if (prices != null) {
            root.add(cardRow(prices));
        }

        UIComponent plan = figureCard("What it intends to do",
                figureIfPresent(I_OPT_NEXT_CHARGE, "battery charges", "arrow_down_circle", "blue"),
                figureIfPresent(I_OPT_NEXT_DISCHARGE, "battery discharges", "arrow_up_circle", "purple"),
                figureIfPresent(I_BOILER_WINDOW, "water heated by", "drop_fill", "blue"),
                figureIfPresent(I_CAP_PROJECTED, "peak heading for", "gauge", "purple"));
        if (plan != null) {
            root.add(cardRow(plan));
        }

        if (has(I_OPT_PLAN_24H)) {
            root.add(cardRow(planCard()));
        }
        return page;
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

    /**
     * What already happened, over the periods the services keep.
     * <p>
     * One row per period rather than one row per figure: the question is "was last week better than the one before",
     * and that is answered by reading across, not by hunting four tiles apart for the same number over two spans.
     */
    private RootUIComponent buildHistoryPage() {
        RootUIComponent page = layoutPage(P_HISTORY, "History");
        List<UIComponent> root = page.addSlot("default");

        for (String[] period : PERIODS) {
            String suffix = period[0];
            UIComponent card = figureCard(period[1],
                    figureIfPresent("EMS_Cost_EUR" + suffix, "bought", "money_euro", "red"),
                    figureIfPresent("EMS_Savings_EUR" + suffix, "saved", "money_euro", "green"),
                    figureIfPresent("EMS_SelfConsumption_kWh" + suffix, "sun used", "sun_max", "orange"),
                    figureIfPresent("EMS_FeedIn_kWh" + suffix, "sold", "arrow_up_right_circle", "green"));
            if (card != null) {
                root.add(cardRow(card));
            }
        }

        // Today's energy circuit by circuit, six to a card instead of one tile each.
        List<UIComponent> circuits = new ArrayList<>();
        for (String circuit : trackedCircuits()) {
            String kwh = "EMS_DM_" + circuit + "_kWh";
            if (has(kwh)) {
                circuits.add(figure(kwh, prettyCircuit(circuit).toLowerCase(java.util.Locale.ROOT), "sum", "purple"));
            }
        }
        if (!circuits.isEmpty()) {
            root.add(cardRow(card("Used today, by circuit", circuits)));
        }

        UIComponent coverage = figureCard("How much of the building this covers",
                figureIfPresent(I_DM_TRACKED, "measured", "checkmark_seal_fill", "green"),
                figureIfPresent(I_DM_UNTRACKED, "not measured", "questionmark_circle", "orange"));
        if (coverage != null) {
            root.add(cardRow(coverage));
        }
        return page;
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

    private UIComponent note(String text) {
        UIComponent c = new UIComponent("oh-label-card");
        c.addConfig("title", text);
        c.addConfig("icon", "f7:info_circle");
        c.addConfig("iconColor", "#9e9e9e");
        c.addConfig("style", tileStyle());
        return c;
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

    private UIComponent tab(String title, String icon, String pageUid) {
        UIComponent t = new UIComponent("oh-tab");
        t.addConfig("title", title);
        t.addConfig("icon", icon);
        t.addConfig("page", pageUid);
        return t;
    }

    /** The energy-level hero: a semicircle gauge (0..3) recolored red->green by level; centre = word. */
    private UIComponent energyLevelGauge() {
        UIComponent g = new UIComponent("oh-gauge-card");
        g.addConfig("item", ITEM_LEVEL);
        g.addConfig("min", Integer.valueOf(0));
        g.addConfig("max", Integer.valueOf(3));
        g.addConfig("type", "circle");
        g.addConfig("size", Integer.valueOf(170));
        g.addConfig("borderWidth", Integer.valueOf(16));
        g.addConfig("labelText", "Energy level");
        g.addConfig("valueText", "=items." + ITEM_LEVEL_TEXT + ".state");
        g.addConfig("valueFontSize", Integer.valueOf(22));
        g.addConfig("valueTextColor", "var(--f7-text-color)");
        g.addConfig("borderColor", "=" + levelColorTernary());
        g.addConfig("style", reactiveCardStyle(levelColorTernary()));
        return g;
    }

    /** Self-sufficiency % gauge — solar self-consumed / total consumption today. */
    private UIComponent selfSufficiencyGauge() {
        UIComponent g = new UIComponent("oh-gauge-card");
        g.addConfig("min", Integer.valueOf(0));
        g.addConfig("max", Integer.valueOf(100));
        g.addConfig("type", "circle");
        g.addConfig("size", Integer.valueOf(170));
        g.addConfig("borderWidth", Integer.valueOf(16));
        g.addConfig("borderColor", "#43a047");
        g.addConfig("labelText", "Self-sufficient");
        g.addConfig("valueFontSize", Integer.valueOf(22));
        g.addConfig("valueTextColor", "var(--f7-text-color)");
        // numericState is the unit-stripped number (the expression sandbox has no parseFloat);
        // guard the divide-by-zero with ||1.
        String sc = "items." + I_SELFCONS_DAY + ".numericState";
        String sup = "items." + I_SUPPLY_DAY + ".numericState";
        String pct = "Math.round(100*" + sc + "/((" + sc + "+" + sup + ")||1))";
        g.addConfig("value", "=" + pct);
        g.addConfig("valueText", "=" + pct + "+'%'");
        g.addConfig("style", reactiveCardStyle("'#43a047'"));
        return g;
    }

    /** A grid column: full-width on a phone, half-width on a tablet+ (two gauges side by side). */
    /** A grid column that fills 1/N of a tablet+ row (packs N cards edge-to-edge). */
    private UIComponent colFill(UIComponent child, int mediumPct) {
        UIComponent c = new UIComponent("oh-grid-col");
        c.addConfig("width", "50");
        c.addConfig("medium", String.valueOf(mediumPct));
        c.addSlot("default").add(child);
        return c;
    }

    private UIComponent colHalf(UIComponent child) {
        UIComponent c = new UIComponent("oh-grid-col");
        c.addConfig("width", "100");
        c.addConfig("medium", "50");
        c.addSlot("default").add(child);
        return c;
    }

    /** Modern tile styling — rounded, soft depth, subtle border. Theme-safe (no fixed bg/text). */
    private java.util.Map<String, Object> tileStyle() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("border-radius", "18px");
        m.put("box-shadow", "0 10px 30px rgba(0,0,0,0.18)");
        m.put("border", "1px solid rgba(140,140,140,0.16)");
        m.put("overflow", "hidden");
        return m;
    }

    /** Level colour (numericState): red -> blue -> lime -> green. Matches the hero ring + glow. */
    private String levelColorTernary() {
        String n = "items." + ITEM_LEVEL + ".numericState";
        return n + ">=3?'#43a047':" + n + ">=2?'#7cb342':" + n + ">=1?'#42a5f5':'#ef5350'";
    }

    /** A reactive card style that TINTS and GLOWS in a live colour (colorExpr = a JS colour fragment). */
    private java.util.Map<String, Object> reactiveCardStyle(String colorExpr) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("border-radius", "18px");
        m.put("border", "1px solid rgba(140,140,140,0.14)");
        m.put("background",
                "='linear-gradient(135deg,'+(" + colorExpr + ")+'42,transparent 82%), var(--f7-card-bg-color)'");
        m.put("box-shadow", "='0 0 60px -16px '+(" + colorExpr + ")+'cc, 0 12px 34px rgba(0,0,0,0.18)'");
        return m;
    }

    /**
     * The site's own item-name patterns, read from the bridge Thing, so the page never assumes what the chargers are
     * called.
     */
    private @org.eclipse.jdt.annotation.Nullable String pattern(String key) {
        for (Thing thing : thingRegistry.getAll()) {
            if (!"emsmanager".equals(thing.getUID().getBindingId())
                    || !"bridge".equals(thing.getThingTypeUID().getId())) {
                continue;
            }
            Object value = thing.getConfiguration().get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private int carCount() {
        for (Thing thing : thingRegistry.getAll()) {
            if (!"emsmanager".equals(thing.getUID().getBindingId())
                    || !"bridge".equals(thing.getThingTypeUID().getId())) {
                continue;
            }
            Object value = thing.getConfiguration().get("carCount");
            if (value instanceof Number number) {
                return number.intValue();
            }
        }
        return 0;
    }

    private @org.eclipse.jdt.annotation.Nullable String carItem(String patternKey, int car) {
        String pattern = pattern(patternKey);
        if (pattern == null) {
            return null;
        }
        String name = String.format(pattern, car);
        return has(name) ? name : null;
    }

    /**
     * The things you can actually change, and the answers to the things you can ask it.
     */
    private RootUIComponent buildControlPage(List<EnergyConsumer> consumers) {
        RootUIComponent page = layoutPage(P_CONTROL, "Control");
        List<UIComponent> root = page.addSlot("default");

        // Every charger in one card, side by side, the way the charging widget on this site does it.
        List<UIComponent> chargers = new ArrayList<>();
        for (int car = 1; car <= carCount(); car++) {
            String mode = carItem("carModeItemPattern", car);
            if (mode != null) {
                chargers.add(chargerColumn(mode, "Car " + car, carItem("carStatusItemPattern", car),
                        carItem("carCableItemPattern", car)));
            }
        }
        if (!chargers.isEmpty()) {
            root.add(cardRow(card("Cars", chargers)));
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

    // --- compact widget vocabulary ---------------------------------------------------------------
    //
    // Framework7 primitives rather than the stock oh-*-card tiles. A tile is one number in a large box, so a page of
    // them is a page of boxes: tall, repetitive and impossible to scan. A card holding a row of small columns puts
    // six related figures in the space one tile used, which is how the widgets already on this site are built.

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
        return block(null, row(col("100", card)));
    }

    /**
     * A charger column: an icon that changes with its state, the mode underneath, and a button per mode.
     * <p>
     * The buttons are built from the Item's own command options, so a site whose modes are not ECO/SNEL/OFF gets its
     * own words on its own buttons. A dropdown hides the choice behind a tap; three buttons show it.
     */
    private UIComponent chargerColumn(String modeItem, String label,
            @org.eclipse.jdt.annotation.Nullable String statusItem,
            @org.eclipse.jdt.annotation.Nullable String cableItem) {
        UIComponent column = new UIComponent("f7-col");
        column.addConfig("class",
                List.of("display-flex", "flex-direction-column", "align-items-center", "padding-vertical-half"));
        column.addConfig("width", "50");
        column.addConfig("medium", "25");
        List<UIComponent> slot = column.addSlot("default");

        UIComponent name = new UIComponent("oh-label-item");
        name.addConfig("title", label);
        name.addConfig("class", List.of("text-align-center"));
        name.addConfig("style", java.util.Map.of("font-weight", "bold", "font-size", "13px"));
        slot.add(name);

        UIComponent glyph = new UIComponent("f7-icon");
        if (cableItem != null) {
            glyph.addConfig("f7", "=items." + cableItem + ".state === 'ON' ? 'bolt_car_fill' : 'bolt_car'");
        } else {
            glyph.addConfig("f7", "bolt_car");
        }
        if (statusItem != null) {
            glyph.addConfig("color", "=items." + statusItem + ".state === 'Charging' ? 'green' : "
                    + (cableItem != null ? "items." + cableItem + ".state === 'ON' ? 'blue' : 'gray'" : "'gray'"));
        } else {
            glyph.addConfig("color", "gray");
        }
        glyph.addConfig("size", Integer.valueOf(34));
        slot.add(glyph);

        if (statusItem != null) {
            UIComponent status = new UIComponent("oh-label-item");
            status.addConfig("item", statusItem);
            status.addConfig("class", List.of("text-align-center"));
            status.addConfig("style", java.util.Map.of("font-size", "10px", "line-height", "1.2", "opacity", "0.7"));
            slot.add(status);
        }

        // the mode itself, always shown - a charger whose Item offers no command options still has a mode, and a
        // column with no buttons and no mode says nothing at all
        UIComponent current = new UIComponent("oh-label-item");
        current.addConfig("item", modeItem);
        current.addConfig("class", List.of("text-align-center"));
        current.addConfig("style", java.util.Map.of("font-size", "12px", "font-weight", "bold"));
        slot.add(current);

        List<UIComponent> buttons = new ArrayList<>();
        for (String option : commandOptions(modeItem)) {
            UIComponent button = new UIComponent("oh-button");
            button.addConfig("text", option);
            button.addConfig("small", Boolean.TRUE);
            button.addConfig("fill", "=items." + modeItem + ".state === '" + option + "'");
            button.addConfig("action", "command");
            button.addConfig("actionItem", modeItem);
            button.addConfig("actionCommand", option);
            buttons.add(button);
        }
        if (!buttons.isEmpty()) {
            UIComponent segmented = new UIComponent("f7-segmented");
            segmented.addConfig("raised", Boolean.TRUE);
            segmented.addConfig("tag", "p");
            segmented.addConfig("style", java.util.Map.of("margin-top", "4px"));
            segmented.addSlot("default").addAll(buttons);
            slot.add(segmented);
        }
        return column;
    }

    /** The commands an Item offers, so the page never invents a mode this site does not have. */
    private List<String> commandOptions(String item) {
        org.openhab.core.items.Item found = itemRegistry.get(item);
        if (found == null) {
            return List.of();
        }
        org.openhab.core.types.CommandDescription description = found.getCommandDescription();
        if (description == null) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        for (org.openhab.core.types.CommandOption option : description.getCommandOptions()) {
            options.add(option.getCommand());
        }
        return options;
    }

    /** A switch as a compact row rather than a card of its own. */
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

    /** A tile, or nothing where the Item behind it does not exist on this site. */
    private @org.eclipse.jdt.annotation.Nullable UIComponent tileIfPresent(String item, String title, String icon,
            String accent) {
        return has(item) ? labelCard(item, title, icon, accent) : null;
    }

    /**
     * A block of tiles, dropping the ones whose Items are absent and the whole block when none survive.
     *
     * @param title the block's heading
     * @param tiles the candidate tiles, nulls allowed
     * @return the block, or {@code null} where this site has nothing to put in it
     */
    private @org.eclipse.jdt.annotation.Nullable UIComponent tileBlock(String title,
            @org.eclipse.jdt.annotation.Nullable UIComponent... tiles) {
        List<UIComponent> cols = new ArrayList<>();
        for (UIComponent tile : tiles) {
            if (tile != null) {
                cols.add(colResponsive(tile));
            }
        }
        return cols.isEmpty() ? null : block(title, row(cols.toArray(new UIComponent[0])));
    }

    /** Adds a block when there is one to add. */
    private void addIfPresent(List<UIComponent> root, @org.eclipse.jdt.annotation.Nullable UIComponent block) {
        if (block != null) {
            root.add(block);
        }
    }

    /**
     * The optimizer's own 24-hour plan, drawn as the string it publishes.
     * <p>
     * One character per hour - charge, discharge or idle - so a glance says what the battery intends to do today. It
     * is monospaced deliberately: the characters line up with the hours only if they are the same width.
     */
    private UIComponent planStrip() {
        UIComponent c = new UIComponent("oh-label-card");
        c.addConfig("item", I_OPT_PLAN_24H);
        c.addConfig("title", "Battery plan, next 24 hours");
        c.addConfig("icon", "f7:square_grid_2x2");
        c.addConfig("iconColor", "#7e57c2");
        c.addConfig("iconSize", Integer.valueOf(30));
        c.addConfig("fontSize", "17px");
        c.addConfig("fontWeight", "600");
        c.addConfig("background", "linear-gradient(135deg, #7e57c222, transparent 72%)");
        java.util.Map<String, Object> style = tileStyle();
        // the characters line up with the hours only if they are all the same width
        style.put("font-family", "monospace");
        style.put("letter-spacing", "2px");
        c.addConfig("style", style);
        return c;
    }

    private UIComponent labelCard(String item, String title, String icon) {
        return labelCard(item, title, icon, "#5b8def");
    }

    /** A modern tile: rounded depth, accent-tinted gradient, big bold value, accent icon. */
    private UIComponent labelCard(String item, String title, String icon, String accent) {
        UIComponent c = new UIComponent("oh-label-card");
        c.addConfig("item", item);
        c.addConfig("title", title);
        c.addConfig("icon", icon);
        c.addConfig("iconColor", accent);
        c.addConfig("iconSize", Integer.valueOf(30));
        c.addConfig("fontSize", "26px");
        c.addConfig("fontWeight", "700");
        c.addConfig("background", "linear-gradient(135deg, " + accent + "22, transparent 72%)");
        c.addConfig("style", tileStyle());
        return c;
    }

    private UIComponent trendCard(String item, String title, String icon, String trendItem) {
        UIComponent c = labelCard(item, title, icon);
        c.addConfig("trendItem", trendItem);
        return c;
    }

    private UIComponent col(String width, UIComponent child) {
        UIComponent c = new UIComponent("oh-grid-col");
        c.addConfig("width", width);
        c.addSlot("default").add(child);
        return c;
    }

    /** A responsive column: two per row on a phone, four on a tablet. */
    private UIComponent colResponsive(UIComponent child) {
        UIComponent c = new UIComponent("oh-grid-col");
        c.addConfig("width", "50");
        c.addConfig("medium", "25");
        c.addSlot("default").add(child);
        return c;
    }

    private UIComponent row(UIComponent... cols) {
        UIComponent r = new UIComponent("oh-grid-row");
        r.addConfig("gap", Boolean.TRUE);
        List<UIComponent> slot = r.addSlot("default");
        for (UIComponent c : cols) {
            slot.add(c);
        }
        return r;
    }

    private UIComponent block(@org.eclipse.jdt.annotation.Nullable String title, UIComponent... rows) {
        UIComponent b = new UIComponent("oh-block");
        if (title != null) {
            b.addConfig("title", title);
        }
        List<UIComponent> slot = b.addSlot("default");
        for (UIComponent r : rows) {
            slot.add(r);
        }
        return b;
    }

    // --- participant → presentation --------------------------------------------------------------

    private String providerTitle(EnergyProvider p) {
        return switch (p.role()) {
            case PV -> "Solar";
            case GRID -> "Grid";
            case BATTERY -> "Battery";
        };
    }

    private String providerIcon(ProviderRole role) {
        return switch (role) {
            case PV -> "oh:solarplant";
            case GRID -> "oh:energy";
            case BATTERY -> "oh:battery_70";
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

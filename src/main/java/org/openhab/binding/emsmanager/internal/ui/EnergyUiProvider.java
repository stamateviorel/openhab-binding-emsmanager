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

    private final Logger logger = LoggerFactory.getLogger(EnergyUiProvider.class);
    private final MetadataRegistry metadataRegistry;
    private final ItemRegistry itemRegistry;
    private volatile List<RootUIComponent> pages = new ArrayList<>();

    @Activate
    public EnergyUiProvider(@Reference MetadataRegistry metadataRegistry, @Reference ItemRegistry itemRegistry) {
        this.metadataRegistry = metadataRegistry;
        this.itemRegistry = itemRegistry;
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
        out.add(buildNowPage(providers, consumers));
        out.add(buildChartsPage(providers, consumers));
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
        tabs.add(tab("Now", "f7:gauge", P_NOW));
        tabs.add(tab("Charts", "f7:chart_bar_alt_fill", P_CHARTS));
        return page;
    }

    /** Live colour that tracks the site energy level (red -> blue -> lime -> green). */
    private String levelColorExpr() {
        String n = "items." + ITEM_LEVEL + ".numericState";
        return "=" + n + ">=3?'#43a047':" + n + ">=2?'#7cb342':" + n + ">=1?'#42a5f5':'#ef5350'";
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

    private RootUIComponent buildNowPage(List<EnergyProvider> providers, List<EnergyConsumer> consumers) {
        RootUIComponent page = layoutPage(P_NOW, "Now");
        List<UIComponent> root = page.addSlot("default");

        // Intelligent, self-updating status line — the EMS talking.
        root.add(block(null, row(col("100", statusBanner(providers)))));

        // Hero: two glanceable gauges side by side — the site energy level (Kai's Energieniveau,
        // recolored red->green) and the self-sufficiency % (solar-covered share of consumption).
        root.add(block(null, row(colHalf(energyLevelGauge()), colHalf(selfSufficiencyGauge()))));

        // At-a-glance KPI strip under the hero — the numbers people actually want.
        root.add(block("Today",
                row(colResponsive(labelCard(I_TARIFF_NOW, "Tariff now", "f7:money_euro")),
                        colResponsive(labelCard(I_FORECAST_TODAY, "Solar forecast", "f7:sun_max", "#ff9800")),
                        colResponsive(labelCard(I_SELFCONS_DAY, "Self-used", "f7:house", "#43a047")),
                        colResponsive(labelCard(I_CAP_PEAK, "Peak this month", "f7:gauge", "#9575cd")))));

        List<UIComponent> devices = new ArrayList<>();
        for (EnergyProvider p : providers) {
            UIComponent card = trendCard(p.id(), providerTitle(p), providerIcon(p.role()), p.id());
            if (p.role() == ProviderRole.GRID) {
                // Reactive: green when exporting to the grid, red when importing from it.
                String g = "items." + p.id() + ".numericState";
                card.addConfig("background", "=" + g
                        + ">=0?'linear-gradient(135deg,#43a04730,transparent 72%)':'linear-gradient(135deg,#ef535030,transparent 72%)'");
            }
            devices.add(card);
        }
        for (EnergyConsumer c : consumers) {
            String measure = c.measureItem();
            devices.add(trendCard(c.id(), consumerTitle(c), "oh:poweroutlet", measure != null ? measure : c.id()));
        }
        if (!devices.isEmpty()) {
            int mw = devices.size() <= 4 ? Math.max(25, 100 / devices.size()) : 25;
            List<UIComponent> cols = new ArrayList<>();
            for (UIComponent d : devices) {
                cols.add(colFill(d, mw));
            }
            root.add(block("Devices", row(cols.toArray(new UIComponent[0]))));
        }

        // Money + CO2 overview row (HA-style headline numbers), fills the page and adds the cost view.
        root.add(block("Money & CO\u2082",
                row(colResponsive(labelCard("EMS_Cost_EUR_Total", "Cost total", "f7:money_euro", "#ef5350")),
                        colResponsive(labelCard("EMS_Savings_EUR_Total", "Saved", "f7:money_euro", "#43a047")),
                        colResponsive(labelCard("EMS_CO2_Net_Today_kg", "CO\u2082 today", "f7:smoke", "#26a69a")),
                        colResponsive(labelCard("EMS_Earnings_EUR_Total", "Earned", "f7:money_euro", "#66bb6a")))));
        return page;
    }

    private RootUIComponent buildChartsPage(List<EnergyProvider> providers, List<EnergyConsumer> consumers) {
        // ONE time-axis chart: live power today for every tagged participant (smooth, area-filled)
        // plus the solar forecast drawn dashed into tomorrow (future=1 extends the window ahead of
        // now). Standalone oh-chart-page (embedded charts render blank); no persistence service set
        // → the site default is used (portable). Drag the slider to pan across time.
        RootUIComponent page = new RootUIComponent(P_CHARTS, "oh-chart-page");
        page.addConfig("label", "Charts");
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
        series.add(powerLine("Solar forecast", ITEM_FORECAST_SERIES, "#ff9800", true, false));
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

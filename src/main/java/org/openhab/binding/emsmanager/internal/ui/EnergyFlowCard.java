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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.config.EmsBridgeConfig;
import org.openhab.core.ui.components.UIComponent;

/**
 * Where the power is going right now, as a picture.
 * <p>
 * Sources on the left, the building in the middle, everything that draws from it on the right.
 * The grid and the battery can be either, so each has a place on both sides and shows on the side
 * that matches what it is doing this second. A ribbon's width is its power and the dots along it
 * move at a pace set by that power, so the picture is only moving where energy is actually moving
 * - nothing on it animates for decoration.
 * <p>
 * Drawn as an inline SVG: MainUI renders raw elements, expressions are allowed on any attribute,
 * and SMIL animation needs no stylesheet. Labels are HTML laid over the picture, because SVG text
 * cannot wrap or take the theme's font.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
final class EnergyFlowCard {

    /** Colours that never repeat between two things on the picture. */
    static final String SUN = "#f0a83c";
    static final String BATTERY = "#3bb273";
    static final String GRID = "#7d6cd6";
    static final String BUILDING = "#5fd0ff";
    private static final Map<String, String> CATEGORY_COLOURS = Map.of("ev", "#ef7b3e", "hvac", "#34b9c9", "heating",
            "#db3b56", "lighting", "#dcc63e", "appliance", "#c8527f", "other", "#9b8f7e");
    private static final Map<String, String> CATEGORY_ICONS = Map.of("ev", "car_fill", "hvac", "snow", "heating",
            "flame_fill", "lighting", "lightbulb_fill", "appliance", "gear_alt_fill", "other", "square_grid_2x2_fill");
    private static final Map<String, String> CATEGORY_LABELS = Map.of("ev", "Cars", "hvac", "Air conditioning",
            "heating", "Heating", "lighting", "Lighting", "appliance", "Appliances", "other", "Other");

    /** The colour a circuit is drawn in: its own if the Thing set one, else its category's. */
    static String colourOf(SiteModel.Circuit c) {
        return c.colour().isBlank() || "#666666".equals(c.colour())
                ? CATEGORY_COLOURS.getOrDefault(c.category(), "#9b8f7e")
                : c.colour();
    }

    /** Above this many circuits a category with this many members is drawn as one ribbon. */
    private static final int MERGE_ABOVE = 8;
    private static final int MERGE_MIN_MEMBERS = 3;

    /** Below this a ribbon is idle: no dots, faint trace, dimmed label. */
    private static final int IDLE_W = 50;

    // Geometry in viewBox units. Labels take the outer quarters, ribbons the middle half.
    private static final int WIDTH = 360;
    private static final int LEFT_X = 96;
    private static final int RIGHT_X = 264;
    private static final int HUB_X = 180;
    private static final int HUB_R = 34;
    private static final int ROW = 30;
    private static final int PAD = 18;

    /** One end of the picture: what it is, what colour, how much power (W, ≥ 0) and when it counts. */
    private record Node(String label, String icon, String colour, String powerExpr, @Nullable String showExpr) {
    }

    private final SiteModel site;
    private final java.util.function.Predicate<String> has;

    EnergyFlowCard(SiteModel site, java.util.function.Predicate<String> has) {
        this.site = site;
        this.has = has;
    }

    /** The card, or nothing when the site has no bridge to read the sources from. */
    @Nullable
    UIComponent build() {
        EmsBridgeConfig cfg = site.bridge();
        if (cfg == null || !has.test(cfg.gridLoadItem) || !has.test(cfg.solarLoadItem)) {
            return null;
        }
        String solar = signed(cfg.solarLoadItem, cfg.invertSolar);
        String grid = signed(cfg.gridLoadItem, cfg.invertGrid);
        String battery = has.test(cfg.batteryLoadItem) ? signed(cfg.batteryLoadItem, cfg.invertBattery) : "0";
        String house = has.test(cfg.houseLoadSumItem) ? signed(cfg.houseLoadSumItem, cfg.invertHouse) : null;

        // engine convention: grid + = export, battery + = charge
        String pv = cfg.solarIncludesBattery ? "Math.max(0,(" + solar + ")+(" + battery + "))"
                : "Math.max(0," + solar + ")";
        String importW = "Math.max(0,-(" + grid + "))";
        String exportW = "Math.max(0," + grid + ")";
        String chargeW = "Math.max(0," + battery + ")";
        String dischargeW = "Math.max(0,-(" + battery + "))";
        boolean hasBattery = has.test(cfg.batteryLoadItem);

        List<Node> sources = new ArrayList<>();
        sources.add(new Node("Solar", "sun_max_fill", SUN, pv, null));
        sources.add(new Node("Grid", "bolt_horizontal_fill", GRID, importW, exportW + "<=" + IDLE_W));
        if (hasBattery) {
            sources.add(new Node("Battery", "battery_25", BATTERY, dischargeW, chargeW + "<=" + IDLE_W));
        }
        List<Node> sinks = new ArrayList<>(consumers());
        sinks.add(new Node("Grid", "bolt_horizontal_fill", GRID, exportW, exportW + ">" + IDLE_W));
        if (hasBattery) {
            sinks.add(new Node("Battery", "battery_25", BATTERY, chargeW, chargeW + ">" + IDLE_W));
        }

        int rows = Math.max(sources.size(), sinks.size());
        int height = rows * ROW + 2 * PAD;
        int hubY = height / 2;
        String hubValue = house != null ? house
                : "(" + pv + "+" + importW + "+" + dischargeW + "-" + exportW + "-" + chargeW + ")";

        UIComponent svg = new UIComponent("svg");
        svg.addConfig("viewBox", "0 0 " + WIDTH + " " + height);
        svg.addConfig("preserveAspectRatio", "xMidYMid meet");
        svg.addConfig("style", Map.of("display", "block", "width", "100%", "height", "auto"));
        List<UIComponent> shapes = svg.addSlot("default");
        List<UIComponent> labels = new ArrayList<>();

        int[] leftYs = rowCentres(sources.size(), height);
        for (int i = 0; i < sources.size(); i++) {
            Node n = sources.get(i);
            shapes.add(ribbon(n, LEFT_X, leftYs[i], HUB_X - HUB_R, hubY));
            labels.add(label(n, leftYs[i], height, true));
        }
        int[] rightYs = rowCentres(sinks.size(), height);
        for (int i = 0; i < sinks.size(); i++) {
            Node n = sinks.get(i);
            shapes.add(ribbon(n, HUB_X + HUB_R, hubY, RIGHT_X, rightYs[i]));
            labels.add(label(n, rightYs[i], height, false));
        }
        shapes.add(hub(hubY));
        labels.add(hubLabel(hubValue, hubY, height));

        UIComponent picture = new UIComponent("div");
        picture.addConfig("style", Map.of("position", "relative", "max-width", "560px", "margin", "0 auto"));
        List<UIComponent> layers = picture.addSlot("default");
        layers.add(svg);
        layers.addAll(labels);

        UIComponent card = new UIComponent("f7-card");
        card.addConfig("title", "Where the power is going");
        UIComponent body = new UIComponent("div");
        body.addConfig("style", Map.of("padding", "4px 8px 10px 8px"));
        body.addSlot("default").add(picture);
        card.addSlot("default").add(body);
        return card;
    }

    /** The site's circuits, one ribbon each unless there are so many that a category is merged. */
    private List<Node> consumers() {
        List<SiteModel.Circuit> circuits = site.circuits();
        Map<String, List<SiteModel.Circuit>> byCategory = new LinkedHashMap<>();
        for (SiteModel.Circuit c : circuits) {
            if (has.test(c.powerItem())) {
                byCategory.computeIfAbsent(c.category(), k -> new ArrayList<>()).add(c);
            }
        }
        int total = byCategory.values().stream().mapToInt(List::size).sum();
        List<Node> out = new ArrayList<>();
        for (var e : byCategory.entrySet()) {
            String category = e.getKey();
            List<SiteModel.Circuit> members = e.getValue();
            if (total > MERGE_ABOVE && members.size() >= MERGE_MIN_MEMBERS) {
                StringBuilder sum = new StringBuilder("(");
                for (int i = 0; i < members.size(); i++) {
                    sum.append(i > 0 ? "+" : "").append(power(members.get(i).powerItem()));
                }
                out.add(new Node(CATEGORY_LABELS.getOrDefault(category, category), icon(category),
                        CATEGORY_COLOURS.getOrDefault(category, "#9b8f7e"), "Math.max(0," + sum + "))", null));
            } else {
                for (SiteModel.Circuit c : members) {
                    String colour = c.colour().isBlank() || "#666666".equals(c.colour())
                            ? CATEGORY_COLOURS.getOrDefault(category, "#9b8f7e")
                            : c.colour();
                    out.add(new Node(c.label(), icon(category), colour, "Math.max(0," + power(c.powerItem()) + ")",
                            null));
                }
            }
        }
        return out;
    }

    private static String icon(String category) {
        return CATEGORY_ICONS.getOrDefault(category, "square_grid_2x2_fill");
    }

    private static String power(String item) {
        return "(items." + item + ".numericState||0)";
    }

    private static String signed(String item, boolean invert) {
        return invert ? "(-" + power(item) + ")" : power(item);
    }

    /** Row centres spread evenly over the height, the same for any count. */
    private static int[] rowCentres(int count, int height) {
        int[] ys = new int[count];
        int span = count * ROW;
        int top = (height - span) / 2;
        for (int i = 0; i < count; i++) {
            ys[i] = top + i * ROW + ROW / 2;
        }
        return ys;
    }

    /**
     * A ribbon and its moving dots. Width says how much, the dots say which way and how fast, and
     * both go quiet under the idle threshold rather than showing a trickle that is really noise.
     */
    private static UIComponent ribbon(Node n, int x1, int y1, int x2, int y2) {
        int mid = (x1 + x2) / 2;
        String d = "M" + x1 + " " + y1 + " C" + mid + " " + y1 + " " + mid + " " + y2 + " " + x2 + " " + y2;
        String p = n.powerExpr();
        String active = "(" + p + ">" + IDLE_W + ")";
        String width = "=Math.max(2,Math.min(12,2+" + p + "/600))";

        UIComponent group = new UIComponent("g");
        if (n.showExpr() != null) {
            group.addConfig("style", Map.of("display", "=(" + n.showExpr() + ")?'inline':'none'"));
        }
        List<UIComponent> parts = group.addSlot("default");

        UIComponent base = new UIComponent("path");
        base.addConfig("d", d);
        base.addConfig("fill", "none");
        base.addConfig("stroke", n.colour());
        base.addConfig("stroke-linecap", "round");
        base.addConfig("stroke-width", width);
        base.addConfig("opacity", "=" + active + "?0.22:0.07");
        parts.add(base);

        UIComponent dots = new UIComponent("path");
        dots.addConfig("d", d);
        dots.addConfig("fill", "none");
        dots.addConfig("stroke", n.colour());
        dots.addConfig("stroke-linecap", "round");
        dots.addConfig("stroke-dasharray", "2 10");
        dots.addConfig("stroke-width", "=Math.max(1.5,Math.min(7,1.5+" + p + "/1000))");
        dots.addConfig("opacity", "=" + active + "?1:0");
        UIComponent motion = new UIComponent("animate");
        motion.addConfig("attributeName", "stroke-dashoffset");
        motion.addConfig("from", "0");
        motion.addConfig("to", "-24");
        // 6 kW runs at the fastest pace; a few hundred watts crawls
        motion.addConfig("dur", "=Math.max(0.4,2.4-" + p + "/3000).toFixed(2)+'s'");
        motion.addConfig("repeatCount", "indefinite");
        dots.addSlot("default").add(motion);
        parts.add(dots);
        return group;
    }

    private static UIComponent hub(int hubY) {
        UIComponent circle = new UIComponent("circle");
        circle.addConfig("cx", String.valueOf(HUB_X));
        circle.addConfig("cy", String.valueOf(hubY));
        circle.addConfig("r", String.valueOf(HUB_R));
        circle.addConfig("stroke", BUILDING);
        circle.addConfig("stroke-width", "3");
        circle.addConfig("style", Map.of("fill", "var(--f7-card-bg-color, #fff)"));
        return circle;
    }

    /** The building's draw in the middle of the picture. */
    private static UIComponent hubLabel(String houseExpr, int hubY, int height) {
        UIComponent box = new UIComponent("div");
        box.addConfig("style", Map.of("position", "absolute", "left", "50%", "top", pct(hubY, height), "transform",
                "translate(-50%,-50%)", "text-align", "center", "line-height", "1.1", "pointer-events", "none"));
        List<UIComponent> lines = box.addSlot("default");
        UIComponent value = new UIComponent("Label");
        value.addConfig("text", "=(" + houseExpr + "/1000).toFixed(1)");
        value.addConfig("style", Map.of("display", "block", "font-size", "17px", "font-weight", "700"));
        lines.add(value);
        UIComponent caption = new UIComponent("Label");
        caption.addConfig("text", "kW building");
        caption.addConfig("style", Map.of("display", "block", "font-size", "9px", "opacity", "0.6"));
        lines.add(caption);
        return box;
    }

    /** Name and kilowatts at the outer end of a ribbon, dimmed while the ribbon is idle. */
    private static UIComponent label(Node n, int y, int height, boolean left) {
        String p = n.powerExpr();
        UIComponent box = new UIComponent("div");
        Map<String, Object> style = new LinkedHashMap<>();
        style.put("position", "absolute");
        style.put("top", pct(y, height));
        style.put("transform", "translateY(-50%)");
        style.put("width", "25%");
        style.put(left ? "left" : "right", "0");
        style.put("display", n.showExpr() == null ? "flex" : "=(" + n.showExpr() + ")?'flex':'none'");
        style.put("flex-direction", "column");
        style.put("align-items", left ? "flex-end" : "flex-start");
        style.put("line-height", "1.15");
        style.put("opacity", "=(" + p + ">" + IDLE_W + ")?1:0.45");
        style.put("pointer-events", "none");
        box.addConfig("style", style);
        List<UIComponent> lines = box.addSlot("default");

        UIComponent head = new UIComponent("div");
        head.addConfig("style",
                Map.of("display", "flex", "align-items", "center", "gap", "3px", "font-size", "10px", "opacity", "0.75",
                        "max-width", "100%", "overflow", "hidden", "white-space", "nowrap", "text-overflow",
                        "ellipsis"));
        List<UIComponent> headParts = head.addSlot("default");
        UIComponent icon = new UIComponent("f7-icon");
        icon.addConfig("f7", n.icon());
        icon.addConfig("size", Integer.valueOf(11));
        icon.addConfig("style", Map.of("color", n.colour()));
        UIComponent name = new UIComponent("Label");
        name.addConfig("text", n.label());
        if (left) {
            headParts.add(name);
            headParts.add(icon);
        } else {
            headParts.add(icon);
            headParts.add(name);
        }
        lines.add(head);

        UIComponent value = new UIComponent("Label");
        value.addConfig("text", "=(" + p + "/1000).toFixed(1)+' kW'");
        value.addConfig("style", Map.of("font-size", "12px", "font-weight", "700", "color", n.colour()));
        lines.add(value);
        return box;
    }

    private static String pct(int y, int height) {
        return String.format(Locale.ROOT, "%.2f%%", 100.0 * y / height);
    }
}

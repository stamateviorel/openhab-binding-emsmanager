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
package org.openhab.binding.emsmanager.internal.ledger;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.controller.analytics.DailyRollup;
import org.openhab.binding.emsmanager.internal.controller.analytics.LongTermStatsController;
import org.openhab.binding.emsmanager.internal.devicemeter.DeviceMeterHandler;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers the Past tab: one table of days, months or circuits for whichever window is selected.
 * <p>
 * Every figure comes from the binding's OWN daily rings - the long-term stats rollups and each device
 * meter's ring - and not from persistence. That is a deliberate choice, not a shortcut: openHAB
 * materialises a whole query range in heap before paging, and one month of one of these counters is
 * some 460 000 points. Reading a ring is free, exact, and cannot take the server down.
 * <p>
 * The rows are sorted, scaled and rendered to JSON here rather than in the page, because a MainUI
 * expression cannot sort and a bar scaled from a widget constant freezes at mount.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class LedgerPublisher {

    /** At most this many rows reach the page; the rest is summarised in the footer. */
    public static final int MAX_ROWS = 60;

    private static final Logger LOGGER = LoggerFactory.getLogger(LedgerPublisher.class);
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    /** The six metrics the stats controller keeps a ring for. */
    private static final String SUN = "EMS_SelfConsumption_kWh";
    private static final String GRID = "EMS_Supply_kWh";
    private static final String SOLD = "EMS_FeedIn_kWh";
    private static final String COST = "EMS_Cost_EUR";
    private static final String SAVED = "EMS_Savings_EUR";

    private static final String[] TOTAL_ITEMS = { "EMS_Ledger_Total_SelfConsumption_kWh", "EMS_Ledger_Total_Supply_kWh",
            "EMS_Ledger_Total_FeedIn_kWh", "EMS_Ledger_Total_Cost_EUR", "EMS_Ledger_Total_Savings_EUR" };

    private final EventPublisher eventPublisher;
    private final ItemRegistry itemRegistry;
    private final @Nullable ThingRegistry thingRegistry;
    private final LongTermStatsController stats;
    private final ZoneId zone;
    private final @Nullable DailySeriesSource series;

    public LedgerPublisher(EventPublisher eventPublisher, ItemRegistry itemRegistry,
            @Nullable ThingRegistry thingRegistry, LongTermStatsController stats, ZoneId zone) {
        this(eventPublisher, itemRegistry, thingRegistry, stats, zone, null);
    }

    public LedgerPublisher(EventPublisher eventPublisher, ItemRegistry itemRegistry,
            @Nullable ThingRegistry thingRegistry, LongTermStatsController stats, ZoneId zone,
            @Nullable DailySeriesSource series) {
        this.eventPublisher = eventPublisher;
        this.itemRegistry = itemRegistry;
        this.thingRegistry = thingRegistry;
        this.stats = stats;
        this.zone = zone;
        this.series = series;
    }

    /** One row of the table, already scaled and formatted. */
    private record Row(String key, String label, String sub, double primary, double[] figures) {
    }

    public void publish(LocalDate today) {
        try {
            // A control the user has never touched is NULL, and a segmented button cannot show which
            // choice is active against NULL. Seed the defaults once so the bar reads correctly.
            String view = seed("EMS_Ledger_View", "days");
            String span = seed("EMS_Ledger_Span", "month");
            seedNumber("EMS_Ledger_Back", 0);
            int back = (int) readNumber("EMS_Ledger_Back");
            String sort = readText("EMS_Ledger_Sort", "");

            if ("actions".equals(view)) {
                // the journal owns this view; leaving a stale day table published behind it would put
                // two answers in the item registry for the same question
                publishText("EMS_Ledger_Cols_JSON", "[]");
                publishText("EMS_Ledger_Rows_JSON", "[]");
                for (String item : TOTAL_ITEMS) {
                    publishUndef(item);
                }
                return;
            }

            Window window = Window.of(span, Math.max(0, back), today);
            List<Row> rows = switch (view) {
                case "months" -> monthRows(today);
                case "circuits" -> circuitRows(window);
                default -> dayRows(window, today);
            };
            rows = sorted(rows, sort);
            publishText("EMS_Ledger_Label", label(view, window, rows.size()));
            publishText("EMS_Ledger_Cols_JSON", columns(view));
            publishText("EMS_Ledger_Rows_JSON", json(rows, columnOf(sort)));
            publishTotals(rows, view);
        } catch (Throwable t) {
            LOGGER.debug("Ledger publish failed: {}", t.toString());
        }
    }

    /** The span of completed days a selection refers to, and whether today's partial belongs to it. */
    private record Window(String span, String title, int fromDaysAgo, int toDaysAgo, boolean includesToday) {
        static Window of(String span, int back, LocalDate today) {
            if ("all".equals(span)) {
                return new Window(span, back == 0 ? "Everything recorded" : "Everything recorded", 1, 400, true);
            }
            if ("year".equals(span)) {
                int year = today.getYear() - back;
                LocalDate from = LocalDate.of(year, 1, 1);
                LocalDate to = back == 0 ? today : LocalDate.of(year, 12, 31);
                // the current year's window ends today, and today is already the first row
                int first = (int) java.time.temporal.ChronoUnit.DAYS.between(to, today);
                return new Window(span, String.valueOf(year), back == 0 ? Math.max(first, 1) : first,
                        (int) java.time.temporal.ChronoUnit.DAYS.between(from, today), back == 0);
            }
            LocalDate first = today.withDayOfMonth(1).minusMonths(back);
            LocalDate last = first.plusMonths(1).minusDays(1);
            boolean current = back == 0;
            int to = (int) java.time.temporal.ChronoUnit.DAYS.between(first, today);
            int from = current ? 1 : (int) java.time.temporal.ChronoUnit.DAYS.between(last, today);
            return new Window(span,
                    first.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + first.getYear(),
                    Math.max(current ? 1 : from, 1), to, current);
        }
    }

    /**
     * One row per day in the window, newest first. A day neither the ring nor the rollup can answer is
     * left out rather than drawn as a row of zeros: the house ran that day, and saying it used nothing
     * is worse than saying nothing.
     */
    private List<Row> dayRows(Window window, LocalDate today) {
        List<Row> rows = new ArrayList<>();
        DailySeriesSource source = series;
        if (source != null) {
            source.load(today.minusDays(Math.min(window.toDaysAgo(), DailySeriesSource.MAX_DAYS)), today);
        }
        if (window.includesToday()) {
            rows.add(dayRow(0, today));
        }
        for (int ago = window.fromDaysAgo(); ago <= window.toDaysAgo() && rows.size() < MAX_ROWS; ago++) {
            Row row = dayRow(ago, today.minusDays(ago));
            if (allUnknown(row)) {
                continue;
            }
            rows.add(row);
        }
        return rows;
    }

    private Row dayRow(int daysAgo, LocalDate date) {
        int held = heldDays();
        double sun = figure(SUN, daysAgo, date, held);
        double grid = figure(GRID, daysAgo, date, held);
        double sold = figure(SOLD, daysAgo, date, held);
        double cost = figure(COST, daysAgo, date, held);
        double saved = figure(SAVED, daysAgo, date, held);
        String sub = daysAgo == 0 ? "so far today"
                : date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        return new Row(date.format(DAY_KEY),
                date.getDayOfMonth() + " " + date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH), sub,
                nz(sun) + nz(grid), new double[] { sun, grid, sold, cost, saved });
    }

    /** The ring where it still reaches, the rollup behind it, NaN where neither knows. */
    private double figure(String metric, int daysAgo, LocalDate date, int held) {
        if (daysAgo <= held) {
            return amount(metric, daysAgo);
        }
        DailySeriesSource source = series;
        Double stored = source == null ? null : source.valueOn(metric, date);
        return stored == null ? Double.NaN : stored;
    }

    private static boolean allUnknown(Row row) {
        for (double figure : row.figures()) {
            if (!Double.isNaN(figure)) {
                return false;
            }
        }
        return true;
    }

    /** NaN reads as nothing when something has to be summed or measured. */
    private static double nz(double value) {
        return Double.isNaN(value) ? 0.0 : value;
    }

    /**
     * One row per calendar month, summed day by day so the ring and the rollup can each answer the part
     * they know. A month with nothing behind it at all is left out; a month only partly recorded totals
     * what it has and says nothing about the rest.
     */
    private List<Row> monthRows(LocalDate today) {
        List<Row> rows = new ArrayList<>();
        int held = heldDays();
        DailySeriesSource source = series;
        if (source != null) {
            source.load(today.minusDays(DailySeriesSource.MAX_DAYS), today);
        }
        LocalDate month = today.withDayOfMonth(1);
        for (int i = 0; i < 24 && rows.size() < MAX_ROWS; i++) {
            LocalDate first = month.minusMonths(i);
            LocalDate last = first.plusMonths(1).minusDays(1);
            if (last.isAfter(today)) {
                last = today;
            }
            double[] f = { Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN };
            String[] metrics = { SUN, GRID, SOLD, COST, SAVED };
            int[] daysKnown = new int[metrics.length];
            int daysInRow = 0;
            for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
                daysInRow++;
                int ago = (int) java.time.temporal.ChronoUnit.DAYS.between(day, today);
                for (int m = 0; m < metrics.length; m++) {
                    double value = figure(metrics[m], ago, day, held);
                    if (!Double.isNaN(value)) {
                        f[m] = nz(f[m]) + value;
                        daysKnown[m]++;
                    }
                }
            }
            // a column short a few days still adds up to a number, and a number nobody is told is
            // partial gets read as the month's total
            int leastCovered = daysInRow;
            for (int m = 0; m < metrics.length; m++) {
                if (daysKnown[m] > 0) {
                    leastCovered = Math.min(leastCovered, daysKnown[m]);
                }
            }
            String sub = first.getYear() + (i == 0 ? " · so far"
                    : leastCovered < daysInRow ? " · " + leastCovered + " of " + daysInRow + " days" : "");
            Row row = new Row(first.format(DAY_KEY), first.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                    sub, nz(f[0]) + nz(f[1]), f);
            if (allUnknown(row)) {
                continue;
            }
            rows.add(row);
        }
        return rows;
    }

    /** One row per metered circuit, totalled over the window from each meter's own ring. */
    private List<Row> circuitRows(Window window) {
        List<Row> rows = new ArrayList<>();
        ThingRegistry things = thingRegistry;
        if (things == null) {
            return rows;
        }
        for (Thing thing : things.getAll()) {
            if (!(thing.getHandler() instanceof DeviceMeterHandler meter)) {
                continue;
            }
            double total = 0.0;
            if (window.includesToday()) {
                total += meter.kwhDaysAgo(0);
            }
            for (int ago = window.fromDaysAgo(); ago <= Math.min(window.toDaysAgo(), meter.daysHeld()); ago++) {
                total += meter.kwhDaysAgo(ago);
            }
            String label = thing.getLabel();
            rows.add(new Row(meter.deviceId(), label == null || label.isBlank() ? meter.name() : label,
                    meter.category(), total, new double[] { total, meter.kwhDaysAgo(0), 0, 0, 0 }));
        }
        rows.sort(Comparator.comparingDouble((Row r) -> r.primary()).reversed());
        return rows;
    }

    private int heldDays() {
        DailyRollup r = stats.rollupOf(SUN);
        return r == null ? 0 : r.daysHeld();
    }

    private double amount(String metric, int daysAgo) {
        DailyRollup r = stats.rollupOf(metric);
        return r == null ? 0.0 : r.amountAgo(daysAgo);
    }

    /** Which figure a sort string ranks by, or -1 for the default order. */
    private static int columnOf(String sort) {
        if (sort.isBlank()) {
            return -1;
        }
        return switch (sort.split(":")[0]) {
            case "sun" -> 0;
            case "grid" -> 1;
            case "sold" -> 2;
            case "cost" -> 3;
            case "saved" -> 4;
            default -> -1;
        };
    }

    private List<Row> sorted(List<Row> rows, String sort) {
        if (sort.isBlank() || rows.isEmpty()) {
            return rows;
        }
        String[] parts = sort.split(":");
        int column = columnOf(sort);
        if (column < 0) {
            return rows;
        }
        boolean descending = parts.length < 2 || !"asc".equals(parts[1]);
        List<Row> copy = new ArrayList<>(rows);
        final int index = column;
        copy.sort(Comparator.comparingDouble((Row r) -> r.figures()[index]));
        if (descending) {
            java.util.Collections.reverse(copy);
        }
        return copy;
    }

    private String label(String view, Window window, int rows) {
        String what = switch (view) {
            case "months" -> "month by month";
            case "circuits" -> "circuit by circuit";
            default -> "day by day";
        };
        String scope = "months".equals(view) ? "as far back as the records go" : window.title();
        // a table silently cut off at its cap reads as the whole answer
        String capped = rows >= MAX_ROWS ? " · newest " + MAX_ROWS + " shown" : "";
        return scope + " · " + what + " · " + rows + (rows == 1 ? " row" : " rows") + capped;
    }

    /**
     * The column headings for this view, in order. The page renders a fixed six and hides the ones
     * this list does not name, so a view with fewer columns does not show empty ones.
     */
    private String columns(String view) {
        return "circuits".equals(view) ? "[\"Circuit\",\"kWh\",\"Today\"]"
                : "[\"When\",\"Sun\",\"Grid\",\"Sold\",\"Cost\",\"Saved\"]";
    }

    /**
     * Rows as JSON, with the bar already scaled here - a widget constant would freeze at mount.
     *
     * @param sortedBy the figure the table is ranked by, or -1. The bar follows it, because a bar
     *            that disagrees with the order the reader asked for is worse than no bar.
     */
    private String json(List<Row> rows, int sortedBy) {
        double max = 0.0;
        for (Row r : rows) {
            max = Math.max(max, Math.abs(nz(sizeOf(r, sortedBy))));
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"k\":\"").append(escape(r.key())).append("\",\"l\":\"").append(escape(r.label()))
                    .append("\",\"s\":\"").append(escape(r.sub())).append("\",\"bar\":")
                    .append(max <= 0 ? 0 : Math.round(100.0 * Math.abs(nz(sizeOf(r, sortedBy))) / max))
                    .append(",\"f\":[");
            for (int f = 0; f < r.figures().length; f++) {
                if (f > 0) {
                    sb.append(',');
                }
                // null, not 0: the page draws a dash for it, and a 0 would be a claim
                sb.append(Double.isNaN(r.figures()[f]) ? "null" : String.valueOf(round(r.figures()[f])));
            }
            sb.append("]}");
        }
        return sb.append(']').toString();
    }

    private void publishTotals(List<Row> rows, String view) {
        double[] totals = new double[5];
        boolean[] complete = { true, true, true, true, true };
        for (Row r : rows) {
            for (int i = 0; i < totals.length && i < r.figures().length; i++) {
                if (Double.isNaN(r.figures()[i])) {
                    complete[i] = false;
                } else {
                    totals[i] += r.figures()[i];
                }
            }
        }
        String[] items = TOTAL_ITEMS;
        for (int i = 0; i < items.length; i++) {
            // a circuits view has no grid/sold/cost/saved of its own, and a zero there would be a lie;
            // so would a month's cost totalled over the handful of days the record still holds
            if (("circuits".equals(view) && i > 0) || !complete[i]) {
                publishUndef(items[i]);
            } else {
                publishNumber(items[i], round(totals[i]));
            }
        }
    }

    private static double sizeOf(Row row, int sortedBy) {
        return sortedBy >= 0 && sortedBy < row.figures().length ? row.figures()[sortedBy] : row.primary();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Read a control, publishing the default the first time it has never been set. */
    private String seed(String item, String fallback) {
        State state = stateOf(item);
        if (state == null || state instanceof UnDefType) {
            publishText(item, fallback);
            return fallback;
        }
        return state.toString();
    }

    private void seedNumber(String item, double fallback) {
        State state = stateOf(item);
        if (state == null || state instanceof UnDefType) {
            publishNumber(item, fallback);
        }
    }

    private String readText(String item, String fallback) {
        State state = stateOf(item);
        return state == null || state instanceof UnDefType ? fallback : state.toString();
    }

    private double readNumber(String item) {
        State state = stateOf(item);
        if (state instanceof DecimalType decimal) {
            return decimal.doubleValue();
        }
        try {
            return state == null ? 0.0 : Double.parseDouble(state.toString().split(" ")[0]);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private @Nullable State stateOf(String itemName) {
        try {
            Item item = itemRegistry.getItem(itemName);
            return item.getState();
        } catch (ItemNotFoundException e) {
            return null;
        }
    }

    private void publishText(String item, String value) {
        post(item, new StringType(value));
    }

    private void publishNumber(String item, double value) {
        post(item, new DecimalType(value));
    }

    private void publishUndef(String item) {
        post(item, UnDefType.UNDEF);
    }

    private void post(String itemName, State value) {
        try {
            itemRegistry.getItem(itemName);
            eventPublisher.post(org.openhab.core.items.events.ItemEventFactory.createStateEvent(itemName, value, null));
        } catch (ItemNotFoundException e) {
            // the site has not declared this Item; the page hides what does not exist
        }
    }
}

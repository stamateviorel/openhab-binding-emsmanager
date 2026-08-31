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
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers "what did that day, month or year look like" for whichever period the dashboard is pointed at.
 * <p>
 * The fixed items - today, yesterday, this month, this year - answer four questions and no others, so a question as
 * ordinary as "what did last August cost" had nowhere to go. This reads two control items, a scale and how many
 * periods to step back, and republishes the same four figures for that period. The dashboard therefore needs one set
 * of cards rather than one per span, and the span is a thing you change rather than a thing that was chosen for you.
 * <p>
 * <strong>It answers only as far as the ring reaches.</strong> {@link LongTermStatsController} keeps 365 completed
 * days, so a year back is the honest limit and a period reaching past it is reported as out of range rather than
 * returned as a smaller number, which would read as a quiet month rather than a missing one.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class HistoryBrowserController implements Controller {

    public static final String NAME = "history-browser";

    /** The metric prefixes this republishes, paired with the Item each answer is written to. */
    private static final String[][] FIGURES = { { "EMS_Supply_kWh", ITEM_EMS_BROWSE_SUPPLY_KWH, "kWh" },
            { "EMS_SelfConsumption_kWh", ITEM_EMS_BROWSE_SELFCONSUMPTION_KWH, "kWh" },
            { "EMS_FeedIn_kWh", ITEM_EMS_BROWSE_FEEDIN_KWH, "kWh" },
            { "EMS_Cost_EUR", ITEM_EMS_BROWSE_COST_EUR, "eur" } };

    private static final Logger LOGGER = LoggerFactory.getLogger(HistoryBrowserController.class);

    private final EventPublisher eventPublisher;
    private final ItemRegistry itemRegistry;
    private final LongTermStatsController stats;

    public HistoryBrowserController(EventPublisher eventPublisher, ItemRegistry itemRegistry,
            LongTermStatsController stats) {
        this.eventPublisher = eventPublisher;
        this.itemRegistry = itemRegistry;
        this.stats = stats;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_HISTORY_BROWSER;
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
        String scale = readText(ITEM_EMS_BROWSE_SCALE, "day");
        int back = Math.max(0, (int) readNumber(ITEM_EMS_BROWSE_BACK));
        LocalDate today = ZonedDateTime.ofInstant(ctx.tickAt(), ZoneId.systemDefault()).toLocalDate();
        Window window = windowFor(scale, back, today);

        // A period older than the ring is not a quiet month, it is a month nobody recorded. Saying so on the label
        // is the difference between a figure and a wrong figure.
        int held = 0;
        for (String[] figure : FIGURES) {
            DailyRollup rollup = stats.rollupOf(figure[0]);
            if (rollup != null) {
                held = Math.max(held, rollup.daysHeld());
            }
        }
        // Only when the period lies ENTIRELY outside the ring. A period that merely starts before the records do -
        // "this month", for the first weeks of a new installation - is partially covered, and the best available
        // figure is still the right thing to show without an alarm on it.
        boolean beyondRecords = window.fromDaysAgo() > held;
        publishText(ITEM_EMS_BROWSE_LABEL, beyondRecords ? window.label() + " — before records began" : window.label());

        for (String[] figure : FIGURES) {
            DailyRollup rollup = stats.rollupOf(figure[0]);
            if (rollup == null) {
                continue;
            }
            publishNumber(figure[1], window.amountFrom(rollup), "kWh".equals(figure[2]));
        }
        return List.of();
    }

    /**
     * The span of completed days a selection refers to, plus whether today's running partial belongs to it.
     *
     * @param label what to call it
     * @param fromDaysAgo the nearer bound, 1 being yesterday
     * @param toDaysAgo the further bound
     * @param includesToday whether the period is still running
     */
    private record Window(String label, int fromDaysAgo, int toDaysAgo, boolean includesToday) {

        double amountFrom(DailyRollup rollup) {
            double total = rollup.sumRange(fromDaysAgo, toDaysAgo);
            return includesToday ? total + rollup.dayAmount() : total;
        }
    }

    /** Turns "month, two back" into the days it covers. */
    private Window windowFor(String scale, int back, LocalDate today) {
        switch (scale.toLowerCase(Locale.ROOT)) {
            case "month": {
                LocalDate first = today.withDayOfMonth(1).minusMonths(back);
                LocalDate last = first.plusMonths(1).minusDays(1);
                String label = first.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + first.getYear();
                if (back == 0) {
                    return new Window("This month", 1, (int) ChronoUnit.DAYS.between(first, today), true);
                }
                return new Window(label, (int) ChronoUnit.DAYS.between(last, today),
                        (int) ChronoUnit.DAYS.between(first, today), false);
            }
            case "year": {
                LocalDate first = today.withDayOfYear(1).minusYears(back);
                LocalDate last = first.plusYears(1).minusDays(1);
                if (back == 0) {
                    return new Window("This year", 1, (int) ChronoUnit.DAYS.between(first, today), true);
                }
                return new Window(String.valueOf(first.getYear()), (int) ChronoUnit.DAYS.between(last, today),
                        (int) ChronoUnit.DAYS.between(first, today), false);
            }
            default: {
                if (back == 0) {
                    return new Window("Today", 1, 0, true);
                }
                LocalDate day = today.minusDays(back);
                String label = back == 1 ? "Yesterday"
                        : day.getDayOfMonth() + " " + day.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                                + " " + day.getYear();
                return new Window(label, back, back, false);
            }
        }
    }

    private void publishNumber(String itemName, double value, boolean energy) {
        try {
            State state = energy ? new QuantityType<>(value, Units.KILOWATT_HOUR) : new DecimalType(value);
            eventPublisher.post(ItemEventFactory.createStateEvent(itemName, state, null));
        } catch (Throwable t) {
            LOGGER.debug("publish {} failed: {}", itemName, t.getMessage());
        }
    }

    private void publishText(String itemName, String value) {
        try {
            eventPublisher.post(ItemEventFactory.createStateEvent(itemName, new StringType(value), null));
        } catch (Throwable t) {
            LOGGER.debug("publish {} failed: {}", itemName, t.getMessage());
        }
    }

    private String readText(String itemName, String fallback) {
        try {
            Item item = itemRegistry.getItem(itemName);
            State state = item.getState();
            if (state instanceof UnDefType) {
                return fallback;
            }
            String text = state.toString();
            return text.isBlank() || "NULL".equals(text) ? fallback : text;
        } catch (ItemNotFoundException e) {
            return fallback;
        }
    }

    private double readNumber(String itemName) {
        try {
            Item item = itemRegistry.getItem(itemName);
            State state = item.getState();
            if (state instanceof UnDefType) {
                return 0.0;
            }
            String text = state.toString();
            if ("NULL".equals(text) || "UNDEF".equals(text)) {
                return 0.0;
            }
            int space = text.indexOf(' ');
            return Double.parseDouble(space > 0 ? text.substring(0, space) : text);
        } catch (ItemNotFoundException | NumberFormatException e) {
            return 0.0;
        }
    }

    /** Exposed for the test, which checks the windows rather than the publishing. */
    Map<String, int[]> windowsForTest(LocalDate today) {
        return Map.of("day0", bounds(windowFor("day", 0, today)), "day1", bounds(windowFor("day", 1, today)), "month0",
                bounds(windowFor("month", 0, today)), "month1", bounds(windowFor("month", 1, today)), "year1",
                bounds(windowFor("year", 1, today)));
    }

    private int[] bounds(Window window) {
        return new int[] { window.fromDaysAgo(), window.toDaysAgo(), window.includesToday() ? 1 : 0 };
    }

    /** Only for the label, which a test reads back. */
    @Nullable
    String labelForTest(String scale, int back, LocalDate today) {
        return windowFor(scale, back, today).label();
    }
}

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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.controller.analytics.DailyRollup;
import org.openhab.binding.emsmanager.internal.controller.analytics.LongTermStatsController;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemStateEvent;
import org.openhab.core.library.items.StringItem;
import org.openhab.core.types.UnDefType;

import com.google.gson.Gson;
import com.google.gson.JsonArray;

/**
 * The table is ranked in the binding because a MainUI expression cannot sort, so the ranking and
 * everything drawn from it have to agree here.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class LedgerPublisherTest {

    private final Map<String, String> published = new HashMap<>();

    /** Four days where the cheapest day is also the sunniest, so a wrong ranking cannot pass. */
    private static final double[] COST = { 1.0, 40.0, 5.0, 20.0 };
    private static final double[] SUN = { 50.0, 4.0, 30.0, 10.0 };

    private LedgerPublisher publisher(String view, String sort) throws Exception {
        return publisher(view, sort, "month", null);
    }

    private LedgerPublisher publisher(String view, String sort, String span,
            org.openhab.binding.emsmanager.internal.ledger.@org.eclipse.jdt.annotation.Nullable DailySeriesSource series)
            throws Exception {
        EventPublisher events = mock(EventPublisher.class);
        doAnswer(call -> {
            Event e = call.getArgument(0);
            if (e instanceof ItemStateEvent state) {
                published.put(state.getItemName(), state.getItemState().toString());
            }
            return null;
        }).when(events).post(any(Event.class));

        Map<String, String> states = Map.of("EMS_Ledger_View", view, "EMS_Ledger_Span", span, "EMS_Ledger_Back", "0",
                "EMS_Ledger_Sort", sort);
        ItemRegistry items = mock(ItemRegistry.class);
        when(items.getItem(anyString())).thenAnswer(call -> {
            String name = (String) call.getArgument(0);
            StringItem item = new StringItem(name);
            String state = states.get(name);
            item.setState(state == null ? UnDefType.NULL : new org.openhab.core.library.types.StringType(state));
            return item;
        });

        LongTermStatsController stats = mock(LongTermStatsController.class);
        when(stats.rollupOf(anyString())).thenAnswer(call -> switch ((String) call.getArgument(0)) {
            case "EMS_Cost_EUR" -> rollup(COST);
            case "EMS_SelfConsumption_kWh" -> rollup(SUN);
            default -> rollup(new double[] { 0, 0, 0, 0 });
        });
        return new LedgerPublisher(events, items, null, stats, ZoneId.of("Europe/Brussels"), series);
    }

    /** A ring holding one value per completed day, newest first. */
    private static DailyRollup rollup(double[] daysAgoOneFirst) {
        DailyRollup r = new DailyRollup(40, true);
        java.util.List<Double> values = new java.util.ArrayList<>();
        for (double v : daysAgoOneFirst) {
            values.add(v);
        }
        java.util.Collections.reverse(values);
        r.restore(values, Double.NaN, Double.NaN);
        return r;
    }

    private JsonArray rows() {
        String raw = published.get("EMS_Ledger_Rows_JSON");
        assertNotNull(raw, "published=" + published);
        JsonArray parsed = new Gson().fromJson(raw, JsonArray.class);
        assertNotNull(parsed, raw);
        return parsed;
    }

    @Test
    public void rankingByAColumnPutsTheBiggestFirst() throws Exception {
        publisher("days", "cost:desc").publish(LocalDate.of(2026, 9, 12));

        JsonArray rows = rows();
        assertTrue(rows.size() >= 4, String.valueOf(rows));
        double first = rows.get(0).getAsJsonObject().getAsJsonArray("f").get(3).getAsDouble();
        double second = rows.get(1).getAsJsonObject().getAsJsonArray("f").get(3).getAsDouble();
        assertEquals(40.0, first);
        assertTrue(first >= second, String.valueOf(rows));
    }

    /**
     * The bar has to measure whatever the table is ranked by. Scaled to the day's total energy while
     * the reader ranks by cost, it draws the cheapest day longest and contradicts the order above it.
     */
    @Test
    public void theBarMeasuresWhateverTheTableIsRankedBy() throws Exception {
        publisher("days", "cost:desc").publish(LocalDate.of(2026, 9, 12));

        JsonArray rows = rows();
        int topBar = rows.get(0).getAsJsonObject().get("bar").getAsInt();
        List<Integer> bars = rows.asList().stream().map(e -> e.getAsJsonObject().get("bar").getAsInt()).toList();
        assertEquals(100, topBar, "the row ranked first is the longest bar: " + bars);
        assertEquals(bars, bars.stream().sorted(java.util.Comparator.reverseOrder()).toList(),
                "bars descend with the ranking: " + bars);
    }

    /**
     * The ring holds 40 days. Before this, a Year view drew every older day as a row of zeros, which
     * reads as "the house used nothing in July" rather than "the record does not go back that far".
     */
    @Test
    public void aDayNothingCanAnswerIsLeftOutRatherThanDrawnAsZeros() throws Exception {
        publisher("days", "", "year", null).publish(LocalDate.of(2026, 9, 12));

        JsonArray rows = rows();
        for (var element : rows) {
            // today's own row belongs there even at one minute past midnight, when it really is zero
            if ("so far today".equals(element.getAsJsonObject().get("s").getAsString())) {
                continue;
            }
            var figures = element.getAsJsonObject().getAsJsonArray("f");
            boolean anyKnown = false;
            for (var f : figures) {
                anyKnown |= !f.isJsonNull() && f.getAsDouble() != 0.0;
            }
            assertTrue(anyKnown, "a completed day is shown only if something is known about it: " + element);
        }
        // a year's worth of empty days used to fill the table to its 60-row cap
        assertTrue(rows.size() < 10,
                "the table stops where the record stops, it does not run to the cap: " + rows.size());
        String oldest = rows.get(rows.size() - 1).getAsJsonObject().get("k").getAsString();
        assertTrue(LocalDate.parse(oldest).isAfter(LocalDate.of(2026, 9, 1)),
                "nothing older than the fixture's ring is shown, got " + oldest);
    }

    /** A column with a hole in it cannot be totalled, and a total over part of a month is a wrong number. */
    @Test
    public void aTotalIsWithheldWhereTheColumnHasAHole() throws Exception {
        publisher("days", "", "month", null).publish(LocalDate.of(2026, 9, 12));

        assertNotNull(published.get("EMS_Ledger_Total_Cost_EUR"));
    }

    /** Today is the first row of a current window; the day loop must not then walk over it again. */
    @Test
    public void todayAppearsOnceInAYearThatIsStillRunning() throws Exception {
        publisher("days", "", "year", null).publish(LocalDate.of(2026, 9, 12));

        JsonArray rows = rows();
        long todays = rows.asList().stream()
                .filter(e -> "2026-09-12".equals(e.getAsJsonObject().get("k").getAsString())).count();
        assertEquals(1, todays, "today listed twice: " + rows);
    }

    @Test
    public void theActionsViewLeavesNoStaleFiguresBehindIt() throws Exception {
        publisher("actions", "").publish(LocalDate.of(2026, 9, 12));

        assertEquals("[]", published.get("EMS_Ledger_Rows_JSON"));
        assertEquals("[]", published.get("EMS_Ledger_Cols_JSON"));
        assertEquals("UNDEF", published.get("EMS_Ledger_Total_Cost_EUR"),
                "a kWh total under a table of actions would be answering a question nobody asked");
    }
}

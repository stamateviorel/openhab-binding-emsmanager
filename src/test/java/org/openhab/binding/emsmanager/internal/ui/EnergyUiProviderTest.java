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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.MetadataRegistry;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.ui.components.RootUIComponent;
import org.openhab.core.ui.components.UIComponent;

/**
 * Tests for {@link EnergyUiProvider}.
 * <p>
 * The page names Items this binding's own services publish, but which of those a site actually has depends on which
 * services it runs. A card whose Item is absent renders as a dash on a page somebody is trying to read a number off,
 * so the rule these tests hold the provider to is: <strong>no Item, no card; no cards, no block.</strong>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class EnergyUiProviderTest {

    /** A provider whose site has exactly the named Items and nothing else. */
    private EnergyUiProvider providerWith(Set<String> presentItems) {
        MetadataRegistry metadata = mock(MetadataRegistry.class);
        when(metadata.getAll()).thenReturn(List.of());
        ItemRegistry items = mock(ItemRegistry.class);
        when(items.get(anyString()))
                .thenAnswer(call -> presentItems.contains(call.getArgument(0)) ? new NumberItem("x") : null);
        List<org.openhab.core.items.Item> all = new ArrayList<>();
        for (String name : presentItems) {
            all.add(new NumberItem(name));
        }
        when(items.getItems()).thenReturn(all);
        return new EnergyUiProvider(metadata, items);
    }

    private @Nullable RootUIComponent page(EnergyUiProvider provider, String uid) {
        for (RootUIComponent page : provider.getAll()) {
            if (uid.equals(page.getUID())) {
                return page;
            }
        }
        return null;
    }

    /** Every block title on a page, in order. */
    private List<String> blockTitles(@Nullable UIComponent component) {
        List<String> found = new ArrayList<>();
        collectBlockTitles(component, found);
        return found;
    }

    private void collectBlockTitles(@Nullable UIComponent component, List<String> found) {
        if (component == null) {
            return;
        }
        Object title = component.getConfig() == null ? null : component.getConfig().get("title");
        // the heading sits on the card now, not on the block that holds it
        if (("oh-block".equals(component.getType()) || "f7-card".equals(component.getType()))
                && title instanceof String text) {
            found.add(text);
        }
        if (component.getSlots() != null) {
            for (List<UIComponent> slot : component.getSlots().values()) {
                for (UIComponent child : slot) {
                    collectBlockTitles(child, found);
                }
            }
        }
    }

    private List<String> itemsOn(@Nullable UIComponent component) {
        List<String> found = new ArrayList<>();
        collectItems(component, found);
        return found;
    }

    private void collectItems(@Nullable UIComponent component, List<String> found) {
        if (component == null) {
            return;
        }
        // an Item can be referenced as the card's own item or as the target of a button's action
        for (String key : List.of("item", "actionItem")) {
            Object item = component.getConfig() == null ? null : component.getConfig().get(key);
            if (item instanceof String name) {
                found.add(name);
            }
        }
        if (component.getSlots() != null) {
            for (List<UIComponent> slot : component.getSlots().values()) {
                for (UIComponent child : slot) {
                    collectItems(child, found);
                }
            }
        }
    }

    @Test
    public void theEnergySectionIsTheTabsPagePlusEveryTab() {
        EnergyUiProvider provider = providerWith(Set.of());
        Collection<RootUIComponent> pages = provider.getAll();

        assertEquals(7, pages.size(), "tabs page plus Past, Future, Now, Control, Power and By circuit");
        for (String uid : List.of("emsmanager_energy", "emsmanager_energy_past", "emsmanager_energy_future",
                "emsmanager_energy_now", "emsmanager_energy_control", "emsmanager_energy_charts",
                "emsmanager_energy_circuits")) {
            assertNotNull(page(provider, uid), "missing page: " + uid);
        }
    }

    /**
     * The circuit chart is drawn from the cumulative energy meters, not the live power ones: those are the meters a
     * site is told to persist, and a chart over an unpersisted Item renders empty.
     */
    @Test
    public void theCircuitChartUsesTheEnergyMetersNotThePowerOnes() {
        RootUIComponent chart = page(providerWith(Set.of("EMS_DM_Airco_W", "EMS_DM_Airco_kWh", "EMS_DM_Boiler_W")),
                "emsmanager_energy_circuits");

        List<String> items = itemsOn(chart);
        assertTrue(items.contains("EMS_DM_Airco_kWh"), "the circuit with an energy meter must be charted");
        assertFalse(items.contains("EMS_DM_Airco_W"), "the live power item would chart empty");
        assertFalse(items.contains("EMS_DM_Boiler_kWh"), "a circuit without an energy meter cannot be charted");
    }

    /**
     * A site running none of the optional services still gets a page rather than an error, and it carries none of the
     * blocks whose Items it does not have.
     */
    @Test
    public void aSiteWithNoneOfTheItemsGetsNoneOfTheBlocks() {
        RootUIComponent now = page(providerWith(Set.of()), "emsmanager_energy_future");

        List<String> blocks = blockTitles(now);
        assertFalse(blocks.contains("Sun expected"));
        assertFalse(blocks.contains("Prices today"));
    }

    @Test
    public void aBlockAppearsAsSoonAsOneOfItsItemsExists() {
        RootUIComponent now = page(providerWith(Set.of("EMS_Tariff_Now_EurPerKWh")), "emsmanager_energy_future");

        assertTrue(blockTitles(now).contains("Prices today"), "one present Item earns the block");
        assertTrue(itemsOn(now).contains("EMS_Tariff_Now_EurPerKWh"));
    }

    @Test
    public void theKillSwitchIsOfferedWhenItExists() {
        RootUIComponent control = page(providerWith(Set.of("EMS_Bridge_Shadow_Mode")), "emsmanager_energy_control");

        assertTrue(blockTitles(control).contains("Stop button"));
        assertTrue(itemsOn(control).contains("EMS_Bridge_Shadow_Mode"));
    }

    /** The breakdown is discovered from the meters that exist, and the roll-ups are left out of it. */
    @Test
    public void theBreakdownListsEachMeasuredCircuitButNotTheRollUps() {
        RootUIComponent devices = page(
                providerWith(Set.of("EMS_DM_Airco_W", "EMS_DM_Airco_kWh", "EMS_DM_Boiler_W", "EMS_DM_Boiler_kWh",
                        "EMS_DM_Cars_W", "EMS_DM_Cars_kWh", "EMS_DM_Lights_W", "EMS_DM_Lights_kWh")),
                "emsmanager_energy_past");

        List<String> items = itemsOn(devices);
        assertTrue(items.contains("EMS_DM_Airco_kWh"));
        assertTrue(items.contains("EMS_DM_Boiler_kWh"));
        assertFalse(items.contains("EMS_DM_Cars_kWh"), "a total next to its own parts reads as double counting");
        assertFalse(items.contains("EMS_DM_Lights_kWh"));
        assertTrue(blockTitles(devices).contains("Today, circuit by circuit"));
    }

    @Test
    public void aSiteWithNoMetersIsToldSoRatherThanShownAnEmptyPage() {
        RootUIComponent history = page(providerWith(Set.of()), "emsmanager_energy_past");

        assertTrue(blockTitles(history).isEmpty(), "a site with nothing kept gets no history headings at all");
    }

    /**
     * Heat-pump Items are named after the Thing a site created, so the page discovers them. A hardcoded name would be
     * a dead card on every site but the one it was written on.
     */
    @Test
    public void heatPumpAdviceIsDiscoveredWhateverThePumpIsCalled() {
        RootUIComponent control = page(providerWith(Set.of("EMS_HP_Warehouse_Reason")), "emsmanager_energy_control");

        assertTrue(itemsOn(control).contains("EMS_HP_Warehouse_Reason"));
    }

    @Test
    public void twoHeatPumpsGetTwoCardsAndNoneGetsNone() {
        assertEquals(2, itemsOn(
                page(providerWith(Set.of("EMS_HP_Hall_Reason", "EMS_HP_Office_Reason")), "emsmanager_energy_control"))
                .stream().filter(i -> i.startsWith("EMS_HP_")).count());
        assertEquals(0, itemsOn(page(providerWith(Set.of()), "emsmanager_energy_control")).stream()
                .filter(i -> i.startsWith("EMS_HP_")).count());
    }

    /**
     * Every component the pages emit must be one MainUI actually ships.
     * <p>
     * An invented type does not error - it renders nothing, so the card is simply missing and nobody finds out until
     * they look at the page. {@code oh-button-card} was exactly that mistake: MainUI has no button card, and four
     * action tiles were silently blank.
     */
    @Test
    public void everyComponentTypeIsOneMainUiShips() {
        Set<String> known = Set.of("oh-tabs-page", "oh-tab", "oh-layout-page", "oh-block", "oh-grid-row", "oh-grid-col",
                "oh-label-card", "oh-toggle-card", "oh-gauge-card", "oh-chart-page", "oh-chart-grid", "oh-time-axis",
                "oh-value-axis", "oh-time-series", "oh-chart-legend", "oh-chart-tooltip", "oh-chart-datazoom",
                "f7-card", "f7-row", "f7-col", "f7-icon", "f7-segmented", "oh-label-item", "oh-button",
                "oh-toggle-item",
                // MainUI renders raw HTML elements too - a plain div is how the widgets on a real site draw bars,
                // and Label is its text primitive
                "div", "Label", "oh-gauge");

        EnergyUiProvider provider = providerWith(Set.of("EMS_Forecast_Now", "EMS_Optimizer_Plan_24h",
                "EMS_Capacity_Current_Quarter", "EMS_Cost_EUR_Month", "EMS_Bridge_Shadow_Mode", "PeakShaving_Enabled",
                "PeakShaving_Manual_Engage", "EMS_BatterySizing_Run", "EMS_DM_Airco_W", "EMS_DM_Airco_kWh"));

        List<String> types = new ArrayList<>();
        for (RootUIComponent page : provider.getAll()) {
            collectTypes(page, types);
        }
        for (String type : types) {
            assertTrue(known.contains(type), "not a MainUI component: " + type);
        }
    }

    private void collectTypes(@Nullable UIComponent component, List<String> found) {
        if (component == null) {
            return;
        }
        String type = component.getType();
        if (type != null) {
            found.add(type);
        }
        if (component.getSlots() != null) {
            for (List<UIComponent> slot : component.getSlots().values()) {
                for (UIComponent child : slot) {
                    collectTypes(child, found);
                }
            }
        }
    }

    /**
     * A card that renders nothing is worse than a missing card: the heading promises something and the space below
     * it is blank.
     * <p>
     * This caught a real one. {@code addSlot("default")} replaces the slot rather than appending to it, so calling it
     * twice on the same component silently discards everything added the first time - the day strip shipped with its
     * hour scale and no hours, and the period bars lost their labels. Nothing else would have noticed: the page was
     * valid, the Items all resolved, and the card was empty.
     */
    @Test
    public void noCardIsEmpty() {
        EnergyUiProvider provider = providerWith(Set.of("EMS_Tariff_Schedule24h_CSV", "EMS_Forecast_Today_Hourly_CSV",
                "EMS_Tariff_Today_Min", "EMS_Tariff_Today_Max", "EMS_Optimizer_Plan_24h", "EMS_SelfConsumption_kWh_Day",
                "EMS_Supply_kWh_Day", "EMS_DM_Airco_kWh", "EMS_DM_Airco_W", "EMS_DeviceMeter_Tracked_W",
                "EMS_DeviceMeter_Untracked_W"));

        List<String> empty = new ArrayList<>();
        for (RootUIComponent page : provider.getAll()) {
            collectEmptyCards(page, empty);
        }
        assertTrue(empty.isEmpty(), "cards with a heading and no content: " + empty);
    }

    private void collectEmptyCards(@Nullable UIComponent component, List<String> empty) {
        if (component == null) {
            return;
        }
        if ("f7-card".equals(component.getType())) {
            Object title = component.getConfig() == null ? null : component.getConfig().get("title");
            int children = 0;
            if (component.getSlots() != null) {
                for (List<UIComponent> slot : component.getSlots().values()) {
                    children += slot.size();
                }
            }
            if (children == 0 && title instanceof String name) {
                empty.add(name);
            }
        }
        if (component.getSlots() != null) {
            for (List<UIComponent> slot : component.getSlots().values()) {
                for (UIComponent child : slot) {
                    collectEmptyCards(child, empty);
                }
            }
        }
    }

    /** The day strip must carry an hour, or it is a heading over a scale. */
    @Test
    public void theDayStripHasItsHours() {
        RootUIComponent future = page(providerWith(Set.of("EMS_Tariff_Schedule24h_CSV", "EMS_Forecast_Today_Hourly_CSV",
                "EMS_Tariff_Today_Min", "EMS_Tariff_Today_Max")), "emsmanager_energy_future");

        List<String> types = new ArrayList<>();
        collectTypes(future, types);
        long divs = types.stream().filter("div"::equals).count();
        assertTrue(divs >= 24, "the strip needs a column per hour, found " + divs + " divs on the page");
    }
}

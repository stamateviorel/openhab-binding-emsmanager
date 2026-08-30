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
import org.openhab.core.config.core.Configuration;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.MetadataRegistry;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.ThingBuilder;
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

    /** A provider whose site has exactly the named Items and no EMS bridge. */
    private EnergyUiProvider providerWith(Set<String> presentItems) {
        return providerWith(presentItems, List.of());
    }

    /** A provider whose site has exactly the named Items, and the given Things. */
    private EnergyUiProvider providerWith(Set<String> presentItems, List<Thing> things) {
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
        ThingRegistry thingRegistry = mock(ThingRegistry.class);
        when(thingRegistry.getAll()).thenReturn(things);
        return new EnergyUiProvider(metadata, items, thingRegistry);
    }

    /** An EMS bridge that names its charger Items the way this site does. */
    private Thing emsBridge(int carCount) {
        Configuration configuration = new Configuration();
        configuration.put("carCount", carCount);
        configuration.put("carModeItemPattern", "Car%d_Mode_OCPP");
        configuration.put("carPauseItemPattern", "Car%d_Pause_OCPP");
        configuration.put("carStatusItemPattern", "Car%d_Status_OCPP");
        return ThingBuilder
                .create(new ThingTypeUID("emsmanager", "bridge"), new ThingUID("emsmanager", "bridge", "main"))
                .withConfiguration(configuration).build();
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
        if ("oh-block".equals(component.getType()) && title instanceof String text) {
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
        Object item = component.getConfig() == null ? null : component.getConfig().get("item");
        if (item instanceof String name) {
            found.add(name);
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

        assertEquals(6, pages.size(), "tabs page plus Now, Control, Where it goes, Power and Today by circuit");
        for (String uid : List.of("emsmanager_energy", "emsmanager_energy_now", "emsmanager_energy_control",
                "emsmanager_energy_devices", "emsmanager_energy_charts", "emsmanager_energy_circuits")) {
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
        RootUIComponent now = page(providerWith(Set.of()), "emsmanager_energy_now");

        List<String> blocks = blockTitles(now);
        assertFalse(blocks.contains("Sun ahead"));
        assertFalse(blocks.contains("Battery"));
        assertFalse(blocks.contains("Grid capacity"));
        assertFalse(blocks.contains("This month"));
    }

    @Test
    public void aBlockAppearsAsSoonAsOneOfItsItemsExists() {
        RootUIComponent now = page(providerWith(Set.of("EMS_Forecast_Now")), "emsmanager_energy_now");

        assertTrue(blockTitles(now).contains("Sun ahead"), "one present Item is enough to earn the block");
        assertTrue(itemsOn(now).contains("EMS_Forecast_Now"));
    }

    /** The half-populated case: the block appears, but only for the Items that are actually there. */
    @Test
    public void onlyThePresentItemsOfABlockAreDrawn() {
        RootUIComponent now = page(providerWith(Set.of("EMS_Forecast_Now", "EMS_Forecast_Next_3h")),
                "emsmanager_energy_now");

        List<String> items = itemsOn(now);
        assertTrue(items.contains("EMS_Forecast_Now"));
        assertTrue(items.contains("EMS_Forecast_Next_3h"));
        assertFalse(items.contains("EMS_Forecast_Next_1h"), "an absent Item must not become a card");
        assertFalse(items.contains("EMS_Forecast_Peak_Today_At"));
    }

    @Test
    public void theBatteryPlanStripIsDrawnOnlyWhenThePlanExists() {
        assertFalse(itemsOn(page(providerWith(Set.of()), "emsmanager_energy_now")).contains("EMS_Optimizer_Plan_24h"));
        assertTrue(itemsOn(page(providerWith(Set.of("EMS_Optimizer_Plan_24h")), "emsmanager_energy_now"))
                .contains("EMS_Optimizer_Plan_24h"));
    }

    @Test
    public void theCapacityBlockAppearsWithItsOwnItems() {
        RootUIComponent now = page(providerWith(Set.of("EMS_Capacity_Current_Quarter", "EMS_Capacity_Status")),
                "emsmanager_energy_now");

        assertTrue(blockTitles(now).contains("Grid capacity"));
        assertTrue(itemsOn(now).contains("EMS_Capacity_Status"));
    }

    /** A fully-equipped site gets the whole page, which is the shape this site actually runs. */
    @Test
    public void aFullyEquippedSiteGetsEveryBlock() {
        RootUIComponent now = page(providerWith(Set.of("EMS_Tariff_Now_EurPerKWh", "EMS_Forecast_Now",
                "EMS_Battery_Setpoint_W", "EMS_Optimizer_Plan_24h", "EMS_Capacity_Current_Quarter",
                "EMS_Cost_EUR_Month", "EMS_Cost_EUR_Total", "EMS_Anomaly_Count_Today")), "emsmanager_energy_now");

        List<String> blocks = blockTitles(now);
        for (String expected : List.of("Today", "Sun ahead", "Battery", "Grid capacity", "This month",
                "Since the beginning", "Worth knowing")) {
            assertTrue(blocks.contains(expected), "missing block: " + expected);
        }
    }

    @Test
    public void everyPageKeepsItsIdentityAcrossRebuilds() {
        EnergyUiProvider provider = providerWith(Set.of("EMS_Forecast_Now"));

        List<String> first = new ArrayList<>();
        provider.getAll().forEach(p -> first.add(p.getUID()));
        List<String> second = new ArrayList<>();
        provider.getAll().forEach(p -> second.add(p.getUID()));

        assertEquals(first, second, "a page's UID is what MainUI keys on; it must not move");
    }

    /**
     * The control page is driven by the site's own configured Item names, so a site that renamed its chargers keeps a
     * working page rather than a row of dead buttons.
     */
    @Test
    public void chargerControlsFollowTheSitesOwnItemNames() {
        EnergyUiProvider provider = providerWith(Set.of("Car1_Mode_OCPP", "Car1_Pause_OCPP"), List.of(emsBridge(4)));

        RootUIComponent control = page(provider, "emsmanager_energy_control");
        assertTrue(blockTitles(control).contains("Charger 1"));
        assertTrue(itemsOn(control).contains("Car1_Mode_OCPP"));
        assertTrue(itemsOn(control).contains("Car1_Pause_OCPP"));
    }

    @Test
    public void aChargerWithoutItsItemsGetsNoBlock() {
        RootUIComponent control = page(providerWith(Set.of(), List.of(emsBridge(4))), "emsmanager_energy_control");

        assertFalse(blockTitles(control).contains("Charger 1"), "no Item, no charger block");
    }

    @Test
    public void withNoBridgeThereAreNoChargerBlocksAtAll() {
        RootUIComponent control = page(providerWith(Set.of("Car1_Mode_OCPP")), "emsmanager_energy_control");

        assertFalse(blockTitles(control).contains("Charger 1"),
                "without a bridge the page cannot know what the chargers are called");
    }

    @Test
    public void theKillSwitchIsOfferedWhenItExists() {
        RootUIComponent control = page(providerWith(Set.of("EMS_Bridge_Shadow_Mode")), "emsmanager_energy_control");

        assertTrue(blockTitles(control).contains("Kill switch"));
        assertTrue(itemsOn(control).contains("EMS_Bridge_Shadow_Mode"));
    }

    /** The breakdown is discovered from the meters that exist, and the roll-ups are left out of it. */
    @Test
    public void theBreakdownListsEachMeasuredCircuitButNotTheRollUps() {
        RootUIComponent devices = page(
                providerWith(Set.of("EMS_DM_Airco_W", "EMS_DM_Boiler_W", "EMS_DM_Cars_W", "EMS_DM_Lights_W")),
                "emsmanager_energy_devices");

        List<String> items = itemsOn(devices);
        assertTrue(items.contains("EMS_DM_Airco_W"));
        assertTrue(items.contains("EMS_DM_Boiler_W"));
        assertFalse(items.contains("EMS_DM_Cars_W"), "a total next to its own parts reads as double counting");
        assertFalse(items.contains("EMS_DM_Lights_W"));
    }

    @Test
    public void aSiteWithNoMetersIsToldSoRatherThanShownAnEmptyPage() {
        RootUIComponent devices = page(providerWith(Set.of()), "emsmanager_energy_devices");

        assertTrue(blockTitles(devices).contains("Nothing measured yet"));
    }

    /**
     * Heat-pump Items are named after the Thing a site created, so the page discovers them. A hardcoded name would be
     * a dead card on every site but the one it was written on.
     */
    @Test
    public void heatPumpAdviceIsDiscoveredWhateverThePumpIsCalled() {
        RootUIComponent now = page(providerWith(Set.of("EMS_HP_Warehouse_Reason")), "emsmanager_energy_now");

        assertTrue(itemsOn(now).contains("EMS_HP_Warehouse_Reason"));
    }

    @Test
    public void twoHeatPumpsGetTwoCardsAndNoneGetsNone() {
        assertEquals(2, itemsOn(
                page(providerWith(Set.of("EMS_HP_Hall_Reason", "EMS_HP_Office_Reason")), "emsmanager_energy_now"))
                .stream().filter(i -> i.startsWith("EMS_HP_")).count());
        assertEquals(0, itemsOn(page(providerWith(Set.of()), "emsmanager_energy_now")).stream()
                .filter(i -> i.startsWith("EMS_HP_")).count());
    }
}

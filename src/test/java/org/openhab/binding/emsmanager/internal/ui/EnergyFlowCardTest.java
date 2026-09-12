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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.MetadataRegistry;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.ui.components.RootUIComponent;
import org.openhab.core.ui.components.UIComponent;

/**
 * The flow picture and the car cards are read off the Things the engine runs on.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class EnergyFlowCardTest {

    private static Thing thing(org.openhab.core.thing.ThingTypeUID type, String id, Map<String, Object> config,
            @Nullable String label) {
        Thing t = mock(Thing.class);
        when(t.getThingTypeUID()).thenReturn(type);
        when(t.getUID()).thenReturn(new ThingUID(type, id));
        when(t.getConfiguration()).thenReturn(new Configuration(config));
        when(t.getLabel()).thenReturn(label);
        return t;
    }

    private static EnergyUiProvider siteWith(Set<String> items, List<Thing> things, Map<String, String> links) {
        MetadataRegistry metadata = mock(MetadataRegistry.class);
        when(metadata.getAll()).thenReturn(List.of());
        ItemRegistry registry = mock(ItemRegistry.class);
        when(registry.get(anyString()))
                .thenAnswer(call -> items.contains(call.getArgument(0)) ? new NumberItem(call.getArgument(0)) : null);
        when(registry.getItems()).thenReturn(List.of());
        ThingRegistry thingRegistry = mock(ThingRegistry.class);
        when(thingRegistry.getAll()).thenReturn(things);
        ItemChannelLinkRegistry linkRegistry = mock(ItemChannelLinkRegistry.class);
        when(linkRegistry.getLinkedItemNames(any(ChannelUID.class))).thenAnswer(call -> {
            String channel = call.getArgument(0).toString();
            String item = links.get(channel);
            return item == null ? Set.of() : Set.of(item);
        });
        return new EnergyUiProvider(metadata, registry, thingRegistry, linkRegistry);
    }

    private static List<Thing> bridgeAndMeters() {
        List<Thing> things = new ArrayList<>();
        things.add(thing(EmsManagerBindingConstants.THING_TYPE_BRIDGE, "main",
                Map.of("gridLoadItem", "Grid_load", "solarLoadItem", "Solar_load", "batteryLoadItem", "Battery_load",
                        "houseLoadSumItem", "House_load_sum", "batteryPercentageItem", "Battery_percentage", "carCount",
                        2, "carModeItemPattern", "Car%d_Mode", "carPlanItemPrefixPattern", "Car%d"),
                "EMS"));
        things.add(thing(EmsManagerBindingConstants.THING_TYPE_DEVICE_METER, "boiler",
                Map.of("name", "Boiler", "powerItem", "x", "category", "heating"), "Boiler"));
        things.add(thing(EmsManagerBindingConstants.THING_TYPE_DEVICE_METER, "car1",
                Map.of("name", "Car 1", "powerItem", "x", "category", "ev"), "Auto 1"));
        return things;
    }

    private static Map<String, String> links() {
        return Map.of("emsmanager:device-meter:boiler:currentW", "EMS_DM_Boiler_W",
                "emsmanager:device-meter:car1:currentW", "EMS_DM_Car1_W", "emsmanager:device-meter:boiler:kwhToday",
                "EMS_DM_Boiler_kWh");
    }

    @Test
    void theCircuitChartTakesTheCountersFromTheThingsNotFromNames() {
        EnergyUiProvider p = siteWith(Set.of("Grid_load", "Solar_load", "EMS_DM_Boiler_W", "EMS_DM_Boiler_kWh"),
                bridgeAndMeters(), links());
        UIComponent chart = page(p, "emsmanager_energy_circuits");
        assertEquals(1, count(chart, "oh-aggregate-series"), "the boiler has a counter, the car does not");
    }

    /**
     * A chart page renders completely blank - silently - as soon as it has a second grid or a second value axis.
     * This tab shipped empty for a week because of it, so the shape is the test.
     */
    @Test
    void aChartPageKeepsToOneGridAndOneValueAxis() {
        EnergyUiProvider p = siteWith(
                Set.of("Grid_load", "Solar_load", "Battery_percentage", "House_load_sum", "EMS_DM_Boiler_kWh"),
                bridgeAndMeters(), links());
        for (String uid : List.of("emsmanager_energy_charts", "emsmanager_energy_circuits")) {
            RootUIComponent page = page(p, uid);
            assertNotNull(page, uid);
            assertEquals(1, count(page, "oh-chart-grid"), uid + " must have exactly one grid");
            assertEquals(1, count(page, "oh-value-axis"), uid + " must have exactly one value axis");
        }
    }

    /** A dataZoom on a category axis blanks the chart the same silent way. */
    @Test
    void theCategoryAxisChartHasNoDataZoom() {
        EnergyUiProvider p = siteWith(Set.of("Grid_load", "Solar_load", "EMS_DM_Boiler_kWh", "EMS_DM_Boiler_W"),
                bridgeAndMeters(), links());
        RootUIComponent circuits = page(p, "emsmanager_energy_circuits");
        assertNotNull(circuits);
        assertEquals(1, count(circuits, "oh-category-axis"));
        assertEquals(0, count(circuits, "oh-chart-datazoom"), "a category axis has no range to zoom");
    }

    private static @Nullable UIComponent find(@Nullable UIComponent c, String type) {
        if (c == null) {
            return null;
        }
        if (type.equals(c.getType())) {
            return c;
        }
        if (c.getSlots() != null) {
            for (List<UIComponent> slot : c.getSlots().values()) {
                for (UIComponent child : slot) {
                    UIComponent hit = find(child, type);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
        }
        return null;
    }

    private static int count(@Nullable UIComponent c, String type) {
        if (c == null) {
            return 0;
        }
        int n = type.equals(c.getType()) ? 1 : 0;
        if (c.getSlots() != null) {
            for (List<UIComponent> slot : c.getSlots().values()) {
                for (UIComponent child : slot) {
                    n += count(child, type);
                }
            }
        }
        return n;
    }

    private static @Nullable RootUIComponent page(EnergyUiProvider p, String uid) {
        for (RootUIComponent page : p.getAll()) {
            if (uid.equals(page.getUID())) {
                return page;
            }
        }
        return null;
    }

    @Test
    void theFlowDrawsEverySourceAndSinkTheSiteHas() {
        EnergyUiProvider p = siteWith(
                Set.of("Grid_load", "Solar_load", "Battery_load", "House_load_sum", "EMS_DM_Boiler_W", "EMS_DM_Car1_W"),
                bridgeAndMeters(), links());

        UIComponent svg = find(page(p, "emsmanager_energy_now"), "svg");
        assertNotNull(svg, "a site with a bridge gets the picture");
        // sun, grid in, battery out | boiler, car, grid out, battery in: seven ribbons of two paths
        assertEquals(7, count(svg, "g"));
        assertEquals(14, count(svg, "path"));
        assertEquals(7, count(svg, "animate"), "every ribbon moves");
        assertEquals(1, count(svg, "circle"), "one building");
    }

    @Test
    void noBridgeMeansNoPictureAndNoCrash() {
        EnergyUiProvider p = siteWith(Set.of("Grid_load", "Solar_load"), List.of(), Map.of());
        assertNull(find(page(p, "emsmanager_energy_now"), "svg"));
    }

    @Test
    void aMissingGridItemMeansNoPicture() {
        EnergyUiProvider p = siteWith(Set.of("Solar_load"), bridgeAndMeters(), links());
        assertNull(find(page(p, "emsmanager_energy_now"), "svg"), "half a picture would be a wrong picture");
    }

    @Test
    void aSiteWithoutABatteryHasNoBatteryRibbons() {
        EnergyUiProvider p = siteWith(Set.of("Grid_load", "Solar_load", "EMS_DM_Boiler_W"), bridgeAndMeters(), links());
        UIComponent svg = find(page(p, "emsmanager_energy_now"), "svg");
        assertEquals(4, count(svg, "g"), "sun, grid in | boiler, grid out");
    }

    @Test
    void aCarGetsItsControlsOnlyWhereItsPlanItemsExist() {
        EnergyUiProvider with = siteWith(
                Set.of("Grid_load", "Solar_load", "Car1_Mode", "Car1_Plan_Enabled", "Car1_Plan_Target_kWh",
                        "Car1_Plan_Departure_At", "Car1_Plan_Strategy", "Car1_Plan_Status"),
                bridgeAndMeters(), links());
        UIComponent control = page(with, "emsmanager_energy_cars");
        assertNotNull(find(control, "oh-input-item"), "the departure time is an input");
        assertEquals(2, count(control, "f7-segmented"), "charging mode and plan strategy for car 1; car 2 has nothing");

        EnergyUiProvider without = siteWith(Set.of("Grid_load", "Solar_load"), bridgeAndMeters(), links());
        assertEquals(0, count(page(without, "emsmanager_energy_cars"), "f7-segmented"));
        assertEquals(0, count(page(with, "emsmanager_energy_control"), "oh-input-item"), "the chargers left Control");
    }

    @Test
    void theHeadlineSpeaksInKilowattsNotEngineWords() {
        EnergyUiProvider p = siteWith(Set.of("Grid_load", "Solar_load"), bridgeAndMeters(), links());
        List<String> texts = new ArrayList<>();
        collectTexts(page(p, "emsmanager_energy_now"), texts);
        String all = String.join("\n", texts);
        assertTrue(all.contains("Selling"), "the sentence says what the building is doing");
        assertTrue(all.contains("Buying"));
    }

    private static void collectTexts(@Nullable UIComponent c, List<String> out) {
        if (c == null) {
            return;
        }
        Object text = c.getConfig().get("text");
        if (text != null) {
            out.add(text.toString());
        }
        if (c.getSlots() != null) {
            for (List<UIComponent> slot : c.getSlots().values()) {
                for (UIComponent child : slot) {
                    collectTexts(child, out);
                }
            }
        }
    }

    @Test
    void theTimelineStretchesToThirtySixHoursWithACarLaneOnceTomorrowIsKnown() {
        EnergyUiProvider p = siteWith(
                Set.of("Grid_load", "Solar_load", "EMS_Tariff_Schedule24h_CSV", "EMS_Tariff_Schedule48h_CSV",
                        "EMS_Forecast_Today_Hourly_CSV", "EMS_Forecast_Tomorrow_Hourly_CSV", "EMS_Tariff_Today_Min",
                        "EMS_Tariff_Today_Max", "Car1_Plan_Enabled", "Car1_Plan_Hours", "Car1_Plan_Status"),
                bridgeAndMeters(), links());
        UIComponent future = page(p, "emsmanager_energy_future");
        List<String> texts = new ArrayList<>();
        collectTexts(future, texts);
        assertTrue(texts.stream().anyMatch(x -> x.contains("when it will charge")), "car 1 gets a lane");
        assertTrue(texts.stream().anyMatch(x -> x.contains("not published yet")), "the two-day price lane is drawn");
        // sun 36 + price 36 + car 36 columns at least
        assertTrue(count(future, "div") >= 108, "thirty-six columns per lane, found " + count(future, "div"));
    }

    @Test
    void withoutTomorrowTheTimelineStaysAtToday() {
        EnergyUiProvider p = siteWith(Set.of("Grid_load", "Solar_load", "EMS_Tariff_Schedule24h_CSV",
                "EMS_Forecast_Today_Hourly_CSV", "EMS_Tariff_Today_Min", "EMS_Tariff_Today_Max"), bridgeAndMeters(),
                links());
        List<String> texts = new ArrayList<>();
        collectTexts(page(p, "emsmanager_energy_future"), texts);
        assertFalse(texts.stream().anyMatch(x -> x.contains("not published yet")));
    }
}

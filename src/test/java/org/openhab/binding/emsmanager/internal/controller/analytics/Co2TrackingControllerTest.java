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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.types.DecimalType;

/**
 * Tests for {@link Co2TrackingController} — pins the avoided-emissions accounting.
 *
 * <p>
 * The original code only credited grid <em>exports</em> (× injection-offset
 * factor) and silently ignored self-consumed solar, which on a battery+PV site
 * is the larger term — leaving CO₂-saved ~10× below what the € savings metric
 * (which counts the same self-consumption) implied. These tests lock in that
 * self-consumed solar is now credited at the grid factor, with correct units.
 *
 * <p>
 * The restore tests model the same restart as the cost analytics ones: items that have not been
 * restored yet read as NULL on the first tick, and that tick used to publish 0 over the year total.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class Co2TrackingControllerTest {

    private static final double GRID = 140.0; // g CO₂ / kWh avoided per self-consumed kWh
    private static final double OFFSET = 350.0; // g CO₂ / kWh avoided per exported kWh
    private static final List<String> ITEMS = List.of("EMS_CO2_Today_kg", "EMS_CO2_Saved_Today_kg",
            "EMS_CO2_Net_Today_kg", "EMS_CO2_Year_kg", "EMS_CO2_Saved_Year_kg");

    private static Path userdata = Path.of("");

    private static final @org.eclipse.jdt.annotation.Nullable String PREVIOUS_USERDATA = System
            .getProperty("openhab.userdata");

    @BeforeAll
    static void redirectCacheAwayFromTheLiveInstall() throws IOException {
        userdata = Files.createTempDirectory("ems-co2-test");
        Files.createDirectories(userdata.resolve("cache"));
        userdata.toFile().deleteOnExit();
        System.setProperty("openhab.userdata", userdata.toString());
    }

    @AfterAll
    static void restoreCacheLocation() {
        // Restoring rather than clearing: the build sets this property for the whole JVM, and a
        // test class that wipes it silently un-sandboxes every class that runs after it.
        String previous = PREVIOUS_USERDATA;
        if (previous == null) {
            System.clearProperty("openhab.userdata");
        } else {
            System.setProperty("openhab.userdata", previous);
        }
    }

    @BeforeEach
    void clearSnapshot() throws IOException {
        Files.deleteIfExists(userdata.resolve("cache").resolve("emsmanager-co2-cache.json"));
    }

    /** Every item exists; those in {@code restored} have a value, the rest are still NULL. */
    private static ItemRegistry registryWith(Map<String, Double> restored) throws ItemNotFoundException {
        ItemRegistry reg = mock(ItemRegistry.class);
        when(reg.getItem(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            if (!ITEMS.contains(name)) {
                throw new ItemNotFoundException(name);
            }
            NumberItem item = new NumberItem(name);
            Double v = restored.get(name);
            if (v != null) {
                item.setState(new DecimalType(v));
            }
            return item;
        });
        return reg;
    }

    private static EventPublisher publisherInto(List<String> posted) {
        EventPublisher publisher = mock(EventPublisher.class);
        doAnswer(inv -> {
            posted.add(inv.getArgument(0).toString());
            return null;
        }).when(publisher).post(any(Event.class));
        return publisher;
    }

    private static Co2TrackingController controller(EventPublisher publisher, ItemRegistry registry) {
        return new Co2TrackingController(publisher, registry, GRID, OFFSET, null);
    }

    /** Build a context with the only fields this controller reads: tick, grid, solar. */
    private EnergyContext ctx(Instant t, double gridW, double solarW) {
        Map<String, CarSnapshot> noCars = Map.of();
        return new EnergyContext(t, gridW, 0, solarW, 0, 0, 50, 30, false, 0, EnergyContext.Mode.GRID_IMPORT, noCars, 0,
                0, 0, true, false, false, false, 0, 0, false, 0, 0, 60_000L, 0.30, new double[0], Double.NaN,
                Double.NaN, false);
    }

    /** Self-consumed solar (grid ≈ 0) must credit CO₂-saved at the grid factor, in kg. */
    @Test
    void selfConsumedSolarCreditsSavedAtGridFactor() {
        Co2TrackingController c = new Co2TrackingController(null, null, GRID, OFFSET, null);
        Instant t0 = Instant.parse("2026-06-03T12:00:00Z");
        c.evaluate(ctx(t0, 0.0, 10_000.0)); // first tick — establishes the clock, no accrual
        c.evaluate(ctx(t0.plusSeconds(36), 0.0, 10_000.0)); // +36 s (0.01 h) at 10 kW self-consumed
        // 10 kW × 0.01 h = 0.1 kWh; 0.1 kWh × 140 g/kWh = 14 g = 0.014 kg.
        assertEquals(0.014, c.todaySavedKg(), 1e-9, "self-consumed solar must credit saved at the grid factor");
        assertEquals(0.0, c.todayEmittedKg(), 1e-9, "no grid import → no emissions");
    }

    /** Export + self-consumption accrue independently (export at offset, self-use at grid). */
    @Test
    void exportAndSelfConsumptionBothCredited() {
        Co2TrackingController c = new Co2TrackingController(null, null, GRID, OFFSET, null);
        Instant t0 = Instant.parse("2026-06-03T12:00:00Z");
        c.evaluate(ctx(t0, 5_000.0, 10_000.0)); // first tick
        c.evaluate(ctx(t0.plusSeconds(36), 5_000.0, 10_000.0)); // +0.01 h: 5 kW export, 5 kW self-used
        // export: 5 kW × 0.01 h × 350 = 17.5 g; self-use: 5 kW × 0.01 h × 140 = 7 g → 24.5 g = 0.0245 kg.
        assertEquals(0.0245, c.todaySavedKg(), 1e-9, "export (offset) + self-consumption (grid) both credited");
    }

    /** Pure grid import emits, saves nothing. */
    @Test
    void gridImportEmitsAndSavesNothing() {
        Co2TrackingController c = new Co2TrackingController(null, null, GRID, OFFSET, null);
        Instant t0 = Instant.parse("2026-06-03T12:00:00Z");
        c.evaluate(ctx(t0, -2_000.0, 0.0)); // first tick
        c.evaluate(ctx(t0.plusSeconds(36), -2_000.0, 0.0)); // +0.01 h importing 2 kW, no solar
        // 2 kW × 0.01 h × 140 = 2.8 g = 0.0028 kg emitted; nothing saved.
        assertEquals(0.0028, c.todayEmittedKg(), 1e-9);
        assertEquals(0.0, c.todaySavedKg(), 1e-9);
    }

    @Test
    void theYearTotalIsNotOverwrittenByAnItemThatHasNotRestoredYet() throws Exception {
        Map<String, Double> all = new HashMap<>();
        all.put("EMS_CO2_Year_kg", 120.5);
        all.put("EMS_CO2_Today_kg", 0.4);
        Co2TrackingController before = controller(publisherInto(new ArrayList<>()), registryWith(all));
        before.evaluate(ctx(Instant.parse("2026-06-03T12:00:00Z"), -2_000.0, 0.0));
        before.saveSnapshot();

        // Restart the same day: every item exists but none has been restored yet.
        List<String> posted = new ArrayList<>();
        Co2TrackingController after = controller(publisherInto(posted), registryWith(new HashMap<>()));
        after.evaluate(ctx(Instant.parse("2026-06-03T12:05:00Z"), -2_000.0, 0.0));

        assertEquals(120.5, after.yearEmittedKg(), 1e-9, "a NULL item must not read as a zero year");
        assertTrue(posted.stream().anyMatch(e -> e.contains("EMS_CO2_Year_kg") && e.contains("120.5")),
                "the snapshot's year total is what gets published, not 0: " + posted);
    }

    @Test
    void aLowerItemReadingNeverPullsTheYearDown() throws Exception {
        Map<String, Double> high = new HashMap<>();
        high.put("EMS_CO2_Year_kg", 120.5);
        Co2TrackingController before = controller(publisherInto(new ArrayList<>()), registryWith(high));
        before.evaluate(ctx(Instant.parse("2026-06-03T12:00:00Z"), 0.0, 0.0));
        before.saveSnapshot();

        Map<String, Double> low = new HashMap<>();
        low.put("EMS_CO2_Year_kg", 0.0);
        Co2TrackingController after = controller(publisherInto(new ArrayList<>()), registryWith(low));
        after.evaluate(ctx(Instant.parse("2026-06-03T12:05:00Z"), 0.0, 0.0));

        assertEquals(120.5, after.yearEmittedKg(), 1e-9, "all-time counters only ever go up");
    }

    @Test
    void withNoSnapshotAndUnreadableItemsNothingIsPublished() throws Exception {
        List<String> posted = new ArrayList<>();
        Co2TrackingController c = controller(publisherInto(posted), registryWith(new HashMap<>()));

        c.evaluate(ctx(Instant.parse("2026-06-03T12:00:00Z"), -2_000.0, 0.0));

        assertFalse(c.isRestored(), "no real value is known yet");
        assertTrue(posted.isEmpty(), "publishing here is what wrote 0 over the year total: " + posted);
    }

    @Test
    void aSnapshotFromYesterdayStartsTodayAtZeroAndKeepsTheYear() throws Exception {
        Map<String, Double> m = new HashMap<>();
        m.put("EMS_CO2_Today_kg", 3.2);
        m.put("EMS_CO2_Year_kg", 100.0);
        Co2TrackingController before = controller(publisherInto(new ArrayList<>()), registryWith(m));
        before.evaluate(ctx(Instant.parse("2026-06-03T12:00:00Z"), 0.0, 0.0));
        before.saveSnapshot();

        // Restart after midnight: the items still hold yesterday's figures too.
        Co2TrackingController after = controller(publisherInto(new ArrayList<>()), registryWith(m));
        after.evaluate(ctx(Instant.parse("2026-06-04T12:00:00Z"), 0.0, 0.0));

        assertEquals(0.0, after.todayEmittedKg(), 1e-9, "yesterday's CO₂ must not become today's");
        assertEquals(100.0, after.yearEmittedKg(), 1e-9);
    }

    @Test
    void aSnapshotFromLastYearStartsTheYearAtZero() throws Exception {
        Map<String, Double> m = new HashMap<>();
        m.put("EMS_CO2_Year_kg", 100.0);
        Co2TrackingController before = controller(publisherInto(new ArrayList<>()), registryWith(m));
        before.evaluate(ctx(Instant.parse("2026-12-31T12:00:00Z"), 0.0, 0.0));
        before.saveSnapshot();

        Co2TrackingController after = controller(publisherInto(new ArrayList<>()), registryWith(m));
        after.evaluate(ctx(Instant.parse("2027-01-01T12:00:00Z"), 0.0, 0.0));

        assertEquals(0.0, after.yearEmittedKg(), 1e-9, "last year's total must not open the new year");
    }
}

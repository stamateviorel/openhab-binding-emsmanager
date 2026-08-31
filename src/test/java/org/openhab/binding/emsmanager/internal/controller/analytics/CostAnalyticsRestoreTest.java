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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.types.DecimalType;

/**
 * Reproduces the 2026-08-30 loss: an openHAB restart where item state had not finished restoring
 * seeded the all-time savings counter from an UNDEF item, read it as zero, and wrote that zero back
 * over 465 EUR of history. Cost and earnings, read microseconds earlier, survived - so the failure
 * is a race and any test for it has to model a partially restored registry, not an empty one.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class CostAnalyticsRestoreTest {

    private static Path userdata = Path.of("");

    @BeforeAll
    static void redirectCacheAwayFromTheLiveInstall() throws IOException {
        userdata = Files.createTempDirectory("ems-cost-test");
        Files.createDirectories(userdata.resolve("cache"));
        userdata.toFile().deleteOnExit();
        System.setProperty("openhab.userdata", userdata.toString());
    }

    @AfterAll
    static void restoreCacheLocation() {
        System.clearProperty("openhab.userdata");
    }

    @BeforeEach
    void clearSnapshot() throws IOException {
        Files.deleteIfExists(userdata.resolve("cache").resolve("emsmanager-cost-cache.json"));
    }

    /** A registry where some items have restored and the rest are still absent. */
    private static ItemRegistry registryWith(Map<String, Double> restored) throws ItemNotFoundException {
        ItemRegistry reg = mock(ItemRegistry.class);
        when(reg.getItem(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            Double v = restored.get(name);
            if (v == null) {
                throw new ItemNotFoundException(name);
            }
            Item item = new NumberItem(name);
            ((NumberItem) item).setState(new DecimalType(v));
            return item;
        });
        return reg;
    }

    private static CostAnalyticsController controller() {
        return new CostAnalyticsController(mock(EventPublisher.class), 0.05);
    }

    @Test
    void aCounterMissingFromTheRegistryIsNotSeededToZero() throws Exception {
        CostAnalyticsController saver = controller();
        Map<String, Double> all = new HashMap<>();
        all.put(ITEM_EMS_SELFCONSUMPTION_KWH_DAY, 10.0);
        all.put(ITEM_EMS_SAVINGS_EUR_TOTAL, 465.57);
        saver.initFromItems(registryWith(all));
        saver.saveSnapshot();

        // Restart: everything restored EXCEPT the savings total - exactly the 2026-08-30 shape.
        Map<String, Double> partial = new HashMap<>();
        partial.put(ITEM_EMS_SELFCONSUMPTION_KWH_DAY, 10.0);
        CostAnalyticsController after = controller();
        after.initFromItems(registryWith(partial));

        assertEquals(465.57, after.savingsEurTotal(), 1e-6,
                "an unreadable item must not be able to zero an all-time counter");
    }

    @Test
    void aLowerReadingNeverWinsOverTheSnapshot() throws Exception {
        CostAnalyticsController saver = controller();
        Map<String, Double> high = new HashMap<>();
        high.put(ITEM_EMS_SELFCONSUMPTION_KWH_DAY, 1.0);
        high.put(ITEM_EMS_SAVINGS_EUR_TOTAL, 465.57);
        saver.initFromItems(registryWith(high));
        saver.saveSnapshot();

        // A stale or half-written item state reading lower than the snapshot.
        Map<String, Double> low = new HashMap<>();
        low.put(ITEM_EMS_SELFCONSUMPTION_KWH_DAY, 1.0);
        low.put(ITEM_EMS_SAVINGS_EUR_TOTAL, 0.0);
        CostAnalyticsController after = controller();
        after.initFromItems(registryWith(low));

        assertEquals(465.57, after.savingsEurTotal(), 1e-6, "all-time counters only ever go up");
    }

    @Test
    void aHigherItemReadingDoesWin() throws Exception {
        CostAnalyticsController saver = controller();
        Map<String, Double> m = new HashMap<>();
        m.put(ITEM_EMS_SELFCONSUMPTION_KWH_DAY, 1.0);
        m.put(ITEM_EMS_SAVINGS_EUR_TOTAL, 100.0);
        saver.initFromItems(registryWith(m));
        saver.saveSnapshot();

        m.put(ITEM_EMS_SAVINGS_EUR_TOTAL, 500.0);
        CostAnalyticsController after = controller();
        after.initFromItems(registryWith(m));

        assertEquals(500.0, after.savingsEurTotal(), 1e-6,
                "a stale snapshot must not hold the counter back when the item knows better");
    }

    @Test
    void theSnapshotSurvivesAnEntirelyEmptyRegistry() throws Exception {
        CostAnalyticsController saver = controller();
        Map<String, Double> m = new HashMap<>();
        m.put(ITEM_EMS_SELFCONSUMPTION_KWH_DAY, 1.0);
        m.put(ITEM_EMS_SAVINGS_EUR_TOTAL, 465.57);
        m.put(ITEM_EMS_COST_EUR_TOTAL, 829.0);
        saver.initFromItems(registryWith(m));
        saver.saveSnapshot();

        CostAnalyticsController after = controller();
        after.initFromItems(registryWith(new HashMap<>()));

        assertEquals(465.57, after.savingsEurTotal(), 1e-6);
        assertEquals(829.0, after.costEurTotal(), 1e-6);
    }

    @Test
    void withNoSnapshotAndNoItemsRestoreIsDeferredRatherThanZeroed() throws Exception {
        CostAnalyticsController c = controller();
        c.initFromItems(registryWith(new HashMap<>()));

        assertFalse(c.isRestored(), "starting from nothing must stay deferred, not publish zeros as fact");
    }
}

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
package org.openhab.binding.emsmanager.internal.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.config.EmsBridgeConfig;

/**
 * These overrides exist because Thing configuration cannot hold them: this site defines its Things
 * in a .things file, and openHAB drops updateConfiguration on a file-provisioned Thing, so a
 * dashboard control routed that way reports success and changes nothing.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class EmsSettingsStoreTest {

    private static Path userdata = Path.of("");

    private static final @org.eclipse.jdt.annotation.Nullable String PREVIOUS_USERDATA = System
            .getProperty("openhab.userdata");

    @BeforeAll
    static void redirectCacheAwayFromTheLiveInstall() throws IOException {
        userdata = Files.createTempDirectory("ems-settings-test");
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
    void clearStore() throws IOException {
        Files.deleteIfExists(userdata.resolve("cache").resolve("emsmanager-settings.json"));
    }

    @Test
    void anUntouchedSettingStillFollowsTheThingFile() {
        EmsBridgeConfig cfg = new EmsBridgeConfig();
        cfg.gridSafetyMarginW = 500;

        new EmsSettingsStore().applyTo(cfg);

        assertEquals(500, cfg.gridSafetyMarginW, "editing the .things file must remain the way to set a default");
    }

    @Test
    void anOverrideWinsOverTheThingFile() {
        EmsSettingsStore store = new EmsSettingsStore();
        store.put("gridSafetyMarginW", 600, 500);

        EmsBridgeConfig cfg = new EmsBridgeConfig();
        cfg.gridSafetyMarginW = 500;
        store.applyTo(cfg);

        assertEquals(600, cfg.gridSafetyMarginW);
    }

    @Test
    void aChangeSurvivesARestart() {
        EmsSettingsStore first = new EmsSettingsStore();
        first.put("boilerDailyTargetKwh", 6.5, 4.0);
        first.put("boilerReadyByHour", 9, 7);
        first.put("shadowMode", true, false);

        EmsBridgeConfig cfg = new EmsBridgeConfig();
        cfg.boilerDailyTargetKwh = 4.0;
        cfg.boilerReadyByHour = 7;
        cfg.shadowMode = false;
        new EmsSettingsStore().applyTo(cfg);

        assertEquals(6.5, cfg.boilerDailyTargetKwh, 1e-9, "a setting made from the dashboard must outlive a restart");
        assertEquals(9, cfg.boilerReadyByHour);
        assertTrue(cfg.shadowMode, "the stop switch above all must not quietly reset itself");
    }

    @Test
    void halfKilowattHoursSurviveTheRoundTrip() {
        new EmsSettingsStore().put("boilerDailyTargetKwh", 4.5, 0.0);

        EmsBridgeConfig cfg = new EmsBridgeConfig();
        new EmsSettingsStore().applyTo(cfg);

        assertEquals(4.5, cfg.boilerDailyTargetKwh, 1e-9, "persisting through JSON must not round the slider step");
    }

    @Test
    void anUnreadableStoreLeavesTheThingConfigurationIntact() throws IOException {
        Files.writeString(userdata.resolve("cache").resolve("emsmanager-settings.json"), "{ this is not json");

        EmsBridgeConfig cfg = new EmsBridgeConfig();
        cfg.gridSafetyMarginW = 500;
        new EmsSettingsStore().applyTo(cfg);

        assertEquals(500, cfg.gridSafetyMarginW, "a corrupt override file must not take the EMS down with it");
    }

    @Test
    void editingTheThingFileRetiresTheOverride() {
        EmsSettingsStore store = new EmsSettingsStore();
        store.put("shadowMode", false, false);

        EmsBridgeConfig cfg = new EmsBridgeConfig();
        cfg.shadowMode = true; // the owner reached for the kill switch in the file
        store.applyTo(cfg);

        assertTrue(cfg.shadowMode, "a value set in the file after the override must win, or the file is dead");
        EmsBridgeConfig again = new EmsBridgeConfig();
        again.shadowMode = true;
        new EmsSettingsStore().applyTo(again);
        assertTrue(again.shadowMode, "and the retired override must stay retired");
    }

    @Test
    void anOverrideStandsWhileTheFileStillSaysWhatItSaid() {
        EmsSettingsStore store = new EmsSettingsStore();
        store.put("gridSafetyMarginW", 600, 500);

        EmsBridgeConfig cfg = new EmsBridgeConfig();
        cfg.gridSafetyMarginW = 500;
        new EmsSettingsStore().applyTo(cfg);

        assertEquals(600, cfg.gridSafetyMarginW);
    }

    @Test
    void aLegacyFlatStoreIsStillReadAndAdoptsTheFileAsItsBaseline() throws IOException {
        Files.writeString(userdata.resolve("cache").resolve("emsmanager-settings.json"),
                "{\"gridSafetyMarginW\":500.0}");

        EmsBridgeConfig cfg = new EmsBridgeConfig();
        cfg.gridSafetyMarginW = 300;
        new EmsSettingsStore().applyTo(cfg);
        assertEquals(500, cfg.gridSafetyMarginW, "the pre-existing override still applies once");

        EmsBridgeConfig edited = new EmsBridgeConfig();
        edited.gridSafetyMarginW = 800;
        new EmsSettingsStore().applyTo(edited);
        assertEquals(800, edited.gridSafetyMarginW, "but a later file edit retires it like any other");
    }
}

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
package org.openhab.binding.emsmanager.internal.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Every piece of state the binding cannot rebuild goes through here. Two things had already gone
 * wrong with these paths: a hardcoded /var/lib/openhab that is wrong on any non-Debian install and
 * let unit tests write into the running system, and a cacheDir() derived with getParent() that
 * silently dropped the cache segment and sent per-device state to the wrong directory.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class CachePathsTest {

    private static final @org.eclipse.jdt.annotation.Nullable String HARNESS_USERDATA = System
            .getProperty("openhab.userdata");

    @AfterEach
    void restoreHarnessValue() {
        String harness = HARNESS_USERDATA;
        if (harness == null) {
            System.clearProperty("openhab.userdata");
        } else {
            System.setProperty("openhab.userdata", harness);
        }
    }

    @Test
    void aCacheFileSitsInsideTheCacheDirectory() {
        System.setProperty("openhab.userdata", "/tmp/ems-test");

        assertEquals("/tmp/ems-test/cache/thing.json", CachePaths.cacheFile("thing.json").toString());
    }

    @Test
    void theCacheDirectoryKeepsItsCacheSegment() {
        System.setProperty("openhab.userdata", "/tmp/ems-test");

        assertEquals("/tmp/ems-test/cache", CachePaths.cacheDir().toString(),
                "deriving this with getParent() dropped the segment and pointed per-device state at userdata itself");
    }

    @Test
    void aCacheFileIsAlwaysAChildOfTheCacheDirectory() {
        System.setProperty("openhab.userdata", "/tmp/ems-test");

        assertEquals(CachePaths.cacheDir(), CachePaths.cacheFile("thing.json").getParent(),
                "the two accessors must not be able to disagree about where the cache is");
    }

    @Test
    void reportsAreASiblingOfTheCacheNotAChild() {
        System.setProperty("openhab.userdata", "/tmp/ems-test");

        assertEquals("/tmp/ems-test/reports", CachePaths.userDataDir("reports").toString());
    }

    @Test
    void theCacheFollowsWhateverUserdataIsInEffect() {
        // The openHAB build sets this property for the test JVM. Honouring it is what keeps a unit
        // test out of a running system - which a hardcoded path did not, leaving synthetic prices
        // in the live tariff cache on 2026-08-31.
        System.setProperty("openhab.userdata", "/tmp/ems-somewhere-else");

        assertTrue(CachePaths.cacheDir().startsWith("/tmp/ems-somewhere-else"),
                "code that ignores the property escapes the sandbox the build set up");
    }

    @Test
    void anAtomicWriteLeavesExactlyTheTargetFileWithTheNewContent() throws IOException {
        Path dir = Files.createTempDirectory("ems-atomic");
        Path target = dir.resolve("state.json");
        Files.writeString(target, "old");
        Object before = Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes.class).fileKey();

        CachePaths.writeAtomic(target, "new");

        assertEquals("new", Files.readString(target));
        Object after = Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        assertNotEquals(before, after,
                "an in-place write keeps the inode and can be caught truncated; a rename swaps the whole file");
        try (Stream<Path> listing = Files.list(dir)) {
            assertEquals(List.of(target), listing.toList(),
                    "the temporary file must be renamed onto the target, not left beside it");
        }
    }

    @Test
    void anAtomicWriteCreatesTheDirectoryItNeeds() throws IOException {
        Path dir = Files.createTempDirectory("ems-atomic").resolve("cache");
        Path target = dir.resolve("state.json");

        CachePaths.writeAtomic(target, "{}");

        assertEquals("{}", Files.readString(target));
    }

    @Test
    void withNoPropertySetItFallsBackToTheStandardInstallLocation() {
        String saved = System.getProperty("openhab.userdata");
        System.clearProperty("openhab.userdata");
        try {
            assertEquals("/var/lib/openhab/cache", CachePaths.cacheDir().toString(),
                    "a real install sets no property and must keep working exactly as before");
        } finally {
            String restore = saved;
            if (restore != null) {
                System.setProperty("openhab.userdata", restore);
            }
        }
    }
}

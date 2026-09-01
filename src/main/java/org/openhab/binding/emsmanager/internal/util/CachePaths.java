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

import java.nio.file.Path;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Where this binding keeps the state it cannot afford to rebuild from item history.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class CachePaths {

    private CachePaths() {
    }

    /**
     * Resolved per call rather than once, so a test can redirect it and so the location is not
     * pinned to a Debian-style install.
     */
    public static Path cacheFile(String fileName) {
        return cacheDir().resolve(fileName);
    }

    /** The directory itself, for state that is one file per device rather than one file. */
    public static Path cacheDir() {
        return Path.of(System.getProperty("openhab.userdata", "/var/lib/openhab"), "cache");
    }

    /** A sibling of the cache directory, such as where reports are written. */
    public static Path userDataDir(String name) {
        return Path.of(System.getProperty("openhab.userdata", "/var/lib/openhab"), name);
    }
}

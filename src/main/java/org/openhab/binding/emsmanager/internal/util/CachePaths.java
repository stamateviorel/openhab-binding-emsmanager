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

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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

    /**
     * Replaces {@code target} with {@code content} so that a reader only ever sees the old file or
     * the new one, never a truncated one. The temporary file has to live in the target's own
     * directory: a rename is only atomic within one filesystem.
     */
    public static void writeAtomic(Path target, String content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir == null) {
            throw new IOException("no parent directory for " + target);
        }
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, content);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}

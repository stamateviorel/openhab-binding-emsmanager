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
package org.openhab.binding.emsmanager.internal.core;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Snapshot/restore primitive for temporary-override controllers (peak
 * shaving, anti-curtailment). Captures the pre-override value of each
 * affected item; on release, restores them in reverse order.
 *
 * <p>
 * Held in memory for the lifetime of an active shed episode. JSONDB-backed
 * persistence could be added so a bridge restart does not leave shed assets
 * stuck off.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class Snapshot {

    private final Map<String, String> captured = new LinkedHashMap<>();

    /** Capture the current value before changing it. No-op if already captured. */
    public void capture(String key, String preValue) {
        captured.putIfAbsent(key, preValue);
    }

    public String captured(String key) {
        String v = captured.get(key);
        return v == null ? "" : v;
    }

    public boolean has(String key) {
        return captured.containsKey(key);
    }

    public Map<String, String> entries() {
        return Map.copyOf(captured);
    }

    public int size() {
        return captured.size();
    }

    public void clear() {
        captured.clear();
    }

    public void remove(String key) {
        captured.remove(key);
    }
}

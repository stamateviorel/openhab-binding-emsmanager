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

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Tracks last-sent values per item with an ACK window, so the same command
 * is not re-sent every tick.
 *
 * <p>
 * Necessary because OCPP control items typically use {@code autoupdate="false"}:
 * their state lags {@code sendCommand} until the binding ACKs the
 * SetChargingProfile. Comparing against item state alone causes the same
 * value to be re-sent every tick.
 *
 * <p>
 * Within the ACK window we trust our own outgoing send; after the window,
 * any state divergence is treated as real (e.g. a charger silently resetting
 * its charging current after a SuspendedEVSE cycle) and re-sent.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class SetpointDedupe {

    public static final long DEFAULT_ACK_WINDOW_MS = 15_000L;

    private final long ackWindowMs;
    private final Map<String, String> lastSent = new HashMap<>();
    private final Map<String, Long> lastSentTs = new HashMap<>();

    public SetpointDedupe() {
        this(DEFAULT_ACK_WINDOW_MS);
    }

    public SetpointDedupe(long ackWindowMs) {
        this.ackWindowMs = Math.max(0L, ackWindowMs);
    }

    /**
     * Decide whether a fresh sendCommand should be issued.
     *
     * @param key item name (or any identifier)
     * @param desiredValue value we want to send
     * @param currentState item's current state as the runtime sees it (may
     *            lag for autoupdate=false items)
     * @param nowMs current epoch millis
     * @return true if caller should send; false if dedup says "skip"
     */
    public boolean shouldSend(String key, String desiredValue, String currentState, long nowMs) {
        boolean sameAsState;
        try {
            double dv = Double.parseDouble(desiredValue);
            double cs = Double.parseDouble(currentState);
            sameAsState = (dv == cs);
        } catch (NumberFormatException e) {
            sameAsState = desiredValue.equals(currentState);
        }
        if (sameAsState) {
            lastSent.put(key, desiredValue);
            lastSentTs.put(key, nowMs);
            return false;
        }
        String prev = lastSent.get(key);
        Long prevTs = lastSentTs.get(key);
        if (prev != null && prev.equals(desiredValue) && prevTs != null && (nowMs - prevTs) < ackWindowMs) {
            // Sent the same value very recently — assume binding hasn't ACK'd yet.
            return false;
        }
        // Either new, or ACK window expired and state still diverged → re-send.
        return true;
    }

    /** Record that a send went out. Call after the caller actually sent. */
    public void markSent(String key, String value, long nowMs) {
        lastSent.put(key, value);
        lastSentTs.put(key, nowMs);
    }

    /** Forget a key — used during peak-shaving release etc. */
    public void forget(String key) {
        lastSent.remove(key);
        lastSentTs.remove(key);
    }

    public int size() {
        return lastSent.size();
    }
}

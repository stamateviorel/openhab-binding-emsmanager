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
package org.openhab.binding.emsmanager.internal.asset;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The minimum time a switched load stays in a state before it may be switched again.
 * <p>
 * Controllers argue in software; a contactor, a heating element or a compressor pays for it in
 * hardware. Whatever the controllers decide, a load that went ON stays ON for at least this long
 * and one that went OFF stays OFF for at least this long. The clock restarts on any change,
 * including one a person made by hand.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class OnOffDwell {

    private final long minOnMs;
    private final long minOffMs;
    private String lastSeen = "";
    private long lastChangeMs = 0L;
    /** False until a change has actually been seen or made; before that no hold is known. */
    private boolean holdKnown = false;

    public OnOffDwell(long minOnMs, long minOffMs) {
        this.minOnMs = minOnMs;
        this.minOffMs = minOffMs;
    }

    /** Note the state the load is in now, so a change made elsewhere restarts the clock too. */
    public void observe(String current, long nowMs) {
        if (lastSeen.isEmpty()) {
            lastSeen = current;
            return;
        }
        if (!current.equals(lastSeen)) {
            lastSeen = current;
            lastChangeMs = nowMs;
            holdKnown = true;
        }
    }

    /**
     * Whether the load, currently in {@code current}, may be switched now.
     * <p>
     * The first observation after start is allowed: nothing is known about how long the load has
     * been where it is, and refusing would leave a stuck load stuck.
     */
    public boolean mayLeave(String current, long nowMs) {
        observe(current, nowMs);
        long minimum = "ON".equals(current) ? minOnMs : minOffMs;
        return !holdKnown || (nowMs - lastChangeMs) >= minimum;
    }

    /** How much longer the load has to stay where it is, in seconds; 0 when it may switch. */
    public long remainingSeconds(String current, long nowMs) {
        observe(current, nowMs);
        long minimum = "ON".equals(current) ? minOnMs : minOffMs;
        return !holdKnown ? 0L : Math.max(0L, (minimum - (nowMs - lastChangeMs)) / 1000L);
    }

    /** We switched it ourselves: the new state starts its hold now. */
    public void switched(String to, long nowMs) {
        lastSeen = to;
        lastChangeMs = nowMs;
        holdKnown = true;
    }
}

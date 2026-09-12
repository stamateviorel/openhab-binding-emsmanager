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
 * Why a dispatched request did or did not reach the hardware.
 *
 * <p>
 * A boolean could only say "a command went out". Everything interesting about this system is in the
 * other four answers: an asset already where it was asked to be is normal and silent, while a guard
 * that keeps refusing is the thing worth reading in the morning.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum AssetWriteOutcome {

    /** A command was published to the item. */
    WROTE,

    /** The asset is already there, or the ACK window has not closed. Not worth recording. */
    UNCHANGED,

    /** A change was wanted and a minimum-dwell or rate guard blocked it for now. */
    HELD,

    /** The request will never be honoured as things stand: wrong kind, no item, readonly mode, NaN. */
    REFUSED,

    /** Shadow mode. The write was fully decided and deliberately not sent. */
    SHADOWED,

    /** The handler threw. Set by the dispatcher, never returned by a handler. */
    FAILED;

    /** True where the outcome is a change in the world, or a refusal to make one — what a journal keeps. */
    public boolean worthRecording() {
        return this != UNCHANGED;
    }
}

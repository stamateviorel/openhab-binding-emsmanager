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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * A controller computes setpoint requests from an immutable per-tick
 * {@link EnergyContext} snapshot. Controllers must be pure functions of
 * their inputs (no I/O, no item access, no side effects) — that makes
 * them trivially unit-testable with synthetic contexts.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface Controller {

    /** Stable identifier used for logging + lastAction. */
    String name();

    /** Priority — lower runs first, higher wins on conflict. */
    int priority();

    /** Whether this controller is currently enabled. */
    boolean enabled();

    /** Whether this controller is in shadow mode (computes but doesn't act). */
    boolean shadowMode();

    /**
     * Evaluate this controller for the given context. Returns 0 or more
     * setpoint requests. Must NOT block or do I/O.
     */
    List<SetpointRequest> evaluate(EnergyContext ctx);
}

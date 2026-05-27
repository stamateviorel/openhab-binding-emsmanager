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
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * Base contract for controllable assets (boiler, aircon, charger, battery).
 * Concrete handlers own the dedupe/ACK window + the per-asset capability
 * check; controllers never touch raw items.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface AssetHandler {

    /** Stable identifier matching {@link SetpointRequest#assetId()}. */
    String assetId();

    /**
     * Apply a setpoint request. Returns true iff a real write was issued;
     * false if dedup skipped, capability check refused, or {@code shadow}
     * is on. Implementations MUST honour {@code shadow} = no real writes.
     *
     * @param req current request
     * @param ctx tick context (for capability checks)
     * @param shadow when true, the asset handler logs only — no writes
     */
    boolean apply(SetpointRequest req, EnergyContext ctx, boolean shadow);
}

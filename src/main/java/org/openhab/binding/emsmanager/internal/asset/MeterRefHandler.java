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
 * Read-only adapter wrapping a power-measuring item (e.g. the grid, solar,
 * battery, or a per-EVSE power item) as a typed read source.
 *
 * <p>
 * Implements AssetHandler.apply as a no-op since meters aren't controllable;
 * they are read-only inputs to the EnergyContext builder.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class MeterRefHandler implements AssetHandler {

    private final String assetId;
    private final String itemName;

    public MeterRefHandler(String assetId, String itemName) {
        this.assetId = assetId;
        this.itemName = itemName;
    }

    @Override
    public String assetId() {
        return assetId;
    }

    public String itemName() {
        return itemName;
    }

    @Override
    public boolean apply(SetpointRequest req, EnergyContext ctx, boolean shadow) {
        // Meters are not controllable. Any request hitting a meter-ref is
        // a programming error; phase 1+ should reject in the scheduler.
        return false;
    }
}

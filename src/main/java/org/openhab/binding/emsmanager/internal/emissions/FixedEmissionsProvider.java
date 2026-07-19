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
package org.openhab.binding.emsmanager.internal.emissions;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Static-constants emissions provider. Default for sites without an
 * ElectricityMaps API key.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class FixedEmissionsProvider implements EmissionsTracker {

    private final double gridGramsPerKWh;
    private final double injectionOffsetGramsPerKWh;

    public FixedEmissionsProvider(double gridGramsPerKWh, double injectionOffsetGramsPerKWh) {
        this.gridGramsPerKWh = gridGramsPerKWh;
        this.injectionOffsetGramsPerKWh = injectionOffsetGramsPerKWh;
    }

    @Override
    public double currentGridGramsPerKWh() {
        return gridGramsPerKWh;
    }

    @Override
    public double currentInjectionOffsetGramsPerKWh() {
        return injectionOffsetGramsPerKWh;
    }

    @Override
    public String name() {
        return "fixed";
    }
}

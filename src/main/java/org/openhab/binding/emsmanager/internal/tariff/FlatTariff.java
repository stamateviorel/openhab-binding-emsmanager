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
package org.openhab.binding.emsmanager.internal.tariff;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Single fixed €/kWh price 24/7. The default tariff kind.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class FlatTariff implements TariffProvider {

    public static final String KIND = "flat";

    private final double price;

    public FlatTariff(double price) {
        this.price = price;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public TariffSnapshot snapshot(Instant now) {
        return TariffMath.buildSnapshotStatic(now, h -> price);
    }
}

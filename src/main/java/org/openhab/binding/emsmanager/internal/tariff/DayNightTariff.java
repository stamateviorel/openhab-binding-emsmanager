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
 * Two-rate schedule: dayPrice during [dayStartHour, dayEndHour), nightPrice
 * everywhere else. Hours wrapping midnight are supported.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class DayNightTariff implements TariffProvider {

    public static final String KIND = "day-night";

    private final double dayPrice;
    private final double nightPrice;
    private final int dayStartHour;
    private final int dayEndHour;

    public DayNightTariff(double dayPrice, double nightPrice, int dayStartHour, int dayEndHour) {
        this.dayPrice = dayPrice;
        this.nightPrice = nightPrice;
        this.dayStartHour = ((dayStartHour % 24) + 24) % 24;
        this.dayEndHour = ((dayEndHour % 24) + 24) % 24;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public TariffSnapshot snapshot(Instant now) {
        return TariffMath.buildSnapshotStatic(now,
                h -> TariffMath.inDayWindow(h, dayStartHour, dayEndHour) ? dayPrice : nightPrice);
    }
}

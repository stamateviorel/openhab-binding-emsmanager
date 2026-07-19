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
 * Pluggable energy-tariff provider. Implementations include Flat, DayNight,
 * TouSchedule, and DynamicSpot (e.g. ENTSO-E).
 *
 * <p>
 * The formula-based implementations are pure functions of (now, config) — no
 * I/O. Dynamic providers do HTTP polling like the forecast provider.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface TariffProvider {

    /** Stable identifier matching the Thing config's {@code kind}. */
    String kind();

    /** Compute a 24h+48h snapshot anchored at {@code now}. */
    TariffSnapshot snapshot(Instant now);
}

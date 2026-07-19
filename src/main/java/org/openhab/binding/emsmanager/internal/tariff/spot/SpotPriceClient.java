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
package org.openhab.binding.emsmanager.internal.tariff.spot;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Pluggable client for dynamic-spot tariff data. One implementation per
 * upstream service (ENTSO-E, Tibber, Engie-CSV, generic URL, …).
 *
 * <p>
 * Implementations MUST be defensive: any I/O or parse failure should
 * return {@link HourlyPrices#empty} with an error message. They MUST NOT
 * throw.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface SpotPriceClient {

    /** Stable identifier matching {@code subProvider} in Thing config. */
    String subProvider();

    /** Fetch today + tomorrow hourly prices. May be cached internally. */
    HourlyPrices fetch();
}

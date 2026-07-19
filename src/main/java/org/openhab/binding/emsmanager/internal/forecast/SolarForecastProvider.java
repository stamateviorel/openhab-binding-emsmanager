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
package org.openhab.binding.emsmanager.internal.forecast;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Pluggable solar-forecast provider. Implementations: forecast.solar
 * free/paid tier, Solcast, OpenWeatherMap cloudiness (fallback), manual.
 *
 * <p>
 * Implementations MUST be defensive: any I/O or parse failure should
 * return {@link ForecastSnapshot#EMPTY} (or the last known good snapshot
 * with {@code lastError} populated). They MUST NOT throw.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface SolarForecastProvider {

    /** Stable identifier matching the Thing config's {@code kind} value. */
    String kind();

    /**
     * Fetch a fresh forecast snapshot. May be called on the openHAB
     * scheduler thread; should respect upstream rate limits and complete
     * in &lt; 10 s typical.
     */
    ForecastSnapshot fetch();
}

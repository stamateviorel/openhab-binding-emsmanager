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
package org.openhab.binding.emsmanager.internal.config;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Thing configuration for {@code emsmanager:forecast-solar}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ForecastSolarConfig {

    public String kind = "forecast-solar-free";
    public @Nullable String apiBaseUrl = "https://api.forecast.solar";
    public @Nullable String apiKey;
    public double lat = Double.NaN;
    public double lon = Double.NaN;
    public double declination = 35.0; // typical Flemish roof pitch
    public double azimuth = 0.0; // 0 = south
    public double kwp = 25.0; // matches the CLAUDE.md site
    public int refreshIntervalMin = 30;
    // Open-Meteo only: GTI->AC derate (inverter, temperature, soiling, wiring).
    public double performanceRatio = 0.85;
}

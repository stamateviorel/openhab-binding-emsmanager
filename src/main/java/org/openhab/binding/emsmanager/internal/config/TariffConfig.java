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

/**
 * Thing configuration for {@code emsmanager:tariff}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class TariffConfig {

    public String kind = "flat";

    /** flat. */
    public double flatPriceEurPerKWh = 0.30;

    /** day-night. */
    public double dayPriceEurPerKWh = 0.32;
    public double nightPriceEurPerKWh = 0.18;
    public int dayStartHour = 7;
    public int dayEndHour = 22;

    /** tou-schedule — CSV of 24 prices for hours 0..23. */
    public String hourlyPricesCsv = "0.18,0.18,0.18,0.18,0.18,0.18,0.18,0.32,0.32,0.32,0.32,0.32,0.32,0.32,0.32,0.32,0.32,0.32,0.32,0.32,0.32,0.32,0.18,0.18";

    /** dynamic-spot — picks the spot-price client by subProvider. */
    public String subProvider = "entsoe-be"; // entsoe-be | tibber | csv-upload
    public String apiKey = ""; // ENTSO-E or Tibber token
    public String csvUrl = ""; // csv-upload only — URL or local file path
    /**
     * Added on top of raw spot. Belgian residential typical 0.12 €/kWh. Tibber already
     * bills end-customer-final so set 0 for Tibber.
     */
    public double markupEurPerKWh = 0.12;
    /**
     * How often to re-fetch from upstream (minutes). Day-ahead doesn't change intraday,
     * so 60 minutes is plenty. Lower = more API traffic.
     */
    public int refreshIntervalMin = 60;
}

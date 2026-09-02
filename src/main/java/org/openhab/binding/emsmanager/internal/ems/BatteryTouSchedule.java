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
package org.openhab.binding.emsmanager.internal.ems;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Time-of-use battery schedule shared by the legacy dispatcher and the engine: grid-charge in the
 * night band, discharge in the evening peak, neutral otherwise. Night charging is skipped when the
 * battery is already near full or tomorrow's PV forecast will fill it anyway.
 *
 * <p>
 * Stateful: it remembers the last setpoint it handed out so that leaving a window (or the SoC gate
 * tripping mid-window) yields one explicit 0 W instead of silence that leaves the inverter on the
 * last command. That memory starts empty, so a bridge re-init after a window has ended does not
 * emit the release. The night decision is logged once per night, not per tick.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class BatteryTouSchedule {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatteryTouSchedule.class);

    public static final int NIGHT_CHARGE_START_HOUR = 2;
    public static final int NIGHT_CHARGE_END_HOUR = 6; // exclusive
    public static final int EVENING_DISCHARGE_START_HOUR = 17;
    public static final int EVENING_DISCHARGE_END_HOUR = 21; // exclusive
    public static final double CHARGE_RATE_W = -2000.0;
    public static final double DISCHARGE_RATE_W = 2000.0;

    /** Above this SoC a night grid charge buys nothing. */
    public static final double NIGHT_CHARGE_FULL_SOC_PCT = 90.0;
    /** A forecast this sunny fills the battery from the roof (25 kWp) by itself. */
    public static final double SUNNY_TOMORROW_SKIP_CHARGE_KWH = 20.0;

    private @Nullable Double lastW = null;
    private String lastReason = "";
    // Once the SoC gate has tripped it stays tripped for the rest of that night, so the battery does
    // not top up, feed the house back down to 89 %, and top up again all night.
    private @Nullable LocalDate nightSkipLatchedOn = null;
    private @Nullable LocalDate nightLoggedOn = null;
    private String nightLoggedKey = "";

    /** Reason for the last non-null result of {@link #setpointW}. */
    public String lastReason() {
        return lastReason;
    }

    /**
     * The setpoint to emit this tick, or null for nothing. + = discharge, − = charge, 0 = neutral
     * (emitted once when leaving a window).
     */
    public @Nullable Double setpointW(ZonedDateTime now, boolean batteryBelowReserve, double batterySoC,
            double forecastTomorrowKwh) {
        int hour = now.getHour();
        LocalDate date = now.toLocalDate();
        boolean night = hour >= NIGHT_CHARGE_START_HOUR && hour < NIGHT_CHARGE_END_HOUR;
        double soc = night && date.equals(nightSkipLatchedOn) ? 100.0 : batterySoC;
        Double want = EnergyManagementService.batteryTouSetpointW(hour, batteryBelowReserve, soc, forecastTomorrowKwh,
                NIGHT_CHARGE_START_HOUR, NIGHT_CHARGE_END_HOUR, EVENING_DISCHARGE_START_HOUR,
                EVENING_DISCHARGE_END_HOUR, CHARGE_RATE_W, DISCHARGE_RATE_W, NIGHT_CHARGE_FULL_SOC_PCT,
                SUNNY_TOMORROW_SKIP_CHARGE_KWH);
        if (night) {
            if (want == null && !Double.isNaN(batterySoC) && batterySoC >= NIGHT_CHARGE_FULL_SOC_PCT) {
                nightSkipLatchedOn = date;
            }
            logNightOnce(date, want != null, batterySoC, forecastTomorrowKwh);
        }
        if (want != null) {
            lastW = want;
            lastReason = want < 0 ? "night charge window " + hour + ":00 → charge"
                    : "evening peak window " + hour + ":00 → discharge";
            return want;
        }
        Double prev = lastW;
        if (prev != null && prev != 0.0) {
            lastW = 0.0;
            lastReason = "leaving ToU window → neutral 0 W";
            return 0.0;
        }
        return null;
    }

    private void logNightOnce(LocalDate date, boolean charging, double soc, double forecastKwh) {
        String key = charging ? "charge" : (date.equals(nightSkipLatchedOn) ? "skip-soc" : "skip-forecast");
        if (date.equals(nightLoggedOn) && key.equals(nightLoggedKey)) {
            return;
        }
        nightLoggedOn = date;
        nightLoggedKey = key;
        LOGGER.debug("Battery night charge {}: SoC {}, tomorrow {} (skip at ≥ {} % or ≥ {} kWh)",
                charging ? "ON" : "skipped", Double.isNaN(soc) ? "?" : String.format(Locale.ROOT, "%.0f %%", soc),
                Double.isNaN(forecastKwh) ? "unknown" : String.format(Locale.ROOT, "%.1f kWh", forecastKwh),
                (int) NIGHT_CHARGE_FULL_SOC_PCT, (int) SUNNY_TOMORROW_SKIP_CHARGE_KWH);
    }
}

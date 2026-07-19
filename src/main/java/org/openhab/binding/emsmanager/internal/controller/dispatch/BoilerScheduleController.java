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
package org.openhab.binding.emsmanager.internal.controller.dispatch;

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * Boiler force-on schedule controller.
 *
 * <p>
 * Inside one of the configured day-of-week windows, the boiler is forced ON
 * regardless of solar / tariff. This is for sites with a hot-water-tank cycle
 * requirement (legionella, scheduled use).
 *
 * <p>
 * Schedule syntax (CSV): {@code MON:07:00-09:00,TUE:07:00-09:00}.
 * Days: MON TUE WED THU FRI SAT SUN. Windows may wrap midnight only by
 * splitting into two entries.
 *
 * <p>
 * Priority {@link EmsManagerBindingConstants#PRIO_SOLAR_SURPLUS} − 5 so
 * it outranks the surplus dispatcher (forces ON when surplus would have
 * decided OFF). It does NOT outrank PeakShaving — when hard-peak is engaged
 * tier ≥ 3 (boiler off), the boiler asset handler's dedupe will still
 * write OFF after the schedule writes ON, because the asset handler is
 * dumb and processes requests in priority order: later-priority requests
 * (higher number) overwrite earlier ones at apply-time.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class BoilerScheduleController implements Controller {

    public static final String NAME = "boiler-schedule";

    private final Map<DayOfWeek, List<TimeWindow>> windows;
    private final String rawSchedule;

    private record TimeWindow(LocalTime start, LocalTime end) {
    }

    public BoilerScheduleController(String scheduleCsv) {
        this.rawSchedule = scheduleCsv == null ? "" : scheduleCsv.trim();
        this.windows = parse(this.rawSchedule);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_SOLAR_SURPLUS - 5; // 65 — outranks SolarSurplus@70
    }

    @Override
    public boolean enabled() {
        return !windows.isEmpty();
    }

    @Override
    public boolean shadowMode() {
        return false;
    }

    @Override
    public List<SetpointRequest> evaluate(EnergyContext ctx) {
        if (windows.isEmpty()) {
            return List.of();
        }
        ZonedDateTime now = ctx.tickAt().atZone(ZoneId.systemDefault());
        DayOfWeek day = now.getDayOfWeek();
        LocalTime time = now.toLocalTime();
        List<TimeWindow> todays = windows.get(day);
        if (todays == null || todays.isEmpty()) {
            return List.of();
        }
        for (TimeWindow w : todays) {
            if (!time.isBefore(w.start()) && time.isBefore(w.end())) {
                return List.of(new SetpointRequest(ASSET_BOILER, SetpointRequest.Kind.ONOFF, 1.0, priority(), NAME,
                        "🕒 In geplande boiler-aan window " + day + " " + w.start() + "-" + w.end()));
            }
        }
        return List.of();
    }

    public String rawSchedule() {
        return rawSchedule;
    }

    private static Map<DayOfWeek, List<TimeWindow>> parse(String csv) {
        Map<DayOfWeek, List<TimeWindow>> out = new EnumMap<>(DayOfWeek.class);
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String entry : csv.split(",")) {
            entry = entry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            try {
                int sep = entry.indexOf(':');
                if (sep < 0) {
                    continue;
                }
                String dayStr = entry.substring(0, sep).trim().toUpperCase();
                String range = entry.substring(sep + 1).trim();
                DayOfWeek day = switch (dayStr) {
                    case "MON" -> DayOfWeek.MONDAY;
                    case "TUE" -> DayOfWeek.TUESDAY;
                    case "WED" -> DayOfWeek.WEDNESDAY;
                    case "THU" -> DayOfWeek.THURSDAY;
                    case "FRI" -> DayOfWeek.FRIDAY;
                    case "SAT" -> DayOfWeek.SATURDAY;
                    case "SUN" -> DayOfWeek.SUNDAY;
                    default -> null;
                };
                if (day == null) {
                    continue;
                }
                int dash = range.indexOf('-');
                if (dash < 0) {
                    continue;
                }
                LocalTime start = LocalTime.parse(range.substring(0, dash).trim());
                LocalTime end = LocalTime.parse(range.substring(dash + 1).trim());
                out.computeIfAbsent(day, k -> new ArrayList<>()).add(new TimeWindow(start, end));
            } catch (Throwable t) {
                // Skip malformed entries silently — the schedule is best-effort.
            }
        }
        return out;
    }
}

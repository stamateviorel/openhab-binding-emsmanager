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
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.controller.peak.HardPeakShavingController;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * decided OFF). It does NOT outrank hard peak shaving: while any tier is
 * engaged the schedule stays silent. The shaving controller writes its
 * boiler-off once, at engage, and the resolver only arbitrates requests
 * made in the same tick — a per-tick ON from here would simply undo it.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class BoilerScheduleController implements Controller {

    private static final Logger LOGGER = LoggerFactory.getLogger(BoilerScheduleController.class);

    public static final String NAME = "boiler-schedule";

    private final Map<DayOfWeek, List<TimeWindow>> windows;
    private final String rawSchedule;
    private final @Nullable HardPeakShavingController hard;

    private record TimeWindow(LocalTime start, LocalTime end) {
    }

    public BoilerScheduleController(String scheduleCsv) {
        this(scheduleCsv, null);
    }

    public BoilerScheduleController(String scheduleCsv, @Nullable HardPeakShavingController hard) {
        this.rawSchedule = scheduleCsv == null ? "" : scheduleCsv.trim();
        this.windows = parse(this.rawSchedule);
        this.hard = hard;
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
        HardPeakShavingController shaving = hard;
        if (shaving != null && shaving.level() > 0) {
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

    /** Visible for testing: schedules are hand-typed, so the forgiving path needs covering. */
    static Map<DayOfWeek, List<TimeWindow>> parseForTest(String csv) {
        return parse(csv);
    }

    private static Map<DayOfWeek, List<TimeWindow>> parse(String csv) {
        int malformed = 0;
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
                // Best-effort, but a typo that quietly drops half a schedule is worth one line.
                malformed++;
            }
        }
        if (malformed > 0) {
            LOGGER.warn("Boiler schedule: {} entr{} could not be read and {} ignored", malformed,
                    malformed == 1 ? "y" : "ies", malformed == 1 ? "was" : "were");
        }
        return out;
    }
}

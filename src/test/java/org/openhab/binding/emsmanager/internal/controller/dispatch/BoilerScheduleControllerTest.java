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

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * Tests for {@link BoilerScheduleController} — schedule parsing + window matching.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class BoilerScheduleControllerTest {

    @Test
    void emptyScheduleIsDisabled() {
        BoilerScheduleController c = new BoilerScheduleController("");
        assertFalse(c.enabled());
        assertEquals(0, c.evaluate(syntheticCtx("2026-05-26T07:30:00")).size());
    }

    @Test
    void firesInsideWindow() {
        // Tuesday 07:30 falls inside TUE:07:00-09:00
        BoilerScheduleController c = new BoilerScheduleController("TUE:07:00-09:00,THU:07:00-09:00");
        assertTrue(c.enabled());
        var out = c.evaluate(syntheticCtx("2026-05-26T07:30:00")); // 2026-05-26 = Tuesday
        assertEquals(1, out.size());
        SetpointRequest req = out.get(0);
        assertEquals("boiler", req.assetId());
        assertEquals(SetpointRequest.Kind.ONOFF, req.kind());
        assertEquals(1.0, req.value());
    }

    @Test
    void quietOutsideWindow() {
        // Tuesday 10:30 is past the 09:00 window end
        BoilerScheduleController c = new BoilerScheduleController("TUE:07:00-09:00");
        assertEquals(0, c.evaluate(syntheticCtx("2026-05-26T10:30:00")).size());
    }

    @Test
    void quietOnNonScheduledDay() {
        // Wednesday is not in the schedule
        BoilerScheduleController c = new BoilerScheduleController("TUE:07:00-09:00,THU:07:00-09:00");
        assertEquals(0, c.evaluate(syntheticCtx("2026-05-27T07:30:00")).size()); // Wed
    }

    @Test
    void ignoresMalformedEntries() {
        BoilerScheduleController c = new BoilerScheduleController("WAT,TUE,TUE:07-09,TUE:07:00-09:00");
        // Only the last valid entry should register; Tuesday 07:30 should still match
        var out = c.evaluate(syntheticCtx("2026-05-26T07:30:00"));
        assertEquals(1, out.size());
    }

    @Test
    void inclusiveStartExclusiveEnd() {
        BoilerScheduleController c = new BoilerScheduleController("TUE:07:00-09:00");
        assertEquals(1, c.evaluate(syntheticCtx("2026-05-26T07:00:00")).size(), "start inclusive");
        assertEquals(0, c.evaluate(syntheticCtx("2026-05-26T09:00:00")).size(), "end exclusive");
    }

    private static EnergyContext syntheticCtx(String localIsoDateTime) {
        Instant at = LocalDateTime.parse(localIsoDateTime).atZone(ZoneId.systemDefault()).toInstant();
        return new EnergyContext(at, 0, 0, 0, 0, 0, 0, 0, false, 0, EnergyContext.Mode.UNKNOWN, Map.of(), 0, 0, 0, true,
                false, false, true, 0, Double.NaN, false, 0, 0, 0, Double.NaN, new double[0], Double.NaN, Double.NaN,
                false);
    }
}

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
package org.openhab.binding.emsmanager.internal.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * The month-to-date peak must come back after a restart, and only for its own month.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class CapacityTariffTrackerRestoreTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Brussels");
    private static final long NOW = ZonedDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZONE).toInstant().toEpochMilli();

    @Test
    void thePeakOfTheCurrentMonthComesBack() {
        CapacityTariffTracker t = new CapacityTariffTracker(ZONE);
        t.restore(new CapacityTariffTracker.Persisted(2026, 9, -3200.0), NOW);

        assertEquals(-3200.0, t.monthlyPeakW(), 1e-9);
        t.sample(-500.0, NOW);
        assertEquals(-3200.0, t.monthlyPeakW(), 1e-9, "a fresh sample in the same month must not reset it");
    }

    @Test
    void lastMonthsPeakIsNotThisMonthsBill() {
        CapacityTariffTracker t = new CapacityTariffTracker(ZONE);
        t.restore(new CapacityTariffTracker.Persisted(2026, 8, -6000.0), NOW);

        assertEquals(0.0, t.monthlyPeakW(), 1e-9);
    }

    @Test
    void nothingStoredChangesNothing() {
        CapacityTariffTracker t = new CapacityTariffTracker(ZONE);
        t.restore(null, NOW);
        assertEquals(0.0, t.monthlyPeakW(), 1e-9);
    }

    @Test
    void whatIsPersistedIsWhatWasTracked() {
        CapacityTariffTracker t = new CapacityTariffTracker(ZONE);
        t.sample(-4000.0, NOW);
        t.sample(-4000.0, NOW + CapacityTariffTracker.SLOT_MS); // commits the first slot
        CapacityTariffTracker.Persisted p = t.persisted();
        assertEquals(2026, p.year());
        assertEquals(9, p.month());
        assertEquals(-4000.0, p.monthlyPeakW(), 1e-9);
    }
}

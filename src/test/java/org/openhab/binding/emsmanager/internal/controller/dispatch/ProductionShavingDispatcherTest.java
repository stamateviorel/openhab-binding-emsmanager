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
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;

/**
 * Anti-curtailment only makes sense while the house is exporting.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class ProductionShavingDispatcherTest {

    /** Battery 96 %, roof 9.9 kW, boiler off; the grid average is the variable. */
    private static EnergyContext fullBatteryBrightRoof(double grid5minAvgW) {
        return new EnergyContext(Instant.now(), grid5minAvgW, grid5minAvgW, 9930, 2000, 0, 96, 30, false, 0,
                EnergyContext.Mode.SOLAR_EXCESS, Map.of(), 0, 0, 0, true, false, false, true, grid5minAvgW, Double.NaN,
                false, 0, 0, 60_000L, 0.30, new double[0], Double.NaN, Double.NaN, false);
    }

    @Test
    void importingHouseGetsNoDumpLoad() {
        // The live flap: a car pulling 22 kW made the house import 2 kW under a full battery and a
        // bright roof; this switched the boiler on, the surplus dispatcher switched it off, every 5 s.
        assertTrue(new ProductionShavingDispatcher().evaluate(fullBatteryBrightRoof(-2016)).isEmpty());
    }

    @Test
    void exportingHouseStillGetsTheBoilerAsADumpLoad() {
        assertFalse(new ProductionShavingDispatcher().evaluate(fullBatteryBrightRoof(3000)).isEmpty());
    }

    @Test
    void anUnknownGridAverageIsNotAnExport() {
        assertTrue(new ProductionShavingDispatcher().evaluate(fullBatteryBrightRoof(Double.NaN)).isEmpty());
    }
}

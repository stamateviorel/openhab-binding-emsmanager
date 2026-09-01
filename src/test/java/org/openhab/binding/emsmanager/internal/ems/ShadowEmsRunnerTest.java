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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.core.CapabilityCheck;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.MetadataRegistry;

/**
 * The engine's per-car EV decision - the live decision maker at cutover, so its breaker pause must be
 * released the same way the legacy coordinator's is.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class ShadowEmsRunnerTest {

    private static final int BREAKER_A = 63;
    private static final int EFFECTIVE_A = BREAKER_A - CapabilityCheck.BREAKER_HEADROOM_A;

    private ShadowEmsRunner engine() {
        return new ShadowEmsRunner(mock(MetadataRegistry.class), mock(ItemRegistry.class), 1000, BREAKER_A, 2500, 500,
                true, "", SoftShaveBand.DEFAULT, EvElectrical.DEFAULT, LevelWindows.DEFAULT, false, List.of(), null);
    }

    private CarSnapshot eco(boolean paused, String status) {
        return new CarSnapshot("car1", CarSnapshot.Mode.ECO, true, status, 0, 0, 0, 0, 0, paused);
    }

    private EnergyContext ctx(CarSnapshot car, double otherLoadA) {
        return new EnergyContext(Instant.now(), -1000, -1000, 0, 1000, 0, 50, 30, false, 0,
                EnergyContext.Mode.GRID_IMPORT, Map.of(car.carKey(), car), otherLoadA, otherLoadA, otherLoadA, true,
                false, false, true, -1000, 0, false, -1000, -2000, 60_000L, 0.30, new double[0], Double.NaN, Double.NaN,
                false);
    }

    private ShadowEmsRunner.EvDecision only(List<ShadowEmsRunner.EvDecision> decisions) {
        assertEquals(1, decisions.size());
        return decisions.get(0);
    }

    @Test
    void breakerPauseIsReleasedOnceHeadroomRecovers() {
        ShadowEmsRunner engine = engine();

        ShadowEmsRunner.EvDecision paused = only(engine.decideEvs(ctx(eco(false, "Charging"), EFFECTIVE_A - 3)));
        assertEquals(1.0, paused.pause(), "3 A of headroom must pause the car");

        ShadowEmsRunner.EvDecision resumed = only(engine.decideEvs(ctx(eco(true, "SuspendedEVSE"), EFFECTIVE_A - 20)));
        assertEquals(0.0, resumed.pause(), "the engine must release the pause it set once headroom is back");
        assertNotNull(resumed.amps(), "and charge again");
    }

    @Test
    void breakerPauseIsHeldInTheHysteresisBand() {
        ShadowEmsRunner engine = engine();
        engine.decideEvs(ctx(eco(false, "Charging"), EFFECTIVE_A - 3));

        ShadowEmsRunner.EvDecision held = only(engine.decideEvs(ctx(eco(true, "SuspendedEVSE"), EFFECTIVE_A - 7)));

        assertNull(held.pause(), "7 A is not enough to release a breaker pause");
        assertNull(held.amps());
    }

    @Test
    void aPauseThatPredatesTheBreakerEventStaysExternal() {
        ShadowEmsRunner engine = engine();
        engine.decideEvs(ctx(eco(true, "SuspendedEVSE"), EFFECTIVE_A - 3));

        ShadowEmsRunner.EvDecision out = only(engine.decideEvs(ctx(eco(true, "SuspendedEVSE"), EFFECTIVE_A - 20)));

        assertNull(out.pause(), "ECO must not resume a pause the engine did not set");
        assertNull(out.amps());
    }

    @Test
    void anExternalPauseIsStillRespected() {
        ShadowEmsRunner.EvDecision out = only(engine().decideEvs(ctx(eco(true, "SuspendedEVSE"), 0)));

        assertNull(out.pause());
        assertNull(out.amps());
    }
}

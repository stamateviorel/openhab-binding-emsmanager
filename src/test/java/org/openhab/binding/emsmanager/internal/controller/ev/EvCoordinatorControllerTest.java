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
package org.openhab.binding.emsmanager.internal.controller.ev;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.controller.peak.HardPeakShavingController;
import org.openhab.binding.emsmanager.internal.controller.peak.SoftPeakShavingController;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * Tests for {@link EvCoordinatorController}, focused on the resume-after-pause
 * behaviour: a SNEL car must be woken from a stale pause (capacity-tariff and
 * peak-shaving never touch SNEL cars), while an ECO car's external pause is
 * respected to avoid flapping against the controller that set it.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class EvCoordinatorControllerTest {

    private EvCoordinatorController newController() {
        return new EvCoordinatorController(false, new HardPeakShavingController(false, false),
                new SoftPeakShavingController(false), 500);
    }

    private CarSnapshot car(String key, CarSnapshot.Mode mode, boolean paused, String status) {
        // Zero per-car/total amps → full breaker headroom; cable connected.
        return new CarSnapshot(key, mode, true, status, 0.0, 0.0, 0.0, 0.0, 0.0, paused);
    }

    private EnergyContext ctxWith(CarSnapshot car) {
        return new EnergyContext(Instant.now(), -13000, -13000, 0, 13000, -20, 40, 30, false, 0,
                EnergyContext.Mode.GRID_IMPORT, Map.of(car.carKey(), car), 0, 0, 0, true, false, false, true, -13000, 0,
                false, -13000, -2000, 60_000L, 0.30, new double[0], Double.NaN, Double.NaN, false);
    }

    @Test
    void snelResumesAStalePausedCar() {
        List<SetpointRequest> out = newController()
                .evaluate(ctxWith(car("car1", CarSnapshot.Mode.SNEL, true, "SuspendedEVSE")));

        Optional<SetpointRequest> pause = out.stream().filter(r -> r.assetId().equals("car1"))
                .filter(r -> r.kind() == SetpointRequest.Kind.PAUSE).findFirst();
        assertTrue(pause.isPresent(), "SNEL must emit a pause setpoint to clear a stale pause");
        assertEquals(0.0, pause.get().value(), 1e-9, "SNEL pause setpoint must be a resume (0.0), not a pause");

        boolean hasAmps = out.stream()
                .anyMatch(r -> r.assetId().equals("car1") && r.kind() == SetpointRequest.Kind.AMPS);
        assertTrue(hasAmps, "SNEL must also push a current-limit setpoint");
    }

    @Test
    void ecoRespectsAnExternalPause() {
        List<SetpointRequest> out = newController()
                .evaluate(ctxWith(car("car1", CarSnapshot.Mode.ECO, true, "SuspendedEVSE")));

        assertTrue(out.stream().noneMatch(r -> r.assetId().equals("car1")),
                "ECO must not fight a pause it did not set — no setpoints expected for a paused ECO car");
    }

    @Test
    void snelChargesNormallyWhenNotPaused() {
        List<SetpointRequest> out = newController()
                .evaluate(ctxWith(car("car1", CarSnapshot.Mode.SNEL, false, "Charging")));

        boolean hasAmps = out.stream()
                .anyMatch(r -> r.assetId().equals("car1") && r.kind() == SetpointRequest.Kind.AMPS);
        assertTrue(hasAmps, "SNEL with cable connected must push a current-limit setpoint");
    }
}

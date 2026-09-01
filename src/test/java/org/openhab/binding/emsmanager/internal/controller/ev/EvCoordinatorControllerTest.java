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
import org.openhab.binding.emsmanager.internal.core.CapabilityCheck;
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
        return ctxWith(car, 0);
    }

    /** {@code otherLoadA} is what everyone else draws on every phase; the car itself draws nothing. */
    private EnergyContext ctxWith(CarSnapshot car, double otherLoadA) {
        return new EnergyContext(Instant.now(), -13000, -13000, 0, 13000, -20, 40, 30, false, 0,
                EnergyContext.Mode.GRID_IMPORT, Map.of(car.carKey(), car), otherLoadA, otherLoadA, otherLoadA, true,
                false, false, true, -13000, 0, false, -13000, -2000, 60_000L, 0.30, new double[0], Double.NaN,
                Double.NaN, false);
    }

    private Optional<SetpointRequest> pauseFor(List<SetpointRequest> out) {
        return out.stream().filter(r -> r.assetId().equals("car1") && r.kind() == SetpointRequest.Kind.PAUSE)
                .findFirst();
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

    /**
     * The pause the coordinator sets for breaker headroom is its own and must be released when the
     * headroom comes back - otherwise an ECO car paused for a busy phase looks "externally paused"
     * forever and never charges again.
     */
    @Test
    void ecoPausedForBreakerHeadroomIsResumedOnceHeadroomRecovers() {
        EvCoordinatorController controller = newController();
        int limit = CapabilityCheck.EFFECTIVE_LIMIT_A;

        List<SetpointRequest> paused = controller
                .evaluate(ctxWith(car("car1", CarSnapshot.Mode.ECO, false, "Charging"), limit - 3));
        assertEquals(1.0, pauseFor(paused).orElseThrow().value(), 1e-9, "3 A of headroom must pause the car");

        List<SetpointRequest> resumed = controller
                .evaluate(ctxWith(car("car1", CarSnapshot.Mode.ECO, true, "SuspendedEVSE"), limit - 20));
        assertEquals(0.0, pauseFor(resumed).orElseThrow().value(), 1e-9,
                "the coordinator must release the pause it set once headroom is back");
        assertTrue(resumed.stream().anyMatch(r -> r.assetId().equals("car1") && r.kind() == SetpointRequest.Kind.AMPS),
                "and start charging again");
    }

    /** Between 6 A and 8 A of headroom the car stays paused, so it does not flap at the boundary. */
    @Test
    void breakerPauseIsHeldInTheHysteresisBand() {
        EvCoordinatorController controller = newController();
        int limit = CapabilityCheck.EFFECTIVE_LIMIT_A;
        controller.evaluate(ctxWith(car("car1", CarSnapshot.Mode.ECO, false, "Charging"), limit - 3));

        List<SetpointRequest> held = controller
                .evaluate(ctxWith(car("car1", CarSnapshot.Mode.ECO, true, "SuspendedEVSE"), limit - 7));

        assertTrue(held.stream().noneMatch(r -> r.assetId().equals("car1")),
                "7 A of headroom is not enough to release a breaker pause");
    }

    /** A pause that was already there when the phase filled up is somebody else's, and stays theirs. */
    @Test
    void aPauseThatPredatesTheBreakerEventIsNotReleased() {
        EvCoordinatorController controller = newController();
        int limit = CapabilityCheck.EFFECTIVE_LIMIT_A;
        controller.evaluate(ctxWith(car("car1", CarSnapshot.Mode.ECO, true, "SuspendedEVSE"), limit - 3));

        List<SetpointRequest> out = controller
                .evaluate(ctxWith(car("car1", CarSnapshot.Mode.ECO, true, "SuspendedEVSE"), limit - 20));

        assertTrue(out.stream().noneMatch(r -> r.assetId().equals("car1")),
                "ECO must not resume a pause it did not set");
    }

    @Test
    void snelChargesNormallyWhenNotPaused() {
        List<SetpointRequest> out = newController()
                .evaluate(ctxWith(car("car1", CarSnapshot.Mode.SNEL, false, "Charging")));

        boolean hasAmps = out.stream()
                .anyMatch(r -> r.assetId().equals("car1") && r.kind() == SetpointRequest.Kind.AMPS);
        assertTrue(hasAmps, "SNEL with cable connected must push a current-limit setpoint");
    }

    private boolean hasStart(List<SetpointRequest> out) {
        return out.stream().anyMatch(r -> r.assetId().equals("car1") && r.kind() == SetpointRequest.Kind.CHARGE_START);
    }

    /**
     * A charger wedged in "Preparing" (Rejects every RemoteStart) must get a
     * short burst of quick attempts and then back off entirely — no RemoteStart
     * spam and, once backed off, no pointless current-limit re-sends either.
     */
    @Test
    void backoffStopsHammeringAWedgedCar() {
        EvCoordinatorController controller = newController();
        CarSnapshot wedged = car("car1", CarSnapshot.Mode.SNEL, false, "Preparing");

        int starts = 0;
        int silentTicks = 0;
        for (int tick = 1; tick <= 10; tick++) {
            List<SetpointRequest> out = controller.evaluate(ctxWith(wedged));
            if (hasStart(out)) {
                starts++;
            }
            if (out.stream().noneMatch(r -> r.assetId().equals("car1"))) {
                silentTicks++;
            }
        }

        assertEquals(5, starts, "a wedged charger must get exactly the 5-attempt burst, then stop hammering");
        assertEquals(5, silentTicks,
                "once backed off a wedged car must emit nothing — not even a current-limit re-send");
    }

    @Test
    void replugResetsBackoff() {
        EvCoordinatorController controller = newController();
        CarSnapshot wedged = car("car1", CarSnapshot.Mode.SNEL, false, "Preparing");
        for (int i = 0; i < 8; i++) {
            controller.evaluate(ctxWith(wedged)); // drive past the burst into backoff
        }
        // Cable unplugged for one tick — clears the backoff counter.
        CarSnapshot unplugged = new CarSnapshot("car1", CarSnapshot.Mode.SNEL, false, "Available", 0.0, 0.0, 0.0, 0.0,
                0.0, false);
        controller.evaluate(ctxWith(unplugged));

        List<SetpointRequest> out = controller.evaluate(ctxWith(wedged));
        assertTrue(hasStart(out), "a physical replug must reset the backoff and earn a fresh RemoteStart");
    }

    @Test
    void chargingResetsBackoff() {
        EvCoordinatorController controller = newController();
        CarSnapshot wedged = car("car1", CarSnapshot.Mode.SNEL, false, "Preparing");
        for (int i = 0; i < 8; i++) {
            controller.evaluate(ctxWith(wedged)); // into backoff
        }
        // A successful charge leaves the start-states → counter resets.
        controller.evaluate(ctxWith(car("car1", CarSnapshot.Mode.SNEL, false, "Charging")));

        List<SetpointRequest> out = controller.evaluate(ctxWith(wedged));
        assertTrue(hasStart(out),
                "a completed charge must reset the backoff so a later Preparing earns fresh attempts");
    }
}

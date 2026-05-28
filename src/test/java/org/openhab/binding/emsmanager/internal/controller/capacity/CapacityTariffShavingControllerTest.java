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
package org.openhab.binding.emsmanager.internal.controller.capacity;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * Tests for {@link CapacityTariffShavingController}, focused on the
 * paused-by-me bookkeeping that releases an ECO car when the peak event
 * passes — otherwise the car sits paused forever and users assume the
 * binding is broken.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class CapacityTariffShavingControllerTest {

    private static final int MIN_BILLABLE_W = 2500;

    private CarSnapshot car(String key, CarSnapshot.Mode mode, boolean paused) {
        return new CarSnapshot(key, mode, true, paused ? "SuspendedEVSE" : "Charging", 0.0, 0.0, 0.0, 0.0, 0.0, paused);
    }

    /**
     * Build a context with a single car and the two capacity-tariff inputs
     * driving wouldExceed. quarterAvgW &lt; -minBillableW-margin and worse than
     * the monthly peak triggers shaving; quarterAvgW closer to zero (or
     * positive) means no shaving needed.
     */
    private EnergyContext ctx(CarSnapshot car, double quarterAvgW, double monthlyPeakW) {
        return new EnergyContext(Instant.now(), -1000, -1000, 0, 1000, 0, 50, 30, false, 0,
                EnergyContext.Mode.GRID_IMPORT, Map.of(car.carKey(), car), 0, 0, 0, true, false, false, true, -1000, 0,
                false, quarterAvgW, monthlyPeakW, 60_000L, 0.30, new double[0], Double.NaN, Double.NaN, false);
    }

    private Optional<SetpointRequest> pauseFor(List<SetpointRequest> out, String carKey) {
        return out.stream().filter(r -> r.assetId().equals(carKey) && r.kind() == SetpointRequest.Kind.PAUSE)
                .findFirst();
    }

    @Test
    void pausesEcoCarWhenProjectionExceedsMonthlyPeak() {
        CapacityTariffShavingController ctrl = new CapacityTariffShavingController(false, MIN_BILLABLE_W);
        // projection well over the floor and worse than the prior peak → shave.
        List<SetpointRequest> out = ctrl.evaluate(ctx(car("car1", CarSnapshot.Mode.ECO, false), -10_000, -2_000));

        Optional<SetpointRequest> pause = pauseFor(out, "car1");
        assertTrue(pause.isPresent(), "must emit a pause when projection exceeds monthly peak");
        assertEquals(1.0, pause.get().value(), 1e-9, "must be a pause (1.0), not a resume");
    }

    @Test
    void releasesPreviouslyPausedEcoCarWhenPeakPasses() {
        CapacityTariffShavingController ctrl = new CapacityTariffShavingController(false, MIN_BILLABLE_W);
        // Tick 1: shave — the car gets paused by us.
        ctrl.evaluate(ctx(car("car1", CarSnapshot.Mode.ECO, false), -10_000, -2_000));
        // Tick 2: the dispatch has flipped the pause item ON; projection still high.
        ctrl.evaluate(ctx(car("car1", CarSnapshot.Mode.ECO, true), -10_000, -2_000));
        // Tick 3: peak event has passed — projection now below the billable floor.
        List<SetpointRequest> out = ctrl.evaluate(ctx(car("car1", CarSnapshot.Mode.ECO, true), -1_000, -2_000));

        Optional<SetpointRequest> release = pauseFor(out, "car1");
        assertTrue(release.isPresent(), "must release the car it previously paused");
        assertEquals(0.0, release.get().value(), 1e-9, "release must be a resume (0.0)");
    }

    @Test
    void doesNotSpamReleasesOnConsecutiveBelowTicks() {
        CapacityTariffShavingController ctrl = new CapacityTariffShavingController(false, MIN_BILLABLE_W);
        ctrl.evaluate(ctx(car("car1", CarSnapshot.Mode.ECO, false), -10_000, -2_000)); // pause
        ctrl.evaluate(ctx(car("car1", CarSnapshot.Mode.ECO, true), -1_000, -2_000)); // first release
        List<SetpointRequest> outAgain = ctrl.evaluate(ctx(car("car1", CarSnapshot.Mode.ECO, false), -1_000, -2_000));

        assertTrue(outAgain.isEmpty(), "after the one-shot release the controller must go quiet");
    }

    @Test
    void firstEvalClaimsAnAlreadyPausedEcoCarSoARestartDoesNotStrandIt() {
        // Simulates a bridge restart mid-event: the controller has no memory but
        // sees an already-paused ECO car. It should adopt it so the next
        // wouldExceed=false transition releases it.
        CapacityTariffShavingController ctrl = new CapacityTariffShavingController(false, MIN_BILLABLE_W);
        List<SetpointRequest> out = ctrl.evaluate(ctx(car("car1", CarSnapshot.Mode.ECO, true), -1_000, -2_000));

        Optional<SetpointRequest> release = pauseFor(out, "car1");
        assertTrue(release.isPresent(), "must adopt-and-release a paused ECO car on first eval after restart");
        assertEquals(0.0, release.get().value(), 1e-9);
    }

    @Test
    void leavesPausedSnelCarAlone() {
        // A paused SNEL car is not capacity-tariff's problem; the EvCoordinator
        // handles SNEL resume. Capacity must not claim or release it.
        CapacityTariffShavingController ctrl = new CapacityTariffShavingController(false, MIN_BILLABLE_W);
        List<SetpointRequest> out = ctrl.evaluate(ctx(car("car1", CarSnapshot.Mode.SNEL, true), -1_000, -2_000));

        assertTrue(pauseFor(out, "car1").isEmpty(), "must not touch SNEL cars");
    }
}

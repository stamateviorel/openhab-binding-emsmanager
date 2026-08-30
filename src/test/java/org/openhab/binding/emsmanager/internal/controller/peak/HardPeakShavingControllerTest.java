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
package org.openhab.binding.emsmanager.internal.controller.peak;

import static org.junit.jupiter.api.Assertions.*;
import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * Tests for {@link HardPeakShavingController} — the progressive load shedding that runs when the site is importing
 * hard enough to matter.
 * <p>
 * The behaviour that most needs pinning is <strong>ECO sacrosanct</strong>, which this site runs: with it on, the
 * ECO-pause tier is dropped entirely and shedding is boiler then aircon. A regression there would start pausing cars
 * that are deliberately protected, which is exactly the kind of thing nobody notices until a car is flat.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class HardPeakShavingControllerTest {

    private static final Instant T0 = Instant.parse("2026-01-11T12:00:00Z");
    private static final double DEEP_IMPORT = HARD_PEAK_THRESHOLD_W - 1000.0;
    private static final double RECOVERED = HARD_RECOVERY_THRESHOLD_W + 1000.0;

    private EnergyContext ctx(Instant at, double gridW, boolean enabled, boolean boilerOn, boolean aircoOn) {
        Map<String, CarSnapshot> cars = new HashMap<>();
        cars.put("car1", new CarSnapshot("car1", CarSnapshot.Mode.ECO, true, "Charging", 0, 0, 0, 0, 16, false));
        return new EnergyContext(at, gridW, gridW, 0, 0, 0, 50, 30, false, 0, EnergyContext.Mode.GRID_IMPORT, cars, 0,
                0, 0, true, boilerOn, aircoOn, enabled, gridW, 0, false, 0, 0, 60_000L, 0.30, new double[0], Double.NaN,
                Double.NaN, false);
    }

    private EnergyContext importing(Instant at) {
        return ctx(at, DEEP_IMPORT, true, true, true);
    }

    /** Drives the controller past its confirmation window and returns whatever the engaging tick emitted. */
    private List<SetpointRequest> engage(HardPeakShavingController controller) {
        controller.evaluate(importing(T0));
        return controller.evaluate(importing(T0.plusSeconds(HARD_PEAK_DURATION_SEC + 1)));
    }

    @Test
    void aQuietGridChangesNothing() {
        HardPeakShavingController controller = new HardPeakShavingController(false, true);

        assertTrue(controller.evaluate(ctx(T0, RECOVERED, true, true, true)).isEmpty());
        assertEquals(0, controller.level());
    }

    /** A momentary spike is not a peak. Shedding on one tick would flap the boiler on every kettle. */
    @Test
    void aBriefSpikeDoesNotShedAnything() {
        HardPeakShavingController controller = new HardPeakShavingController(false, true);

        controller.evaluate(importing(T0));
        List<SetpointRequest> out = controller.evaluate(importing(T0.plusSeconds(5)));

        assertTrue(out.isEmpty(), "the confirmation window must not have elapsed after five seconds");
        assertEquals(0, controller.level());
    }

    @Test
    void aSustainedPeakEngagesTheFirstTier() {
        HardPeakShavingController controller = new HardPeakShavingController(false, true);

        List<SetpointRequest> out = engage(controller);

        assertEquals(1, controller.level(), "a sustained peak must engage");
        assertFalse(out.isEmpty(), "engaging must actually shed something");
    }

    @Test
    void aGridThatRecoversRestartsTheConfirmationWindow() {
        HardPeakShavingController controller = new HardPeakShavingController(false, true);

        controller.evaluate(importing(T0));
        controller.evaluate(ctx(T0.plusSeconds(10), RECOVERED, true, true, true));
        controller.evaluate(importing(T0.plusSeconds(HARD_PEAK_DURATION_SEC + 1)));

        assertEquals(0, controller.level(), "the clock must restart once the grid comes back inside the threshold");
    }

    /**
     * <strong>The one that matters on this site.</strong> With ECO sacrosanct the first thing shed is the boiler, and
     * no tier ever pauses a car.
     */
    @Test
    void withEcoSacrosanctTheBoilerIsShedFirstAndNoCarIsPaused() {
        HardPeakShavingController controller = new HardPeakShavingController(false, true);

        List<SetpointRequest> out = engage(controller);

        assertTrue(out.stream().anyMatch(r -> ASSET_BOILER.equals(r.assetId())), "tier one must shed the boiler");
        assertTrue(out.stream().noneMatch(r -> r.kind() == SetpointRequest.Kind.PAUSE),
                "no car may be paused while ECO is sacrosanct");
    }

    @Test
    void withEcoSacrosanctSheddingStopsAfterTheAircon() {
        HardPeakShavingController controller = new HardPeakShavingController(false, true);
        engage(controller);

        Instant at = T0.plusSeconds(HARD_PEAK_DURATION_SEC + 1);
        for (int step = 1; step <= 4; step++) {
            at = at.plus(Duration.ofSeconds(HARD_TIER_INTERVAL_SEC + 1));
            controller.evaluate(importing(at));
        }

        assertEquals(2, controller.level(), "sacrosanct shedding tops out at boiler plus aircon");
    }

    /** Without the flag the site behaves as the binding ships: the ECO-pause tier is back, and it is first. */
    @Test
    void withoutEcoSacrosanctTheFirstTierPausesEcoCars() {
        HardPeakShavingController controller = new HardPeakShavingController(false, false);

        List<SetpointRequest> out = engage(controller);

        assertTrue(out.stream().anyMatch(r -> "car1".equals(r.assetId()) && r.kind() == SetpointRequest.Kind.PAUSE),
                "the shipped behaviour pauses ECO cars first");
    }

    @Test
    void theKillSwitchReleasesEverythingItHadShed() {
        HardPeakShavingController controller = new HardPeakShavingController(false, true);
        engage(controller);

        List<SetpointRequest> out = controller
                .evaluate(ctx(T0.plusSeconds(HARD_PEAK_DURATION_SEC + 10), DEEP_IMPORT, false, true, true));

        assertEquals(0, controller.level(), "disabling the controller must stand everything down");
        assertFalse(out.isEmpty(), "standing down must put back what was shed");
    }

    @Test
    void aManualResetStandsEveryTierDown() {
        HardPeakShavingController controller = new HardPeakShavingController(false, true);
        engage(controller);

        controller.requestManualReset();
        List<SetpointRequest> out = controller.evaluate(importing(T0.plusSeconds(HARD_PEAK_DURATION_SEC + 10)));

        assertEquals(0, controller.level());
        assertFalse(out.isEmpty(), "a manual reset must release, not merely forget");
    }

    /** A manual engage skips the confirmation window, which is the point of having it. */
    @Test
    void aManualEngageDoesNotWaitForTheWindow() {
        HardPeakShavingController controller = new HardPeakShavingController(false, true);

        controller.requestManualEngage();
        controller.evaluate(ctx(T0, RECOVERED, true, true, true));

        assertEquals(1, controller.level(), "a manual engage applies on the next tick regardless of the grid");
    }

    @Test
    void aRecoveredGridStepsBackDownOneTierAtATime() {
        HardPeakShavingController controller = new HardPeakShavingController(false, true);
        engage(controller);
        assertEquals(1, controller.level());

        controller.evaluate(
                ctx(T0.plusSeconds(HARD_PEAK_DURATION_SEC + HARD_TIER_INTERVAL_SEC + 5), RECOVERED, true, true, true));

        assertEquals(0, controller.level(), "a recovered grid must release the tier once the dwell has passed");
    }

    @Test
    void aRecoveredGridHoldsTheTierUntilTheDwellHasPassed() {
        HardPeakShavingController controller = new HardPeakShavingController(false, true);
        engage(controller);

        controller.evaluate(ctx(T0.plusSeconds(HARD_PEAK_DURATION_SEC + 2), RECOVERED, true, true, true));

        assertEquals(1, controller.level(), "releasing immediately would flap against the load it just shed");
    }
}

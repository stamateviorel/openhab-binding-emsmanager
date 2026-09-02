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
package org.openhab.binding.emsmanager.internal.controller.safety;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.core.CapabilityCheck;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * Tests for {@link SafetyBreakerController}, the one controller that stands between the chargers and an overloaded
 * phase.
 * <p>
 * It is always on, cannot be disabled, and runs first. Everything here is about the two ways it can be wrong: failing
 * to pause a car that would overload a phase, and pausing one that would not - the second being how a safety net
 * teaches people to switch it off.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class SafetyBreakerControllerTest {

    private static final int LIMIT_A = CapabilityCheck.EFFECTIVE_LIMIT_A;

    private CarSnapshot car(String key, boolean cableConnected, double l1, double l2, double l3) {
        return car(key, cableConnected, l1, l2, l3, false);
    }

    private CarSnapshot car(String key, boolean cableConnected, double l1, double l2, double l3, boolean paused) {
        return new CarSnapshot(key, CarSnapshot.Mode.ECO, cableConnected, "Charging", l1, l2, l3, 0.0, 16.0, paused);
    }

    private EnergyContext ctx(boolean modbusFresh, double totalL1, double totalL2, double totalL3,
            CarSnapshot... cars) {
        Map<String, CarSnapshot> map = new HashMap<>();
        for (CarSnapshot c : cars) {
            map.put(c.carKey(), c);
        }
        return new EnergyContext(Instant.now(), 0, 0, 0, 0, 0, 50, 30, false, 0, EnergyContext.Mode.BALANCED, map,
                totalL1, totalL2, totalL3, modbusFresh, false, false, true, 0, 0, false, 0, 0, 60_000L, 0.30,
                new double[0], Double.NaN, Double.NaN, false);
    }

    private Optional<SetpointRequest> requestFor(List<SetpointRequest> out, String car, SetpointRequest.Kind kind) {
        return out.stream().filter(r -> car.equals(r.assetId()) && r.kind() == kind).findFirst();
    }

    @Test
    void aCarWithRoomOnEveryPhaseIsLeftAlone() {
        List<SetpointRequest> out = new SafetyBreakerController(false)
                .evaluate(ctx(true, 10, 10, 10, car("car1", true, 10, 10, 10)));

        assertTrue(out.isEmpty(), "nothing should be emitted while every phase has room");
    }

    /**
     * The subtlety the whole check turns on: headroom is what is left <em>after removing this car's own draw</em>. A
     * car drawing 30 A on an otherwise empty phase has 53 A available to it, not 23 A, and a controller that forgot to
     * subtract it would pause every car that was charging properly.
     */
    @Test
    void aCarIsNotMeasuredAgainstItsOwnDraw() {
        double heavy = LIMIT_A - 5.0;
        List<SetpointRequest> out = new SafetyBreakerController(false)
                .evaluate(ctx(true, heavy, heavy, heavy, car("car1", true, heavy, heavy, heavy)));

        assertTrue(out.isEmpty(), "a car must not pause itself for the current it is itself drawing");
    }

    @Test
    void aCarWithNoRoomLeftByTheOthersIsPaused() {
        // car2 is drawing the phase nearly flat; car1 contributes nothing and has nowhere to go
        List<SetpointRequest> out = new SafetyBreakerController(false)
                .evaluate(ctx(true, LIMIT_A - 1.0, 5, 5, car("car1", true, 0, 0, 0)));

        Optional<SetpointRequest> pause = requestFor(out, "car1", SetpointRequest.Kind.PAUSE);
        assertTrue(pause.isPresent(), "a car with less than the minimum of headroom must be paused");
        assertEquals(1.0, pause.get().value(), 1e-9, "the request must be a pause, not a resume");
        assertEquals(SafetyBreakerController.NAME, pause.get().controllerName());
    }

    /** The worst phase decides. Two phases being clear is no comfort if the third is full. */
    @Test
    void theWorstPhaseDecides() {
        List<SetpointRequest> out = new SafetyBreakerController(false)
                .evaluate(ctx(true, 0, 0, LIMIT_A - 1.0, car("car1", true, 0, 0, 0)));

        assertTrue(requestFor(out, "car1", SetpointRequest.Kind.PAUSE).isPresent(),
                "a full third phase must pause the car even where L1 and L2 are empty");
    }

    /** Exactly the minimum is enough; the check is "less than", and a car that can draw 6 A should be allowed to. */
    @Test
    void exactlyTheMinimumOfHeadroomIsNotPaused() {
        double used = LIMIT_A - CapabilityCheck.MIN_CHARGING_CURRENT_A;
        List<SetpointRequest> out = new SafetyBreakerController(false)
                .evaluate(ctx(true, used, used, used, car("car1", true, 0, 0, 0)));

        assertTrue(out.isEmpty(), "a car with exactly the minimum of headroom must be left charging");
    }

    @Test
    void anUnpluggedCarIsNotConsideredAtAll() {
        List<SetpointRequest> out = new SafetyBreakerController(false)
                .evaluate(ctx(true, LIMIT_A, LIMIT_A, LIMIT_A, car("car1", false, 0, 0, 0)));

        assertTrue(out.isEmpty(), "a car with no cable cannot overload anything");
    }

    @Test
    void onlyTheCarWithoutRoomIsTouched() {
        // the phase is full of car2's draw, so car1 has nothing; car2 is not measured against itself
        List<SetpointRequest> out = new SafetyBreakerController(false).evaluate(
                ctx(true, LIMIT_A - 1.0, 5, 5, car("car1", true, 0, 0, 0), car("car2", true, LIMIT_A - 1.0, 5, 5)));

        assertTrue(requestFor(out, "car1", SetpointRequest.Kind.PAUSE).isPresent(), "car1 has no headroom");
        assertTrue(requestFor(out, "car2", SetpointRequest.Kind.PAUSE).isEmpty(),
                "car2 is drawing that current itself and must not be paused for it");
    }

    /**
     * <strong>The fail-safe.</strong> Per-phase amps come from one Modbus bridge, and when it drops every reading
     * silently reads zero - which looks exactly like a completely idle installation. Believing that would ramp every
     * car to maximum, so a stale bridge caps everything at the minimum instead.
     */
    @Test
    void aStaleModbusBridgeCapsEveryPluggedCarAtTheMinimum() {
        List<SetpointRequest> out = new SafetyBreakerController(false)
                .evaluate(ctx(false, 0, 0, 0, car("car1", true, 0, 0, 0), car("car2", true, 0, 0, 0)));

        for (String car : List.of("car1", "car2")) {
            Optional<SetpointRequest> amps = requestFor(out, car, SetpointRequest.Kind.AMPS);
            assertTrue(amps.isPresent(), car + " must be capped while the readings cannot be trusted");
            assertEquals(CapabilityCheck.MIN_CHARGING_CURRENT_A, amps.get().value(), 1e-9);
        }
        assertTrue(out.stream().noneMatch(r -> r.kind() == SetpointRequest.Kind.PAUSE),
                "the stale-readings fallback caps rather than pauses, so a car keeps charging slowly");
    }

    @Test
    void aStaleModbusBridgeStillIgnoresUnpluggedCars() {
        List<SetpointRequest> out = new SafetyBreakerController(false)
                .evaluate(ctx(false, 0, 0, 0, car("car1", false, 0, 0, 0)));

        assertTrue(out.isEmpty(), "no cable, nothing to cap");
    }

    /** A pause is only half a safety net: the car has to come back once the phase has room again. */
    @Test
    void aPauseItSetIsReleasedOnceThereIsRoomAgain() {
        SafetyBreakerController controller = new SafetyBreakerController(false);
        controller.evaluate(ctx(true, LIMIT_A - 3.0, 5, 5, car("car1", true, 0, 0, 0)));

        List<SetpointRequest> out = controller
                .evaluate(ctx(true, LIMIT_A - 20.0, 5, 5, car("car1", true, 0, 0, 0, true)));

        Optional<SetpointRequest> resume = requestFor(out, "car1", SetpointRequest.Kind.PAUSE);
        assertTrue(resume.isPresent(), "the car must be resumed once headroom is back");
        assertEquals(0.0, resume.get().value(), 1e-9, "a resume, not another pause");
    }

    /** Right at the minimum the car would flap on and off with every amp, so release waits for a little more. */
    @Test
    void thePauseIsHeldUntilHeadroomClearsTheHysteresisBand() {
        SafetyBreakerController controller = new SafetyBreakerController(false);
        controller.evaluate(ctx(true, LIMIT_A - 3.0, 5, 5, car("car1", true, 0, 0, 0)));

        List<SetpointRequest> out = controller.evaluate(ctx(true,
                LIMIT_A - (SafetyBreakerController.RESUME_HEADROOM_A - 1.0), 5, 5, car("car1", true, 0, 0, 0, true)));

        assertTrue(out.isEmpty(), "just under the resume threshold the car stays paused");
    }

    /** Someone else's pause - a manual one, a capacity-tariff one - is not this controller's to lift. */
    @Test
    void aPauseThatWasAlreadyThereIsNotReleased() {
        SafetyBreakerController controller = new SafetyBreakerController(false);
        controller.evaluate(ctx(true, LIMIT_A - 3.0, 5, 5, car("car1", true, 0, 0, 0, true)));

        List<SetpointRequest> out = controller
                .evaluate(ctx(true, LIMIT_A - 20.0, 5, 5, car("car1", true, 0, 0, 0, true)));

        assertTrue(out.isEmpty(), "a pause that predates the breaker event belongs to whoever set it");
    }

    @Test
    void theControllerRunsFirstAndCannotBeDisabled() {
        SafetyBreakerController controller = new SafetyBreakerController(false);

        assertTrue(controller.enabled(), "the breaker check must never be switchable off");
        assertEquals(10, controller.priority(), "it must sort ahead of every other controller");
    }

    @Test
    void minHeadroomIsTheMaximumWhenNothingIsPlugged() {
        int headroom = SafetyBreakerController.minHeadroomA(ctx(true, 40, 40, 40, car("car1", false, 0, 0, 0)), LIMIT_A,
                CapabilityCheck.MAX_CHARGING_CURRENT_A);

        assertEquals(CapabilityCheck.MAX_CHARGING_CURRENT_A, headroom,
                "with no cable anywhere the site is not constrained by the chargers");
    }

    @Test
    void minHeadroomIsTheWorstOfThePluggedCars() {
        int headroom = SafetyBreakerController.minHeadroomA(
                ctx(true, LIMIT_A - 10.0, 5, 5, car("car1", true, 0, 0, 0), car("car2", true, LIMIT_A - 10.0, 5, 5)),
                LIMIT_A, CapabilityCheck.MAX_CHARGING_CURRENT_A);

        assertEquals(10, headroom, "car1 is the constrained one and it is the one that counts");
    }
}

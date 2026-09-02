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
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * Who gets the surplus first, the car or the boiler - and in particular that a car which is not
 * taking any does not keep the boiler waiting.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class SolarSurplusDispatcherTest {

    private CarSnapshot ecoCar(String status, double drawW) {
        return new CarSnapshot("car1", CarSnapshot.Mode.ECO, true, status, 0, 0, 0, drawW, 6.0, false);
    }

    /** 3 kW of export averaged over 5 min, boiler off, nothing cloudy: the boiler should come on. */
    private EnergyContext exporting(CarSnapshot car) {
        return new EnergyContext(Instant.now(), 3000, 3000, 5000, 2000, 0, 60, 30, false, 3000,
                EnergyContext.Mode.SOLAR_EXCESS, Map.of(car.carKey(), car), 0, 0, 0, true, false, false, true, 3000,
                Double.NaN, false, 3000, 0, 60_000L, 0.30, new double[0], Double.NaN, Double.NaN, false);
    }

    private boolean boilerOn(List<SetpointRequest> out) {
        return out.stream().anyMatch(
                r -> "boiler".equals(r.assetId()) && r.kind() == SetpointRequest.Kind.ONOFF && r.value() > 0.5);
    }

    @Test
    void aFullCarDoesNotKeepTheBoilerWaiting() {
        List<SetpointRequest> out = new SolarSurplusDispatcher(false, null)
                .evaluate(exporting(ecoCar("SuspendedEV", 0)));

        assertTrue(boilerOn(out), "a car at 0 W with a 6 A limit is full, and the export should heat water");
    }

    @Test
    void aChargingCarStillGetsTheSurplusFirst() {
        List<SetpointRequest> out = new SolarSurplusDispatcher(false, null)
                .evaluate(exporting(ecoCar("Charging", 4000)));

        assertFalse(boilerOn(out), "a car actually drawing below its maximum gets the surplus first");
    }

    /** A charger that reports Charging but whose meter has not caught up yet still counts as drawing. */
    @Test
    void chargingStatusCountsEvenBeforeTheMeterMoves() {
        assertTrue(SolarSurplusDispatcher.anyEcoCarWantsMoreCurrent(exporting(ecoCar("Charging", 0))));
    }

    @Test
    void aCarAtItsMaximumDoesNotWantMore() {
        CarSnapshot maxed = new CarSnapshot("car1", CarSnapshot.Mode.ECO, true, "Charging", 0, 0, 0, 22000, 32.0,
                false);

        assertFalse(SolarSurplusDispatcher.anyEcoCarWantsMoreCurrent(exporting(maxed)));
    }
}

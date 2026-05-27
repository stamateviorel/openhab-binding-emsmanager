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
package org.openhab.binding.emsmanager.internal.heatpump;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Convergence tests for the RLS thermal estimator. We generate synthetic
 * data from a known (R, C) and check that the estimator converges.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class ThermalModelEstimatorTest {

    /** Forward-simulate one Δt step. */
    private static double simulateStep(double tIn, double tOut, double heatInputW, double dt, double r, double c) {
        // dT/dt = ((T_out − T_in)/R + Q) / C
        double dTdt = ((tOut - tIn) / r + heatInputW) / c;
        return tIn + dTdt * dt;
    }

    @Test
    void convergesOnSyntheticData() {
        double trueR = 0.005; // K/W
        double trueC = 5_000_000; // J/K
        ThermalModelEstimator est = new ThermalModelEstimator(0.98);

        // Simulate 7 days at 5-min step, with varying T_out and heat input.
        double dt = 300; // 5 min
        double tIn = 20.0;
        java.util.Random rng = new java.util.Random(42);
        for (int step = 0; step < 7 * 24 * 12; step++) {
            // T_out: daily sine wave 0..15
            double hour = (step * dt / 3600.0) % 24;
            double tOut = 7.5 + 7.5 * Math.sin(2 * Math.PI * (hour - 6) / 24);
            // Heat input: heat pump on 50% duty cycle, ~3 kW
            double q = (rng.nextDouble() < 0.5) ? 3000.0 : 0.0;
            double tInNext = simulateStep(tIn, tOut, q, dt, trueR, trueC);
            // Feed estimator
            est.update(dt, tIn, tInNext, tOut, q);
            tIn = tInNext;
        }

        // R should be within ±25 % of truth; C within ±30 %. RLS isn't a least-squares MLE
        // and noise-free synthetic data is the easy case; this is a loose convergence check.
        double estR = est.r();
        double estC = est.c();
        assertTrue(Math.abs(estR - trueR) / trueR < 0.25, "R should converge to ≈0.005; got " + estR);
        assertTrue(Math.abs(estC - trueC) / trueC < 0.30, "C should converge to ≈5e6; got " + estC);
        assertTrue(est.sampleCount() > 1000);
    }

    @Test
    void resetReturnsToPrior() {
        ThermalModelEstimator est = new ThermalModelEstimator();
        // Feed any garbage
        for (int i = 0; i < 100; i++) {
            est.update(60, 20, 21, 5, 1000);
        }
        double afterTheta0 = est.theta0();
        est.reset();
        assertNotEquals(afterTheta0, est.theta0());
        assertEquals(0, est.sampleCount());
    }

    @Test
    void rejectsNonFiniteInputs() {
        ThermalModelEstimator est = new ThermalModelEstimator();
        est.update(60, Double.NaN, 20, 5, 1000);
        est.update(60, 20, Double.POSITIVE_INFINITY, 5, 1000);
        est.update(60, 20, 21, Double.NaN, 1000);
        assertEquals(0, est.sampleCount(), "NaN/Inf inputs must be ignored");
    }

    @Test
    void rejectsAbsurdTimeGaps() {
        ThermalModelEstimator est = new ThermalModelEstimator();
        est.update(-1, 20, 21, 5, 1000);
        est.update(100000, 20, 21, 5, 1000); // 100k seconds > 24h
        est.update(0, 20, 21, 5, 1000);
        assertEquals(0, est.sampleCount());
    }
}

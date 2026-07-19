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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Online Recursive Least Squares (RLS) thermal model estimator.
 *
 * <p>
 * Single-zone first-order RC equivalent:
 *
 * <pre>
 *     C · dT_in/dt = (T_out − T_in)/R + Q_hp + Q_solar + Q_internal
 * </pre>
 *
 * <p>
 * Discretised one-step (Δt seconds):
 *
 * <pre>
 *     y = T_in(t+Δt) − T_in(t)
 *     x = [ Δt·(T_out − T_in) , Δt·(Q_hp + Q_solar + Q0) ]
 *     y ≈ θᵀx, where θ = [ 1/(RC), 1/C ]
 * </pre>
 *
 * <p>
 * Standard RLS with forgetting factor λ = 0.99:
 *
 * <pre>
 *     K = P x / (λ + xᵀ P x)
 *     θ ← θ + K (y − θᵀ x)
 *     P ← (P − K xᵀ P) / λ
 * </pre>
 *
 * <p>
 * Initialisation: high P₀ = 1e6·I means "I know nothing", weights early
 * samples heavily; converges within hundreds of samples under varied inputs.
 *
 * <p>
 * Numerically: 2-parameter case is small enough that we keep P in a
 * 2×2 matrix and write the math by hand. No external linear-algebra dep.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ThermalModelEstimator {

    /** Forgetting factor. λ = 1 → unweighted; λ < 1 → exponentially down-weight old samples. */
    private final double forgetting;

    /** Parameters θ = [1/(RC), 1/C]. */
    private double theta0;
    private double theta1;

    /** Covariance matrix P (2×2, symmetric). */
    private double p00, p01, p11;

    /** Last update's residual (y − θᵀx); useful for RMSE tracking. */
    private double lastResidual;

    /** Sample count since last reset. */
    private long sampleCount;

    public ThermalModelEstimator(double forgetting) {
        this.forgetting = forgetting;
        reset();
    }

    public ThermalModelEstimator() {
        this(0.99);
    }

    /** Reset to initial uncertainty. */
    public void reset() {
        // Physically-plausible priors for a 150 m² well-insulated Belgian home.
        // R ≈ 0.005 K/W, C ≈ 5e6 J/K → RC ≈ 25000 s → 1/(RC) ≈ 4e-5, 1/C ≈ 2e-7.
        this.theta0 = 4e-5;
        this.theta1 = 2e-7;
        // High uncertainty so first samples drive the estimate.
        this.p00 = 1e6;
        this.p01 = 0;
        this.p11 = 1e6;
        this.lastResidual = 0;
        this.sampleCount = 0;
    }

    /**
     * Feed one observation.
     *
     * @param dtSeconds time since previous sample
     * @param tInPrev T_in at previous sample (°C)
     * @param tInNow T_in at this sample (°C)
     * @param tOutPrev T_out at previous sample (°C)
     * @param heatInputW total instantaneous heat input (W) — Q_hp + Q_solar + Q_internal
     */
    public void update(double dtSeconds, double tInPrev, double tInNow, double tOutPrev, double heatInputW) {
        if (dtSeconds <= 0 || dtSeconds > 86400) {
            return; // skip absurd gaps
        }
        if (!Double.isFinite(tInPrev) || !Double.isFinite(tInNow) || !Double.isFinite(tOutPrev)
                || !Double.isFinite(heatInputW)) {
            return;
        }

        double y = tInNow - tInPrev;
        double x0 = dtSeconds * (tOutPrev - tInPrev); // coefficient for 1/(RC)
        double x1 = dtSeconds * heatInputW; // coefficient for 1/C

        // Predict + residual
        double yHat = theta0 * x0 + theta1 * x1;
        double err = y - yHat;

        // K = P x / (λ + xᵀ P x). 2×2 case:
        double pX0 = p00 * x0 + p01 * x1;
        double pX1 = p01 * x0 + p11 * x1;
        double denom = forgetting + x0 * pX0 + x1 * pX1;
        if (denom == 0) {
            return;
        }
        double k0 = pX0 / denom;
        double k1 = pX1 / denom;

        // θ ← θ + K · err
        theta0 += k0 * err;
        theta1 += k1 * err;

        // P ← (P − K xᵀ P) / λ. K xᵀ P is a 2×2 rank-1; subtract entry by entry.
        // K xᵀ = [k0 x0, k0 x1; k1 x0, k1 x1]; (K xᵀ) P columns:
        // col 0 = K (x · P[:,0]) but easier: P_new = (P − K (x P)ᵀ) / λ
        double newP00 = (p00 - k0 * pX0) / forgetting;
        double newP01 = (p01 - k0 * pX1) / forgetting;
        double newP11 = (p11 - k1 * pX1) / forgetting;
        this.p00 = newP00;
        this.p01 = newP01;
        this.p11 = newP11;

        this.lastResidual = err;
        this.sampleCount++;
    }

    /** Thermal resistance R [K/W] = θ₁ / θ₀, undefined if θ₀ ≤ 0. */
    public double r() {
        return theta0 > 0 ? theta1 / theta0 : Double.NaN;
    }

    /** Thermal capacitance C [J/K] = 1 / θ₁, undefined if θ₁ ≤ 0. */
    public double c() {
        return theta1 > 0 ? 1.0 / theta1 : Double.NaN;
    }

    public double rcSeconds() {
        if (theta0 <= 0) {
            return Double.NaN;
        }
        return 1.0 / theta0;
    }

    public double lastResidual() {
        return lastResidual;
    }

    public long sampleCount() {
        return sampleCount;
    }

    public double theta0() {
        return theta0;
    }

    public double theta1() {
        return theta1;
    }
}

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

import java.util.Arrays;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Forward dynamic-programming heat-pump scheduler.
 *
 * <p>
 * Given a learned thermal model (R, C), forecasts of outdoor temperature
 * and electricity tariff over the next N hours, and a target indoor temp
 * with deadband, find the on/off conditioning schedule that minimises cost
 * subject to T_in ≥ target − deadband (heating) or T_in ≤ target + deadband
 * (cooling) at every hour.
 *
 * <p>
 * State space: T_in discretised into bins; 70 bins × 24 hours × 2
 * actions = 3360 DP evaluations per plan. Fast.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ThermalPlanner {

    private static final double T_BIN_STEP_C = 0.2;
    private static final double T_WINDOW_BELOW_C = 2.0;
    private static final double T_WINDOW_ABOVE_C = 5.0;
    private static final double INFEASIBLE = 1e18;

    public record Plan(int[] action, double[] expectedT, double totalCost) {
    }

    /**
     * Compute optimal hour-by-hour heating plan.
     *
     * @param tInNow current indoor T (°C)
     * @param targetT user target T (°C)
     * @param deadbandC allowed undershoot below target before forcing heat
     * @param tOutForecast outdoor T per future hour (length = N hours)
     * @param tariffPrices €/kWh per future hour (length = N hours)
     * @param r thermal resistance K/W
     * @param c thermal capacitance J/K
     * @param heatPowerW heat pump electrical input when ON (W); thermal output assumed COP×P
     * @param cop coefficient of performance for the heat pump
     * @param cooling false = heating (keep T above floor); true = cooling (keep T below ceiling)
     * @return optimal plan
     */
    public static Plan plan(double tInNow, double targetT, double deadbandC, double[] tOutForecast,
            double[] tariffPrices, double r, double c, double heatPowerW, double cop, boolean cooling) {
        int n = Math.min(tOutForecast.length, tariffPrices.length);
        if (n == 0 || !Double.isFinite(r) || !Double.isFinite(c) || r <= 0 || c <= 0) {
            return new Plan(new int[0], new double[0], 0);
        }
        // More headroom on the side we drive toward: down for cooling, up for heating.
        double tMin = cooling ? targetT - T_WINDOW_ABOVE_C : targetT - T_WINDOW_BELOW_C;
        double tMax = cooling ? targetT + T_WINDOW_BELOW_C : targetT + T_WINDOW_ABOVE_C;
        int nBins = (int) Math.ceil((tMax - tMin) / T_BIN_STEP_C) + 1;

        // cost[h][b]: minimum cost to reach state-bin b at hour h (start of hour h)
        double[][] cost = new double[n + 1][nBins];
        // prevAction[h][b]: action taken at hour h-1 to reach (h, b)
        int[][] prevAction = new int[n + 1][nBins];
        int[][] prevBin = new int[n + 1][nBins];

        for (double[] row : cost) {
            Arrays.fill(row, INFEASIBLE);
        }
        int startBin = clamp(tempToBin(tInNow, tMin), 0, nBins - 1);
        cost[0][startBin] = 0;

        double heatThermalW = heatPowerW * cop;

        for (int h = 0; h < n; h++) {
            double tariff = tariffPrices[h];
            double tOut = tOutForecast[h];
            for (int b = 0; b < nBins; b++) {
                if (cost[h][b] >= INFEASIBLE) {
                    continue;
                }
                double tIn = binToTemp(b, tMin);

                for (int a = 0; a <= 1; a++) {
                    // ON drives temperature up (heating) or down (cooling).
                    double q = (a == 1) ? (cooling ? -heatThermalW : heatThermalW) : 0;
                    double dTdt = ((tOut - tIn) / r + q) / c;
                    double tInNext = tIn + dTdt * 3600.0;
                    // Comfort constraint at end of hour: heating floor / cooling ceiling.
                    if (cooling ? (tInNext > targetT + deadbandC) : (tInNext < targetT - deadbandC)) {
                        continue;
                    }
                    int nextBin = clamp(tempToBin(tInNext, tMin), 0, nBins - 1);
                    double stepCost = (a == 1) ? heatPowerW / 1000.0 * tariff : 0;
                    double total = cost[h][b] + stepCost;
                    if (total < cost[h + 1][nextBin]) {
                        cost[h + 1][nextBin] = total;
                        prevAction[h + 1][nextBin] = a;
                        prevBin[h + 1][nextBin] = b;
                    }
                }
            }
        }

        // Find best terminal state
        int bestB = 0;
        double bestCost = INFEASIBLE;
        for (int b = 0; b < nBins; b++) {
            if (cost[n][b] < bestCost) {
                bestCost = cost[n][b];
                bestB = b;
            }
        }
        if (bestCost >= INFEASIBLE) {
            // Infeasible — heat as much as possible
            int[] act = new int[n];
            Arrays.fill(act, 1);
            double[] exp = new double[n];
            return new Plan(act, exp, INFEASIBLE);
        }

        // Backtrack to recover action sequence
        int[] act = new int[n];
        double[] exp = new double[n];
        int b = bestB;
        for (int h = n; h > 0; h--) {
            act[h - 1] = prevAction[h][b];
            exp[h - 1] = binToTemp(b, tMin);
            b = prevBin[h][b];
        }
        return new Plan(act, exp, bestCost);
    }

    private static int tempToBin(double t, double tMin) {
        return (int) Math.round((t - tMin) / T_BIN_STEP_C);
    }

    private static double binToTemp(int b, double tMin) {
        return tMin + b * T_BIN_STEP_C;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}

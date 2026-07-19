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

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.core.CapabilityCheck;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.Controller;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;

/**
 * Solar-surplus boiler dispatcher. Switches the boiler on opportunistically
 * when there is enough solar excess (a scheduled force-on window is handled
 * separately by {@link BoilerScheduleController}).
 *
 * <p>
 * Logic each tick:
 * <ol>
 * <li>If user override active ({@code EMS_Boiler_User_Override == ON}),
 * skip — user owns the boiler today.</li>
 * <li>Compute the on-threshold from the cloudiness forecast: gloomy
 * (&gt;70 %) → 500 W; sunny (&lt;20 %) → 2500 W; default → 2000 W.</li>
 * <li>If battery is below its reserve floor, add +1500 W to the threshold —
 * don't steal from the battery's recharge.</li>
 * <li>If the 5-min avg grid (export +, import −) exceeds the threshold AND
 * boiler is OFF AND no plugged ECO car wants more current → emit
 * boiler ON.</li>
 * <li>If 5-min avg grid &lt; −1000 W AND boiler is ON → emit boiler OFF.</li>
 * </ol>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class SolarSurplusDispatcher implements Controller {

    public static final String NAME = "solar-surplus-dispatcher";

    private final boolean shadowMode;
    private final @Nullable BoilerPlanController boilerPlan;

    // Diagnostic state for the bridge to surface as channels.
    private volatile int lastOnThresholdW = SURPLUS_DEFAULT_ON_THRESHOLD_W;

    public SolarSurplusDispatcher(boolean shadowMode, @Nullable BoilerPlanController boilerPlan) {
        this.shadowMode = shadowMode;
        this.boilerPlan = boilerPlan;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIO_SOLAR_SURPLUS;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean shadowMode() {
        return shadowMode;
    }

    /** Latest computed on-threshold (for bridge channel). */
    public int lastOnThresholdW() {
        return lastOnThresholdW;
    }

    @Override
    public List<SetpointRequest> evaluate(EnergyContext ctx) {
        // Step 1 — user override.
        if (ctx.boilerUserOverride()) {
            return List.of();
        }

        // Step 2 — cloudiness-adaptive on-threshold.
        double cloudiness = ctx.cloudinessTodayPct();
        int onThreshold;
        if (!Double.isNaN(cloudiness) && cloudiness > 70.0) {
            onThreshold = SURPLUS_GLOOMY_ON_THRESHOLD_W;
        } else if (!Double.isNaN(cloudiness) && cloudiness < 20.0) {
            onThreshold = SURPLUS_SUNNY_ON_THRESHOLD_W;
        } else {
            onThreshold = SURPLUS_DEFAULT_ON_THRESHOLD_W;
        }
        if (ctx.batteryBelowReserve()) {
            onThreshold += SURPLUS_BELOW_RESERVE_PENALTY_W;
        }
        this.lastOnThresholdW = onThreshold;

        double avg5min = ctx.gridLoad5minAvgW();
        if (Double.isNaN(avg5min)) {
            // Not enough history yet — don't act.
            return List.of();
        }

        // Step 4 — surplus → consider turning on.
        if (avg5min > onThreshold && !ctx.boilerOn()) {
            if (anyEcoCarWantsMoreCurrent(ctx)) {
                // Defer to the EV — let the surplus go to the car first.
                return List.of();
            }
            return List.of(new SetpointRequest(ASSET_BOILER, SetpointRequest.Kind.ONOFF, 1.0, priority(), NAME,
                    String.format("surplus %.0fW > %dW (cloud=%.0f%%, belowReserve=%s)", avg5min, onThreshold,
                            cloudiness, ctx.batteryBelowReserve())));
        }

        // Step 5 — significant import → boiler off. But NOT while the DHW plan is
        // actively topping up at cheap hours: the boiler's own draw IS the import,
        // and turning it off here would oscillate the relay. BoilerPlan runs at a
        // lower priority, so its decision for this tick is already set.
        if (avg5min < SURPLUS_OFF_THRESHOLD_W && ctx.boilerOn()) {
            BoilerPlanController bp = boilerPlan;
            if (bp != null && !bp.shadowMode() && bp.wantsBoilerOn()) {
                return List.of();
            }
            return List.of(new SetpointRequest(ASSET_BOILER, SetpointRequest.Kind.ONOFF, 0.0, priority(), NAME,
                    String.format("5-min avg %.0fW < %dW — boiler off", avg5min, SURPLUS_OFF_THRESHOLD_W)));
        }

        return List.of();
    }

    /**
     * Any plugged ECO car whose current limit is below MAX gets first dibs on
     * the surplus, so EV charging is preferred over heating the boiler.
     */
    private boolean anyEcoCarWantsMoreCurrent(EnergyContext ctx) {
        for (CarSnapshot car : ctx.cars().values()) {
            if (!car.cableConnected()) {
                continue;
            }
            if (car.mode() != CarSnapshot.Mode.ECO) {
                continue;
            }
            if (car.currentLimitA() > 0 && car.currentLimitA() < CapabilityCheck.MAX_CHARGING_CURRENT_A) {
                return true;
            }
        }
        return false;
    }
}

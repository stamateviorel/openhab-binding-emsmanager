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
package org.openhab.binding.emsmanager.internal.bridge;

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.types.Command;

/**
 * Translates a command on a setpoint channel into the Thing-configuration change it stands for.
 *
 * Kept separate from the handler so the mapping - which channel writes which key, and what happens
 * to a value outside its range - can be tested without a framework around it.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class BridgeSetpoints {

    /** A configuration key and the value to write to it. */
    public record Setting(String key, Object value) {
    }

    private BridgeSetpoints() {
    }

    public static Optional<Setting> resolve(String channelId, Command command) {
        switch (channelId) {
            case CHANNEL_SHADOW_MODE:
                return command instanceof OnOffType onOff
                        ? Optional.of(new Setting("shadowMode", onOff == OnOffType.ON))
                        : Optional.empty();
            case CHANNEL_SET_BOILER_TARGET_KWH:
                return number(command).map(v -> new Setting("boilerDailyTargetKwh", clamp(v, 0, 30)));
            case CHANNEL_SET_BOILER_READY_BY_HOUR:
                return number(command).map(v -> new Setting("boilerReadyByHour", (int) clamp(v, 0, 23)));
            case CHANNEL_SET_GRID_SAFETY_MARGIN_W:
                return number(command).map(v -> new Setting("gridSafetyMarginW", (int) clamp(v, 0, 3000)));
            case CHANNEL_SET_CAPACITY_BUDGET_W:
                return number(command).map(v -> new Setting("capacityMinBillableW", (int) clamp(v, 0, 15000)));
            default:
                return Optional.empty();
        }
    }

    /** Sliders may send a plain number or a quantity; the configuration key holds neither unit. */
    static Optional<Double> number(Command command) {
        if (command instanceof QuantityType<?> q) {
            return Optional.of(q.doubleValue());
        }
        if (command instanceof DecimalType d) {
            return Optional.of(d.doubleValue());
        }
        try {
            return Optional.of(Double.parseDouble(command.toString().split(" ")[0]));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Out-of-range values are pulled into range rather than rejected: these keys feed breaker and
     * peak arithmetic, where a negative or absurd figure is worse than a clamped one.
     */
    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** The configuration keys this class may write, for a handler that wants to publish them back. */
    public static Map<String, String> channelsByConfigKey() {
        return Map.of("shadowMode", CHANNEL_SHADOW_MODE, "boilerDailyTargetKwh", CHANNEL_SET_BOILER_TARGET_KWH,
                "boilerReadyByHour", CHANNEL_SET_BOILER_READY_BY_HOUR, "gridSafetyMarginW",
                CHANNEL_SET_GRID_SAFETY_MARGIN_W, "capacityMinBillableW", CHANNEL_SET_CAPACITY_BUDGET_W);
    }
}

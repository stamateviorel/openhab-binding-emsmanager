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

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.time.ZonedDateTime;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.config.HeatPumpConfig;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thing handler for {@code emsmanager:heatpump}. A pure data holder — the
 * actual decisions are computed by {@link
 * org.openhab.binding.emsmanager.internal.controller.dispatch.HeatPumpOptimizerController},
 * which discovers heatpump Things via the ThingRegistry and calls
 * {@link #publishDecision} each tick.
 *
 * <p>
 * This is the upstream-publishable equivalent of {@code DeviceMeterHandler}
 * — config-only Thing that gives users an "out of the box" way to attach
 * their heat pump's metering items to the binding.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class HeatPumpAssetHandler extends BaseThingHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeatPumpAssetHandler.class);

    public HeatPumpAssetHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        HeatPumpConfig cfg = getConfigAs(HeatPumpConfig.class);
        updateStatus(ThingStatus.ONLINE);
        LOGGER.info("HeatPump[{}]: initialized — name='{}', SCOP={}, powerItem={}, modeItem={}", heatpumpId(), cfg.name,
                cfg.scopCop, cfg.powerItem, cfg.modeItem);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // All channels read-only.
    }

    public HeatPumpConfig getCfg() {
        return getConfigAs(HeatPumpConfig.class);
    }

    public String heatpumpId() {
        return getThing().getUID().getId();
    }

    /** Called each tick by the optimizer. */
    public void publishDecision(String recommendedMode, String reason, double effectivePriceEur, double dailyKwhEst,
            boolean optimizerActive) {
        updateState(HP_CHANNEL_RECOMMENDED_MODE, new StringType(recommendedMode));
        updateState(HP_CHANNEL_REASON, new StringType(reason));
        if (!Double.isNaN(effectivePriceEur)) {
            updateState(HP_CHANNEL_EFFECTIVE_PRICE_EUR, new DecimalType(effectivePriceEur));
        }
        if (!Double.isNaN(dailyKwhEst)) {
            updateState(HP_CHANNEL_DAILY_KWH, new QuantityType<>(dailyKwhEst, Units.KILOWATT_HOUR));
        }
        updateState(HP_CHANNEL_OPTIMIZER_ACTIVE, OnOffType.from(optimizerActive));
    }

    /** Publish learned thermal-model parameters + DP plan outputs. */
    public void publishModel(double r, double c, double rmse, @Nullable ZonedDateTime preheatAt,
            double costNext24hEur) {
        if (!Double.isNaN(r)) {
            updateState(HP_CHANNEL_MODEL_R, new DecimalType(r));
        }
        if (!Double.isNaN(c)) {
            updateState(HP_CHANNEL_MODEL_C, new DecimalType(c));
        }
        if (!Double.isNaN(rmse)) {
            updateState(HP_CHANNEL_MODEL_RMSE, new DecimalType(rmse));
        }
        if (preheatAt != null) {
            updateState(HP_CHANNEL_PLAN_PREHEAT_AT, new DateTimeType(preheatAt));
        }
        if (!Double.isNaN(costNext24hEur)) {
            updateState(HP_CHANNEL_PLAN_COST_24H, new DecimalType(costNext24hEur));
        }
    }
}

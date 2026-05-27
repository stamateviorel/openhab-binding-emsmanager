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
package org.openhab.binding.emsmanager.internal.charger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.config.ChargerConfig;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thing handler for {@code emsmanager:charger}. A config-only data holder,
 * like {@code DeviceMeterHandler} / {@code HeatPumpAssetHandler}. The EV
 * coordinator + charging-plan controllers discover charger Things via the
 * ThingRegistry; the bridge builds a {@code ChargerAssetHandler} per
 * charger for the write path.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ChargerHandler extends BaseThingHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChargerHandler.class);

    public ChargerHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        ChargerConfig cfg = getCfg();
        updateStatus(ThingStatus.ONLINE);
        LOGGER.info("Charger[{}]: initialized — name='{}', carKey={}, mode={}, cable={}, currentLimit={}", chargerId(),
                cfg.name, carKey(), cfg.modeItem, cfg.cableItem, cfg.currentLimitItem);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // No writable channels; the coordinator writes the configured items directly.
    }

    public ChargerConfig getCfg() {
        return getConfigAs(ChargerConfig.class);
    }

    public String chargerId() {
        return getThing().getUID().getId();
    }

    /** Snapshot key — config carKey if set, else the Thing id. */
    public String carKey() {
        ChargerConfig cfg = getCfg();
        String key = cfg.carKey;
        return (key == null || key.isBlank()) ? chargerId() : key;
    }
}

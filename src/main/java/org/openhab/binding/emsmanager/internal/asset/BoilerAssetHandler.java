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
package org.openhab.binding.emsmanager.internal.asset;

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointDedupe;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.OnOffType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Boiler ON/OFF dispatcher. Commands the configured boiler switch item.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class BoilerAssetHandler implements AssetHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(BoilerAssetHandler.class);

    private final EventPublisher eventPublisher;
    private final String boilerItemName;
    private final SetpointDedupe dedupe = new SetpointDedupe();

    public BoilerAssetHandler(EventPublisher eventPublisher, String boilerItemName) {
        this.eventPublisher = eventPublisher;
        this.boilerItemName = boilerItemName;
    }

    @Override
    public String assetId() {
        return ASSET_BOILER;
    }

    @Override
    public boolean apply(SetpointRequest req, EnergyContext ctx, boolean shadow) {
        if (req.kind() != SetpointRequest.Kind.ONOFF) {
            LOGGER.warn("BoilerAssetHandler: unsupported kind {} from {}", req.kind(), req.controllerName());
            return false;
        }
        boolean wantOn = req.value() >= 0.5;
        String desired = wantOn ? "ON" : "OFF";
        String current = ctx.boilerOn() ? "ON" : "OFF";
        long now = System.currentTimeMillis();

        if (!dedupe.shouldSend(boilerItemName, desired, current, now)) {
            return false;
        }
        if (shadow) {
            LOGGER.info("[SHADOW] would write {} ← {} ({}: {})", boilerItemName, desired, req.controllerName(),
                    req.reason());
            return false;
        }
        eventPublisher.post(ItemEventFactory.createCommandEvent(boilerItemName, OnOffType.from(wantOn)));
        dedupe.markSent(boilerItemName, desired, now);
        LOGGER.info("BoilerAssetHandler: sent {} ← {} ({}: {})", boilerItemName, desired, req.controllerName(),
                req.reason());
        return true;
    }
}

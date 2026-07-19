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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.core.CarSnapshot;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointDedupe;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-car charger dispatcher. Handles PAUSE, AMPS (current-limit) and
 * CHARGE_START requests by writing the configured per-car control items.
 *
 * <p>
 * One instance per car. Single {@link SetpointDedupe} tracking all three
 * items (pause, current-limit, charging) by item name. The 15-second ACK
 * window accommodates OCPP control items that use {@code autoupdate="false"}
 * and only reflect a new state once the binding ACKs the command.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ChargerAssetHandler implements AssetHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChargerAssetHandler.class);

    private final EventPublisher eventPublisher;
    private final String carKey;
    private final String pauseItemName;
    private final String currentLimitItemName;
    private final String chargingItemName;
    private final SetpointDedupe dedupe = new SetpointDedupe();

    /**
     * Explicit-item-names constructor. The resolved per-car write-item names
     * are passed in by the bridge handler (from the {@code car%d}-pattern
     * config) or by a {@code emsmanager:charger} Thing. Null items are
     * replaced with empty strings; writes to an empty item name are skipped.
     */
    public ChargerAssetHandler(EventPublisher eventPublisher, String carKey,
            @org.eclipse.jdt.annotation.Nullable String pauseItemName,
            @org.eclipse.jdt.annotation.Nullable String currentLimitItemName,
            @org.eclipse.jdt.annotation.Nullable String chargingItemName) {
        this.eventPublisher = eventPublisher;
        this.carKey = carKey;
        this.pauseItemName = pauseItemName == null ? "" : pauseItemName;
        this.currentLimitItemName = currentLimitItemName == null ? "" : currentLimitItemName;
        this.chargingItemName = chargingItemName == null ? "" : chargingItemName;
    }

    @Override
    public String assetId() {
        return carKey;
    }

    @Override
    public boolean apply(SetpointRequest req, EnergyContext ctx, boolean shadow) {
        CarSnapshot car = ctx.cars().get(carKey);
        switch (req.kind()) {
            case PAUSE:
                return applyPause(req, car, shadow);
            case AMPS:
                return applyAmps(req, car, shadow);
            case CHARGE_START:
                return applyChargeStart(req, car, shadow);
            default:
                LOGGER.warn("ChargerAssetHandler[{}]: unsupported kind {} from {}", carKey, req.kind(),
                        req.controllerName());
                return false;
        }
    }

    private boolean applyPause(SetpointRequest req, @org.eclipse.jdt.annotation.Nullable CarSnapshot car,
            boolean shadow) {
        if (pauseItemName.isBlank()) {
            return false;
        }
        boolean wantPaused = req.value() >= 0.5;
        String desired = wantPaused ? "ON" : "OFF";
        String current = (car != null && car.paused()) ? "ON" : "OFF";
        long now = System.currentTimeMillis();
        if (!dedupe.shouldSend(pauseItemName, desired, current, now)) {
            return false;
        }
        if (shadow) {
            LOGGER.info("[SHADOW] would write {} ← {} ({}: {})", pauseItemName, desired, req.controllerName(),
                    req.reason());
            return false;
        }
        eventPublisher.post(ItemEventFactory.createCommandEvent(pauseItemName, OnOffType.from(wantPaused)));
        dedupe.markSent(pauseItemName, desired, now);
        LOGGER.info("ChargerAssetHandler[{}]: sent {} ← {} ({}: {})", carKey, pauseItemName, desired,
                req.controllerName(), req.reason());
        return true;
    }

    private boolean applyAmps(SetpointRequest req, @org.eclipse.jdt.annotation.Nullable CarSnapshot car,
            boolean shadow) {
        if (currentLimitItemName.isBlank()) {
            return false;
        }
        int amps = (int) Math.round(req.value());
        String desired = String.valueOf(amps);
        String current = (car != null) ? String.valueOf((int) Math.round(car.currentLimitA())) : "0";
        long now = System.currentTimeMillis();
        if (!dedupe.shouldSend(currentLimitItemName, desired, current, now)) {
            return false;
        }
        if (shadow) {
            LOGGER.info("[SHADOW] would write {} ← {} A ({}: {})", currentLimitItemName, amps, req.controllerName(),
                    req.reason());
            return false;
        }
        eventPublisher.post(ItemEventFactory.createCommandEvent(currentLimitItemName, new DecimalType(amps)));
        dedupe.markSent(currentLimitItemName, desired, now);
        LOGGER.info("ChargerAssetHandler[{}]: sent {} ← {} A ({}: {})", carKey, currentLimitItemName, amps,
                req.controllerName(), req.reason());
        return true;
    }

    private boolean applyChargeStart(SetpointRequest req, @org.eclipse.jdt.annotation.Nullable CarSnapshot car,
            boolean shadow) {
        if (chargingItemName.isBlank()) {
            return false;
        }
        // We use this item's last-sent state as the dedupe; the OCPP binding sets
        // it to OFF after a Charging cycle ends. autoupdate=false → state lags.
        String desired = "ON";
        // We can't directly read the item's current state from the car snapshot,
        // so we rely entirely on the dedupe's ACK-window memo.
        String current = "UNKNOWN";
        long now = System.currentTimeMillis();
        if (!dedupe.shouldSend(chargingItemName, desired, current, now)) {
            return false;
        }
        if (shadow) {
            LOGGER.info("[SHADOW] would write {} ← ON ({}: {})", chargingItemName, req.controllerName(), req.reason());
            return false;
        }
        eventPublisher.post(ItemEventFactory.createCommandEvent(chargingItemName, OnOffType.ON));
        dedupe.markSent(chargingItemName, desired, now);
        LOGGER.info("ChargerAssetHandler[{}]: sent {} ← ON ({}: {})", carKey, chargingItemName, req.controllerName(),
                req.reason());
        return true;
    }
}

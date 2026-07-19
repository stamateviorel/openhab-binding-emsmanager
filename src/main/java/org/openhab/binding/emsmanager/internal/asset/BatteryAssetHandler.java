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
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.config.BatteryConfig;
import org.openhab.binding.emsmanager.internal.core.EnergyContext;
import org.openhab.binding.emsmanager.internal.core.SetpointDedupe;
import org.openhab.binding.emsmanager.internal.core.SetpointRequest;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.DecimalType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Battery setpoint dispatcher. Behaviour depends on
 * {@link BatteryConfig#controlMode}:
 * <ul>
 * <li><b>auto</b>: clamp the request to [minSetpointW, maxSetpointW] and
 * send it as a command to {@code setpointItemName}. Dedupe by value.</li>
 * <li><b>fixed</b>: log + reject — EMS isn't allowed to drive in fixed mode.</li>
 * <li><b>readonly</b>: log + reject — the inverter doesn't accept writes.</li>
 * </ul>
 *
 * <p>
 * Defaults to {@code readonly} for sites without a writable inverter item.
 * The controller still emits decisions; this handler quietly swallows them so
 * the rest of the binding can be exercised with no real effect. When a writable
 * inverter is available, change the Thing config to {@code auto} + set
 * {@code setpointItemName}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class BatteryAssetHandler implements AssetHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatteryAssetHandler.class);

    private final EventPublisher eventPublisher;
    private final BatteryConfig config;
    private final SetpointDedupe dedupe = new SetpointDedupe();

    public BatteryAssetHandler(EventPublisher eventPublisher, BatteryConfig config) {
        this.eventPublisher = eventPublisher;
        this.config = config;
    }

    @Override
    public String assetId() {
        return ASSET_BATTERY;
    }

    public String controlMode() {
        return config.controlMode;
    }

    @Override
    public boolean apply(SetpointRequest req, EnergyContext ctx, boolean shadow) {
        if (req.kind() != SetpointRequest.Kind.WATTS_BATTERY) {
            LOGGER.warn("BatteryAssetHandler: unsupported kind {} from {}", req.kind(), req.controllerName());
            return false;
        }

        switch (config.controlMode) {
            case "auto":
                return applyAuto(req, shadow);
            case "fixed":
            case "readonly":
            default:
                if (shadow) {
                    LOGGER.info("[SHADOW][battery:{}] {} → would set {} W ({})", config.controlMode,
                            req.controllerName(), Math.round(req.value()), req.reason());
                } else {
                    LOGGER.debug("[NO-OP][battery:{}] {} → would set {} W ({}) — controlMode rejects writes",
                            config.controlMode, req.controllerName(), Math.round(req.value()), req.reason());
                }
                return false;
        }
    }

    private boolean applyAuto(SetpointRequest req, boolean shadow) {
        @Nullable
        String item = config.setpointItemName;
        if (item == null || item.isBlank()) {
            LOGGER.warn("BatteryAssetHandler: controlMode=auto but setpointItemName not configured — rejecting write");
            return false;
        }
        int target = (int) Math.round(req.value());
        if (target < config.minSetpointW) {
            target = config.minSetpointW;
        }
        if (target > config.maxSetpointW) {
            target = config.maxSetpointW;
        }
        String desired = String.valueOf(target);
        // We can't easily read the current item state here (would need ItemRegistry — small future improvement);
        // rely on the dedupe ACK-window memo only.
        String current = "UNKNOWN";
        long now = System.currentTimeMillis();
        if (!dedupe.shouldSend(item, desired, current, now)) {
            return false;
        }
        if (shadow) {
            LOGGER.info("[SHADOW][battery:auto] would write {} ← {} W ({}: {})", item, target, req.controllerName(),
                    req.reason());
            return false;
        }
        eventPublisher.post(ItemEventFactory.createCommandEvent(item, new DecimalType(target)));
        dedupe.markSent(item, desired, now);
        LOGGER.info("BatteryAssetHandler: sent {} ← {} W ({}: {})", item, target, req.controllerName(), req.reason());
        return true;
    }
}

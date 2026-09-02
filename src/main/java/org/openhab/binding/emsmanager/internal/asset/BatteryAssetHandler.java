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
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Battery setpoint dispatcher. Behaviour depends on
 * {@link BatteryConfig#controlMode}:
 * <ul>
 * <li><b>auto</b>: clamp the request to [minSetpointW, maxSetpointW] and
 * send it as a command to {@code setpointItemName}.</li>
 * <li><b>fixed</b>: ignore the request's value and hold the item at
 * {@code fixedSetpointW} (clamped the same way).</li>
 * <li><b>readonly</b>: log + reject — the inverter doesn't accept writes.</li>
 * </ul>
 *
 * <p>
 * A value is sent once; it is only re-sent when the item's state (read
 * through the registry when one is wired) disagrees with it after the ACK
 * window. Without a registry the last value sent stands in for the state.
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
    private final @Nullable ItemRegistry itemRegistry;
    private final SetpointDedupe dedupe;
    private @Nullable String lastSent = null;

    public BatteryAssetHandler(EventPublisher eventPublisher, BatteryConfig config) {
        this(eventPublisher, config, null);
    }

    public BatteryAssetHandler(EventPublisher eventPublisher, BatteryConfig config,
            @Nullable ItemRegistry itemRegistry) {
        this(eventPublisher, config, itemRegistry, new SetpointDedupe());
    }

    /** Visible for testing: lets a test shrink the ACK window. */
    BatteryAssetHandler(EventPublisher eventPublisher, BatteryConfig config, @Nullable ItemRegistry itemRegistry,
            SetpointDedupe dedupe) {
        this.eventPublisher = eventPublisher;
        this.config = config;
        this.itemRegistry = itemRegistry;
        this.dedupe = dedupe;
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
                return write((int) Math.round(req.value()), req, shadow);
            case "fixed":
                return write((int) Math.round(config.fixedSetpointW), req, shadow);
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

    private boolean write(int requestedW, SetpointRequest req, boolean shadow) {
        @Nullable
        String item = config.setpointItemName;
        if (item == null || item.isBlank()) {
            LOGGER.warn("BatteryAssetHandler: controlMode={} but setpointItemName not configured — rejecting write",
                    config.controlMode);
            return false;
        }
        int target = Math.max(config.minSetpointW, Math.min(config.maxSetpointW, requestedW));
        String desired = String.valueOf(target);
        String previous = lastSent;
        String fromItem = itemStateW(item);
        String current = fromItem != null ? fromItem : (previous != null ? previous : "UNKNOWN");
        long now = System.currentTimeMillis();
        if (!dedupe.shouldSend(item, desired, current, now)) {
            return false;
        }
        String why = "fixed".equals(config.controlMode) ? "fixed setpoint" : req.reason();
        if (shadow) {
            LOGGER.info("[SHADOW][battery:{}] would write {} ← {} W ({}: {})", config.controlMode, item, target,
                    req.controllerName(), why);
            return false;
        }
        eventPublisher.post(ItemEventFactory.createCommandEvent(item, new DecimalType(target)));
        dedupe.markSent(item, desired, now);
        lastSent = desired;
        LOGGER.info("BatteryAssetHandler: sent {} ← {} W ({}: {})", item, target, req.controllerName(), why);
        return true;
    }

    /** The setpoint item's numeric state as an integer string, or null when there is no registry / no number. */
    private @Nullable String itemStateW(String itemName) {
        ItemRegistry registry = itemRegistry;
        if (registry == null) {
            return null;
        }
        Item item = registry.get(itemName);
        if (item == null) {
            return null;
        }
        State state = item.getState();
        if (state instanceof QuantityType<?> q) {
            return String.valueOf(q.intValue());
        }
        if (state instanceof DecimalType d) {
            return String.valueOf(d.intValue());
        }
        return null;
    }
}

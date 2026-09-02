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
package org.openhab.binding.emsmanager.internal.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants;
import org.openhab.binding.emsmanager.internal.config.DeviceMeterConfig;
import org.openhab.binding.emsmanager.internal.config.EmsBridgeConfig;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;

/**
 * What the dashboard knows about the site, read from the Things the engine itself runs on.
 * <p>
 * The engine is configured with the grid, solar, battery and house Items and the site's device
 * meters are Things of this binding, so the dashboard has no reason to guess names: it asks the
 * Thing registry and draws what the engine is looking at.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class SiteModel {

    /** One metered circuit: the live power Item the device-meter Thing publishes, and how to show it. */
    public record Circuit(String powerItem, String label, String category, String colour) {
    }

    /** Per-car Item names the engine reads, derived from the bridge's own patterns. */
    public record Car(int number, String modeItem, String statusItem, String cableItem, String powerWItem,
            String powerKwItem, String currentLimitItem, String planPrefix) {
    }

    private final @Nullable EmsBridgeConfig bridge;
    private final List<Circuit> circuits;
    private final List<Car> cars;

    private SiteModel(@Nullable EmsBridgeConfig bridge, List<Circuit> circuits, List<Car> cars) {
        this.bridge = bridge;
        this.circuits = circuits;
        this.cars = cars;
    }

    /** A site the dashboard knows nothing about: no bridge, no circuits. */
    public static SiteModel empty() {
        return new SiteModel(null, List.of(), List.of());
    }

    public static SiteModel from(@Nullable ThingRegistry things, @Nullable ItemChannelLinkRegistry links) {
        if (things == null) {
            return empty();
        }
        EmsBridgeConfig bridge = null;
        List<Circuit> circuits = new ArrayList<>();
        for (Thing thing : things.getAll()) {
            if (EmsManagerBindingConstants.THING_TYPE_BRIDGE.equals(thing.getThingTypeUID()) && bridge == null) {
                bridge = thing.getConfiguration().as(EmsBridgeConfig.class);
            } else if (EmsManagerBindingConstants.THING_TYPE_DEVICE_METER.equals(thing.getThingTypeUID())) {
                Circuit c = circuitOf(thing, links);
                if (c != null) {
                    circuits.add(c);
                }
            }
        }
        circuits.sort((a, b) -> {
            int byCategory = a.category().compareTo(b.category());
            return byCategory != 0 ? byCategory : a.label().compareToIgnoreCase(b.label());
        });
        List<Car> cars = new ArrayList<>();
        if (bridge != null) {
            for (int n = 1; n <= Math.max(0, bridge.carCount); n++) {
                cars.add(new Car(n, String.format(bridge.carModeItemPattern, n),
                        String.format(bridge.carStatusItemPattern, n), String.format(bridge.carCableItemPattern, n),
                        String.format(bridge.carPowerOcppItemPattern, n),
                        String.format(bridge.carPowerKwItemPattern, n),
                        String.format(bridge.carCurrentLimitItemPattern, n),
                        String.format(bridge.carPlanItemPrefixPattern, n)));
            }
        }
        return new SiteModel(bridge, Collections.unmodifiableList(circuits), Collections.unmodifiableList(cars));
    }

    /** The Item linked to the Thing's live-power channel, which is what the flow draws from. */
    private static @Nullable Circuit circuitOf(Thing thing, @Nullable ItemChannelLinkRegistry links) {
        if (links == null) {
            return null;
        }
        Set<String> linked = links.getLinkedItemNames(new ChannelUID(thing.getUID(), "currentW"));
        if (linked.isEmpty()) {
            return null;
        }
        DeviceMeterConfig cfg = thing.getConfiguration().as(DeviceMeterConfig.class);
        String label = thing.getLabel();
        if (label == null || label.isBlank()) {
            label = cfg.name;
        }
        return new Circuit(linked.iterator().next(), label, cfg.category, cfg.color);
    }

    public boolean hasBridge() {
        return bridge != null;
    }

    public @Nullable EmsBridgeConfig bridge() {
        return bridge;
    }

    public List<Circuit> circuits() {
        return circuits;
    }

    public List<Car> cars() {
        return cars;
    }
}

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
package org.openhab.binding.emsmanager.internal.discovery;

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.DiscoveryService;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auto-discovery service.
 *
 * <p>
 * Scans the {@link ItemRegistry} for items that match known EMS-relevant
 * patterns and emits {@link DiscoveryResult}s into the inbox so the user can
 * one-click create the corresponding Things. This is the single biggest
 * adoption-barrier reduction: instead of having to read a setup plan and
 * write .things files, a new user opens "Search for things → EMS Manager"
 * and sees their devices already pre-populated.
 *
 * <p>
 * Patterns recognised:
 * <ul>
 * <li>{@code Grid_Power} OR {@code *_grid_power_W} → suggest a bridge Thing
 * at {@code emsmanager:bridge:auto}.</li>
 * <li>{@code <name>_Power} → suggest a device-meter Thing
 * at {@code emsmanager:device-meter:<name>}, pre-filled with the
 * discovered powerItem.</li>
 * <li>{@code Heatpump_*_Power_W} or {@code HeatPump_*_Power} → suggest a
 * heat-pump Thing at {@code emsmanager:heatpump:<name>}.</li>
 * </ul>
 *
 * <p>
 * Background scan runs every 5 minutes; on-demand scan honours the
 * 10-second timeout {@code AbstractDiscoveryService} demands.
 *
 * @author Stamate Viorel - Initial contribution
 */
@Component(service = DiscoveryService.class, configurationPid = "discovery.emsmanager")
@NonNullByDefault
public final class EmsAutoDiscoveryService extends AbstractDiscoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmsAutoDiscoveryService.class);
    private static final int DISCOVER_TIMEOUT_SEC = 10;
    private static final long BACKGROUND_SCAN_INTERVAL_SEC = 300;

    private static final Set<org.openhab.core.thing.ThingTypeUID> SUPPORTED = Set.of(THING_TYPE_BRIDGE,
            THING_TYPE_DEVICE_METER, THING_TYPE_HEATPUMP);

    // Patterns. Named groups make the suggested ID extraction trivial.
    // A generic power item is anything ending in "_Power"; the prefix is taken as the device id.
    private static final Pattern ENERGY_ITEM = Pattern.compile("^(?<id>[a-zA-Z0-9_]+)_Power$");
    private static final Pattern HEATPUMP_POWER = Pattern.compile("^Heat[Pp]ump_(?<id>[a-zA-Z0-9_]*)_?Power(?:_W)?$");

    private @Nullable ItemRegistry itemRegistry;
    private @Nullable ScheduledFuture<?> backgroundJob;

    @Activate
    public EmsAutoDiscoveryService() throws IllegalArgumentException {
        super(SUPPORTED, DISCOVER_TIMEOUT_SEC, true);
    }

    @Reference
    public void setItemRegistry(ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    public void unsetItemRegistry(ItemRegistry itemRegistry) {
        this.itemRegistry = null;
    }

    @Override
    protected void startBackgroundDiscovery() {
        LOGGER.debug("EmsAutoDiscoveryService: starting background scan, every {} s", BACKGROUND_SCAN_INTERVAL_SEC);
        ScheduledFuture<?> current = backgroundJob;
        if (current == null || current.isCancelled()) {
            backgroundJob = scheduler.scheduleWithFixedDelay(this::startScan, 5, BACKGROUND_SCAN_INTERVAL_SEC,
                    TimeUnit.SECONDS);
        }
    }

    @Override
    protected void stopBackgroundDiscovery() {
        ScheduledFuture<?> current = backgroundJob;
        if (current != null) {
            current.cancel(true);
            backgroundJob = null;
        }
    }

    @Override
    protected void startScan() {
        ItemRegistry r = itemRegistry;
        if (r == null) {
            LOGGER.debug("EmsAutoDiscoveryService: itemRegistry not yet bound, skipping scan");
            return;
        }
        try {
            Set<String> names = new HashSet<>();
            for (Item item : r.getAll()) {
                names.add(item.getName());
            }
            scanForBridge(names);
            scanForDeviceMeters(names);
            scanForHeatPumps(names);
        } catch (Throwable t) {
            LOGGER.warn("EmsAutoDiscoveryService.startScan: {}", t.toString());
        }
    }

    @Deactivate
    @Override
    protected void deactivate() {
        stopBackgroundDiscovery();
        super.deactivate();
    }

    /** If the user has the grid power item + solar (or house) power item, suggest a bridge. */
    private void scanForBridge(Set<String> names) {
        boolean hasGrid = names.contains(ITEM_GRID_LOAD);
        boolean hasSolarOrHouse = names.contains(ITEM_SOLAR_LOAD) || names.contains(ITEM_HOUSE_LOAD_SUM);
        if (!hasGrid && !hasSolarOrHouse) {
            return;
        }
        ThingUID uid = new ThingUID(THING_TYPE_BRIDGE, "auto");
        Map<String, Object> props = new HashMap<>();
        props.put("tickIntervalSeconds", 5);
        props.put("gridSafetyMarginW", 500);
        props.put("shadowMode", true); // safety — let user flip to live after sanity-check
        props.put("discoveredFrom", "auto"); // unique key for representationProperty
        DiscoveryResult result = DiscoveryResultBuilder.create(uid).withLabel("EMS Manager (auto-detected)")
                .withProperties(props).withRepresentationProperty("discoveredFrom").withThingType(THING_TYPE_BRIDGE)
                .build();
        thingDiscovered(result);
        LOGGER.info("EmsAutoDiscoveryService: suggested bridge Thing (grid + solar/house power found)");
    }

    /** For every <id>_Power item, suggest a device-meter. */
    private void scanForDeviceMeters(Set<String> names) {
        for (String name : names) {
            Matcher m = ENERGY_ITEM.matcher(name);
            if (!m.matches()) {
                continue;
            }
            String id = m.group("id");
            // Skip per-car patterns + the grid/solar/main aggregate meters. Those aren't
            // *device* meters — cars are tracked by the EV coordinator, and grid/solar/main
            // are already top-level energy sources in the EnergyContext.
            if (id.matches("(?i)EVSE[1-9][0-9]*")) {
                continue;
            }
            if ("main".equalsIgnoreCase(id) || "grid".equalsIgnoreCase(id) || "solar".equalsIgnoreCase(id)
                    || "house".equalsIgnoreCase(id) || "battery".equalsIgnoreCase(id)) {
                continue;
            }
            String label = humanize(id);
            ThingUID uid = new ThingUID(THING_TYPE_DEVICE_METER, id);
            Map<String, Object> props = new HashMap<>();
            props.put("name", label);
            props.put("powerItem", name);
            props.put("powerInKw", false);
            props.put("category", guessCategory(id));
            props.put("color", guessColor(id));
            DiscoveryResult result = DiscoveryResultBuilder.create(uid).withLabel("Device — " + label)
                    .withProperties(props).withRepresentationProperty("powerItem")
                    .withThingType(THING_TYPE_DEVICE_METER).build();
            thingDiscovered(result);
        }
    }

    /** For HeatPump_*_Power_W items, suggest a heatpump Thing. */
    private void scanForHeatPumps(Set<String> names) {
        for (String name : names) {
            Matcher m = HEATPUMP_POWER.matcher(name);
            if (!m.matches()) {
                continue;
            }
            String id = m.group("id");
            if (id == null || id.isBlank()) {
                id = "main";
            }
            ThingUID uid = new ThingUID(THING_TYPE_HEATPUMP, id);
            Map<String, Object> props = new HashMap<>();
            props.put("name", "Heat pump " + id);
            props.put("powerItem", name);
            DiscoveryResult result = DiscoveryResultBuilder.create(uid).withLabel("Heat pump — " + id)
                    .withProperties(props).withRepresentationProperty("powerItem").withThingType(THING_TYPE_HEATPUMP)
                    .build();
            thingDiscovered(result);
        }
    }

    private static String humanize(String snake) {
        String[] parts = snake.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private static String guessCategory(String id) {
        String l = id.toLowerCase();
        if (l.contains("boiler") || l.contains("heater")) {
            return "heating";
        }
        if (l.contains("airco") || l.contains("ac_") || l.contains("hvac")) {
            return "hvac";
        }
        if (l.contains("light")) {
            return "lighting";
        }
        if (l.matches("car[0-9]+") || l.contains("evse") || l.contains("ev") || l.contains("wallbox")
                || l.contains("charge")) {
            return "ev";
        }
        if (l.contains("security") || l.contains("alarm") || l.contains("camera")) {
            return "other";
        }
        return "other";
    }

    private static String guessColor(String id) {
        return switch (guessCategory(id)) {
            case "heating" -> "#ff9800";
            case "hvac" -> "#03a9f4";
            case "lighting" -> "#ffd54f";
            case "ev" -> "#4caf50";
            default -> "#9e9e9e";
        };
    }
}

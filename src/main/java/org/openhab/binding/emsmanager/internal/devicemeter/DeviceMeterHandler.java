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
package org.openhab.binding.emsmanager.internal.devicemeter;

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Deque;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.emsmanager.internal.config.DeviceMeterConfig;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thing handler for a {@code emsmanager:device-meter} instance. Reads the
 * configured powerItem each tick (driven by the bridge), integrates power
 * × dt into a per-device kWh accumulator, and publishes today / yesterday /
 * last-7 / last-30 / month / year on the Thing's channels.
 *
 * <p>
 * Registers with the bridge on init so the bridge's master scheduler
 * drives the update loop — keeps thread count low (one shared scheduler,
 * any number of device-meters).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class DeviceMeterHandler extends BaseThingHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceMeterHandler.class);

    private final ItemRegistry itemRegistry;

    // State (preserved across ticks; serialised to disk via DeviceMeterCache).
    private LocalDate lastSeenDay = LocalDate.MIN;
    private double kwhToday = 0.0;
    private final Deque<Double> ring = new ArrayDeque<>();
    private long lastTickMs = 0L;
    private volatile double currentW = 0.0;

    public DeviceMeterHandler(Thing thing, ItemRegistry itemRegistry) {
        super(thing);
        this.itemRegistry = itemRegistry;
    }

    @Override
    public void initialize() {
        DeviceMeterConfig cfg = getConfigAs(DeviceMeterConfig.class);
        if (cfg.powerItem == null || cfg.powerItem.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "powerItem required");
            return;
        }

        // Restore from disk.
        DeviceMeterCache.State restored = DeviceMeterCache.load(deviceId());
        if (restored != null) {
            lastSeenDay = restored.lastSeenDay();
            kwhToday = restored.kwhToday();
            ring.clear();
            ring.addAll(restored.ring());
            LOGGER.info("DeviceMeter[{}]: restored today={} kWh, ring size={}", deviceId(),
                    String.format("%.3f", kwhToday), ring.size());
        }

        // The bridge will discover us via the ThingRegistry on each tick —
        // no explicit registration needed. Keeps device-meter Things as
        // standalone (not bridge children) for cleaner .things-file syntax.

        updateStatus(ThingStatus.ONLINE);
        LOGGER.info("DeviceMeter[{}]: initialized — name='{}', powerItem={}, category={}", deviceId(), cfg.name,
                cfg.powerItem, cfg.category);
    }

    @Override
    public void dispose() {
        // Best-effort save.
        DeviceMeterCache.save(deviceId(), new DeviceMeterCache.State(lastSeenDay, kwhToday, new ArrayDeque<>(ring)));
        super.dispose();
        LOGGER.info("DeviceMeter[{}]: disposed", deviceId());
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // All channels read-only.
    }

    public String deviceId() {
        return getThing().getUID().getId();
    }

    public String name() {
        return getConfigAs(DeviceMeterConfig.class).name;
    }

    public String category() {
        return getConfigAs(DeviceMeterConfig.class).category;
    }

    public String color() {
        return getConfigAs(DeviceMeterConfig.class).color;
    }

    public double currentW() {
        return currentW;
    }

    public double kwhToday() {
        return kwhToday;
    }

    /**
     * Yesterday's completed daily total (most recent ring entry), or NaN if the
     * ring is empty. The anomaly detector reads this at midnight to grow its
     * per-day-of-week baseline.
     */
    public double yesterdayKwh() {
        return ring.isEmpty() ? Double.NaN : ring.peekLast();
    }

    /** Called by the bridge each tick. */
    public void update(long nowMs) {
        try {
            DeviceMeterConfig cfg = getConfigAs(DeviceMeterConfig.class);
            String powerItemName = cfg.powerItem;
            if (powerItemName == null || powerItemName.isBlank()) {
                return;
            }
            double rawPower = readNumber(powerItemName);
            if (Double.isNaN(rawPower)) {
                return;
            }
            double w = cfg.powerInKw ? rawPower * 1000.0 : rawPower;
            // CT clamps can read NEGATIVE when their phase orientation is reversed —
            // for device-meter integration we want absolute magnitude (we're measuring CONSUMPTION).
            w = Math.abs(w);
            this.currentW = w;

            if (lastTickMs == 0L) {
                lastTickMs = nowMs;
                publish(cfg);
                return;
            }
            long dtMs = nowMs - lastTickMs;
            lastTickMs = nowMs;
            if (dtMs <= 0L || dtMs > 60_000L) {
                publish(cfg);
                return;
            }

            // Midnight rollover check.
            LocalDate today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault())
                    .toLocalDate();
            if (!today.equals(lastSeenDay)) {
                if (lastSeenDay != LocalDate.MIN) {
                    ring.addLast(kwhToday);
                    while (ring.size() > DeviceMeterCache.maxRingDays()) {
                        ring.removeFirst();
                    }
                    LOGGER.info("DeviceMeter[{}]: day rollover — yesterday={} kWh, ringSize={}", deviceId(),
                            String.format("%.2f", kwhToday), ring.size());
                    kwhToday = 0.0;
                    DeviceMeterCache.save(deviceId(),
                            new DeviceMeterCache.State(today, kwhToday, new ArrayDeque<>(ring)));
                }
                lastSeenDay = today;
            }

            // Integrate.
            double hours = dtMs / 3_600_000.0;
            double dKwh = (w / 1000.0) * hours;
            kwhToday += dKwh;

            publish(cfg);
        } catch (Throwable t) {
            LOGGER.debug("DeviceMeter[{}].update: {}", deviceId(), t.getMessage());
        }
    }

    private void publish(DeviceMeterConfig cfg) {
        updateState(DM_CHANNEL_CURRENT_W, new QuantityType<>(currentW, Units.WATT));
        updateState(DM_CHANNEL_KWH_TODAY, new QuantityType<>(kwhToday, Units.KILOWATT_HOUR));

        // Yesterday = ring's last entry (set at last midnight)
        double yesterday = ring.isEmpty() ? 0.0 : ring.peekLast();
        updateState(DM_CHANNEL_KWH_YESTERDAY, new QuantityType<>(yesterday, Units.KILOWATT_HOUR));

        // Rolling sums + today
        updateState(DM_CHANNEL_KWH_LAST_7, new QuantityType<>(sumLast(7) + kwhToday, Units.KILOWATT_HOUR));
        updateState(DM_CHANNEL_KWH_LAST_30, new QuantityType<>(sumLast(30) + kwhToday, Units.KILOWATT_HOUR));

        // Year — Jan 1 to today.
        int doy = ZonedDateTime.now(ZoneId.systemDefault()).getDayOfYear();
        int included = Math.min(doy - 1, ring.size());
        updateState(DM_CHANNEL_KWH_YEAR, new QuantityType<>(sumLast(included) + kwhToday, Units.KILOWATT_HOUR));

        updateState(DM_CHANNEL_CATEGORY, new StringType(cfg.category));
        updateState(DM_CHANNEL_COLOR, new StringType(cfg.color));
    }

    private double sumLast(int n) {
        if (n <= 0 || ring.isEmpty()) {
            return 0.0;
        }
        Double[] arr = ring.toArray(new Double[0]);
        double sum = 0.0;
        int taken = 0;
        for (int i = arr.length - 1; i >= 0 && taken < n; i--, taken++) {
            sum += arr[i];
        }
        return sum;
    }

    private double readNumber(String itemName) {
        try {
            Item item = itemRegistry.getItem(itemName);
            State state = item.getState();
            if (state instanceof UnDefType) {
                return Double.NaN;
            }
            String s = state.toString();
            if (s == null || "NULL".equals(s) || "UNDEF".equals(s) || s.isEmpty()) {
                return Double.NaN;
            }
            int sp = s.indexOf(' ');
            String numPart = (sp > 0) ? s.substring(0, sp) : s;
            return Double.parseDouble(numPart);
        } catch (ItemNotFoundException | NumberFormatException e) {
            return Double.NaN;
        }
    }
}

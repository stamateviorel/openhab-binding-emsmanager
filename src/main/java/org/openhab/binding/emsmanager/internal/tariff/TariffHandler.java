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
package org.openhab.binding.emsmanager.internal.tariff;

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.emsmanager.internal.config.TariffConfig;
import org.openhab.binding.emsmanager.internal.tariff.spot.CsvFetcher;
import org.openhab.binding.emsmanager.internal.tariff.spot.EntsoeBeClient;
import org.openhab.binding.emsmanager.internal.tariff.spot.SpotPriceClient;
import org.openhab.binding.emsmanager.internal.tariff.spot.TibberClient;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thing handler for {@code emsmanager:tariff}. Holds a provider chosen by
 * config, snapshots it every minute, publishes channels.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class TariffHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(TariffHandler.class);

    private @Nullable ScheduledFuture<?> pollJob;
    private @Nullable TariffProvider provider;
    private volatile TariffSnapshot lastSnapshot = TariffSnapshot.EMPTY;

    private final HttpClient httpClient;

    public TariffHandler(Thing thing, HttpClient httpClient) {
        super(thing);
        this.httpClient = httpClient;
    }

    @Override
    public void initialize() {
        TariffConfig cfg = getConfigAs(TariffConfig.class);
        try {
            provider = buildProvider(cfg);
        } catch (Exception e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "tariff init: " + e.getMessage());
            return;
        }

        // Snapshot once now, then every minute. Snapshot is cheap (pure math).
        pollJob = scheduler.scheduleWithFixedDelay(this::pollOnce, 0, 60L, TimeUnit.SECONDS);

        updateStatus(ThingStatus.ONLINE);
        logger.info("Tariff Thing initialized: kind={}", cfg.kind);
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> job = pollJob;
        if (job != null) {
            job.cancel(true);
            pollJob = null;
        }
        provider = null;
        super.dispose();
        logger.info("Tariff Thing disposed.");
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        publish(lastSnapshot);
    }

    public TariffSnapshot snapshot() {
        return lastSnapshot;
    }

    private TariffProvider buildProvider(TariffConfig cfg) {
        switch (cfg.kind) {
            case DayNightTariff.KIND:
                return new DayNightTariff(cfg.dayPriceEurPerKWh, cfg.nightPriceEurPerKWh, cfg.dayStartHour,
                        cfg.dayEndHour);
            case TouScheduleTariff.KIND:
                return new TouScheduleTariff(cfg.hourlyPricesCsv);
            case DynamicSpotTariff.KIND:
                // A cold start with the feed unreachable would otherwise publish UNDEF prices and
                // silently stop every price-driven planner; fall back to the configured flat price.
                return new FallbackTariff(new DynamicSpotTariff(buildSpotClient(cfg), cfg.refreshIntervalMin),
                        new FlatTariff(cfg.flatPriceEurPerKWh));
            case FlatTariff.KIND:
            default:
                return new FlatTariff(cfg.flatPriceEurPerKWh);
        }
    }

    private SpotPriceClient buildSpotClient(TariffConfig cfg) {
        switch (cfg.subProvider) {
            case TibberClient.KEY:
                return new TibberClient(httpClient, cfg.apiKey);
            case CsvFetcher.KEY:
                return new CsvFetcher(httpClient, cfg.csvUrl, cfg.markupEurPerKWh);
            case EntsoeBeClient.KEY:
            default:
                return new EntsoeBeClient(httpClient, cfg.apiKey, cfg.markupEurPerKWh);
        }
    }

    private void pollOnce() {
        TariffProvider p = provider;
        if (p == null) {
            return;
        }
        try {
            TariffSnapshot snap = p.snapshot(Instant.now());
            lastSnapshot = snap;
            if (snap.lastError() == null) {
                updateStatus(ThingStatus.ONLINE);
            } else if (FallbackTariff.isFallback(snap)) {
                // Still serving usable prices (fallback or stale cache), so the thing is not down.
                updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, "Using fallback price - " + snap.lastError());
            } else {
                updateStatus(ThingStatus.OFFLINE, statusDetailFor(snap.lastError()), snap.lastError());
            }
            publish(snap);
        } catch (Throwable t) {
            logger.warn("Tariff snapshot threw", t);
        }
    }

    /**
     * A rejected or missing key needs the user to act; anything else (maintenance, DNS, timeout) is
     * transient and must not be reported as a configuration fault, which would imply an edit is due.
     */
    private static ThingStatusDetail statusDetailFor(@Nullable String error) {
        if (error == null) {
            return ThingStatusDetail.NONE;
        }
        boolean rejectedKey = error.contains("401") || error.contains("403") || error.contains("key not configured");
        return rejectedKey ? ThingStatusDetail.CONFIGURATION_ERROR : ThingStatusDetail.COMMUNICATION_ERROR;
    }

    private void publish(TariffSnapshot snap) {
        publishDouble(TR_CHANNEL_NOW_PRICE, snap.nowPriceEurPerKWh());
        publishDouble(TR_CHANNEL_NEXT_1H_PRICE, snap.next1hPriceEurPerKWh());
        publishDouble(TR_CHANNEL_TODAY_MIN, snap.todayMinPrice());
        publishDouble(TR_CHANNEL_TODAY_MAX, snap.todayMaxPrice());
        publishDouble(TR_CHANNEL_TODAY_AVG, snap.todayAvgPrice());
        publishInstant(TR_CHANNEL_CHEAPEST_HOUR_START, snap.cheapestHourStart());
        publishInstant(TR_CHANNEL_MOST_EXPENSIVE_HOUR_START, snap.mostExpensiveHourStart());
        publishCsv(TR_CHANNEL_SCHEDULE_24H, snap.schedule24h());
        publishCsv(TR_CHANNEL_SCHEDULE_48H, snap.schedule48h());
        updateState(TR_CHANNEL_SOURCE, new StringType(describeSource(snap)));
        if (snap.refreshedAt() != null && !snap.refreshedAt().equals(Instant.EPOCH)) {
            updateState(TR_CHANNEL_LAST_REFRESH_AT,
                    new DateTimeType(ZonedDateTime.ofInstant(snap.refreshedAt(), ZoneId.systemDefault())));
        } else {
            updateState(TR_CHANNEL_LAST_REFRESH_AT, UnDefType.UNDEF);
        }
    }

    /** Plain-language provenance for the prices currently published. */
    private String describeSource(TariffSnapshot snap) {
        if (Double.isNaN(snap.nowPriceEurPerKWh())) {
            return "unavailable - " + (snap.lastError() == null ? "no prices" : snap.lastError());
        }
        if (FallbackTariff.isFallback(snap)) {
            return "estimate (feed unavailable: " + snap.lastError() + ")";
        }
        TariffConfig cfg = getConfigAs(TariffConfig.class);
        return DynamicSpotTariff.KIND.equals(cfg.kind) ? "market prices (" + cfg.subProvider + ")"
                : "configured " + cfg.kind + " rate";
    }

    private void publishDouble(String channel, double v) {
        if (Double.isNaN(v)) {
            updateState(channel, UnDefType.UNDEF);
        } else {
            updateState(channel, new DecimalType(v));
        }
    }

    private void publishInstant(String channel, @Nullable Instant v) {
        if (v == null) {
            updateState(channel, UnDefType.UNDEF);
        } else {
            updateState(channel, new DateTimeType(ZonedDateTime.ofInstant(v, ZoneId.systemDefault())));
        }
    }

    private void publishCsv(String channel, double[] arr) {
        if (arr.length == 0) {
            updateState(channel, UnDefType.UNDEF);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(java.util.Locale.ROOT, "%.4f", arr[i]));
        }
        updateState(channel, new StringType(sb.toString()));
    }
}

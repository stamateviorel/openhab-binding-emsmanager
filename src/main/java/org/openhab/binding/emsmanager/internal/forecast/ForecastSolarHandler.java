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
package org.openhab.binding.emsmanager.internal.forecast;

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.emsmanager.internal.config.ForecastSolarConfig;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
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
 * Thing handler for {@code emsmanager:forecast-solar}. Polls the configured
 * provider on a schedule, publishes the snapshot to read-only channels.
 *
 * <p>
 * Free-tier-friendly: persists the snapshot to {@link ForecastCache} on
 * every successful fetch. On init, restores from cache and skips the
 * post-init fetch entirely if the cache is younger than the refresh
 * interval — bundle restarts no longer burn API calls. If a fetch returns
 * HTTP 429 (rate limit), the next attempt waits 60 minutes regardless of
 * the normal schedule.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ForecastSolarHandler extends BaseThingHandler {

    private static final long RATE_LIMITED_BACKOFF_MS = 60L * 60L * 1000L;

    private final Logger logger = LoggerFactory.getLogger(ForecastSolarHandler.class);

    private final HttpClient httpClient;
    private @Nullable ScheduledFuture<?> pollJob;
    private @Nullable SolarForecastProvider provider;
    private volatile ForecastSnapshot lastSnapshot = ForecastSnapshot.EMPTY;
    private volatile long rateLimitedUntilMs = 0L;
    private int refreshIntervalMin = 30;
    // Don't flap OFFLINE on a single transient fetch failure — keep the last good
    // forecast on the channels and only drop OFFLINE after several misses in a row.
    private static final int OFFLINE_AFTER_FAILS = 3;
    private int consecutiveFailures = 0;

    public ForecastSolarHandler(Thing thing, HttpClient httpClient) {
        super(thing);
        this.httpClient = httpClient;
    }

    @Override
    public void initialize() {
        ForecastSolarConfig cfg = getConfigAs(ForecastSolarConfig.class);
        if (Double.isNaN(cfg.lat) || Double.isNaN(cfg.lon)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "lat / lon are required");
            return;
        }
        provider = buildProvider(cfg);
        refreshIntervalMin = Math.max(5, cfg.refreshIntervalMin);

        // Load any cached snapshot — keeps channels populated across bundle
        // restarts without burning an API call.
        ForecastSnapshot cached = ForecastCache.load();
        long initialDelaySec;
        if (cached != null && !cached.refreshedAt().equals(Instant.EPOCH)) {
            lastSnapshot = cached;
            publish(cached);
            long ageMs = System.currentTimeMillis() - cached.refreshedAt().toEpochMilli();
            long intervalMs = refreshIntervalMin * 60L * 1000L;
            long remainingMs = intervalMs - ageMs;
            if (remainingMs > 0) {
                initialDelaySec = remainingMs / 1000L;
                logger.info("ForecastSolar: cache loaded (age {}s) — first fetch in {} s", ageMs / 1000L,
                        initialDelaySec);
            } else {
                initialDelaySec = 30L; // cache stale — fetch soon (but not 5s — give the bundle reload race time)
                logger.info("ForecastSolar: cache loaded but stale ({}s old) — fetching in {} s", ageMs / 1000L,
                        initialDelaySec);
            }
        } else {
            initialDelaySec = 30L;
            logger.info("ForecastSolar: no cache — first fetch in {} s", initialDelaySec);
        }

        pollJob = scheduler.scheduleWithFixedDelay(this::pollOnce, initialDelaySec, refreshIntervalMin * 60L,
                TimeUnit.SECONDS);

        updateStatus(ThingStatus.ONLINE);
        logger.info("ForecastSolar Thing initialized: kind={}, lat={}, lon={}, decl={}, az={}, kwp={}, every {} min",
                cfg.kind, cfg.lat, cfg.lon, cfg.declination, cfg.azimuth, cfg.kwp, refreshIntervalMin);
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
        logger.info("ForecastSolar Thing disposed.");
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        publish(lastSnapshot);
    }

    public ForecastSnapshot snapshot() {
        return lastSnapshot;
    }

    private SolarForecastProvider buildProvider(ForecastSolarConfig cfg) {
        switch (cfg.kind) {
            case "open-meteo":
                return new OpenMeteoForecast(httpClient, cfg);
            case "forecast-solar-free":
            default:
                return new ForecastSolarFreeTier(httpClient, cfg);
        }
    }

    private void pollOnce() {
        SolarForecastProvider p = provider;
        if (p == null) {
            return;
        }
        // Honour the 429 backoff — skip until the cooldown elapses.
        long now = System.currentTimeMillis();
        if (now < rateLimitedUntilMs) {
            long minLeft = (rateLimitedUntilMs - now) / 60_000L;
            logger.debug("ForecastSolar: skipping poll, rate-limit backoff active ({} min left)", minLeft);
            return;
        }
        try {
            ForecastSnapshot snap = p.fetch();
            String err = snap.lastError();
            if (err == null) {
                consecutiveFailures = 0;
                lastSnapshot = snap;
                updateStatus(ThingStatus.ONLINE);
                ForecastCache.save(snap); // persist for next reload
                publish(snap);
                logger.info("Forecast refreshed: nowW={}, todayKwh={}, tomorrowKwh={}, rateLimitRemaining={}",
                        fmt(snap.nowW()), fmt(snap.todayKwh()), fmt(snap.tomorrowKwh()), snap.rateLimitRemaining());
            } else if (err.contains("429")) {
                rateLimitedUntilMs = now + RATE_LIMITED_BACKOFF_MS;
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "rate limited — retry in 60 min");
                logger.warn("Forecast fetch hit rate limit (429) — backing off 60 min");
                updateState(FC_CHANNEL_LAST_ERROR, new StringType(err));
            } else {
                // Transient failure: keep the last good snapshot on the channels and
                // only drop OFFLINE after several consecutive misses (no flapping).
                consecutiveFailures++;
                if (consecutiveFailures >= OFFLINE_AFTER_FAILS) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, err);
                    logger.warn("Forecast fetch failed {}x in a row: {}", consecutiveFailures, err);
                } else {
                    logger.info("Forecast fetch transient failure ({}/{}), keeping last forecast: {}",
                            consecutiveFailures, OFFLINE_AFTER_FAILS, err);
                }
                updateState(FC_CHANNEL_LAST_ERROR, new StringType(err));
            }
        } catch (Throwable t) {
            logger.warn("ForecastSolar poll threw", t);
        }
    }

    private void publish(ForecastSnapshot snap) {
        publishPower(FC_CHANNEL_NOW_W, snap.nowW());
        publishEnergy(FC_CHANNEL_NEXT_1H_WH, snap.next1hWh());
        publishEnergy(FC_CHANNEL_NEXT_3H_WH, snap.next3hWh());
        publishEnergy(FC_CHANNEL_NEXT_6H_WH, snap.next6hWh());
        publishEnergy(FC_CHANNEL_TODAY_KWH, snap.todayKwh() * 1000.0);
        publishEnergy(FC_CHANNEL_TOMORROW_KWH, snap.tomorrowKwh() * 1000.0);
        updateState(FC_CHANNEL_HOURLY_TODAY_CSV,
                snap.hourlyTodayCsv() == null || snap.hourlyTodayCsv().isEmpty() ? UnDefType.UNDEF
                        : new org.openhab.core.library.types.StringType(snap.hourlyTodayCsv()));

        java.time.Instant peak = snap.peakTodayAt();
        if (peak == null) {
            updateState(FC_CHANNEL_PEAK_TODAY_AT, UnDefType.UNDEF);
        } else {
            updateState(FC_CHANNEL_PEAK_TODAY_AT,
                    new DateTimeType(ZonedDateTime.ofInstant(peak, ZoneId.systemDefault())));
        }

        if (snap.refreshedAt() != null && !snap.refreshedAt().equals(java.time.Instant.EPOCH)) {
            updateState(FC_CHANNEL_LAST_REFRESH_AT,
                    new DateTimeType(ZonedDateTime.ofInstant(snap.refreshedAt(), ZoneId.systemDefault())));
        } else {
            updateState(FC_CHANNEL_LAST_REFRESH_AT, UnDefType.UNDEF);
        }

        Integer remaining = snap.rateLimitRemaining();
        if (remaining == null) {
            updateState(FC_CHANNEL_RATE_LIMIT_REMAINING, UnDefType.UNDEF);
        } else {
            updateState(FC_CHANNEL_RATE_LIMIT_REMAINING, new DecimalType(remaining));
        }

        String err = snap.lastError();
        updateState(FC_CHANNEL_LAST_ERROR, err == null ? UnDefType.UNDEF : new StringType(err));
    }

    private void publishPower(String channel, double w) {
        if (Double.isNaN(w)) {
            updateState(channel, UnDefType.UNDEF);
        } else {
            updateState(channel, new QuantityType<>(w, Units.WATT));
        }
    }

    private void publishEnergy(String channel, double wh) {
        if (Double.isNaN(wh)) {
            updateState(channel, UnDefType.UNDEF);
        } else {
            updateState(channel, new QuantityType<>(wh, Units.WATT_HOUR));
        }
    }

    private static String fmt(double d) {
        return Double.isNaN(d) ? "NaN" : String.format("%.1f", d);
    }
}

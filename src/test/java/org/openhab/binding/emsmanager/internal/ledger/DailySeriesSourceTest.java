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
package org.openhab.binding.emsmanager.internal.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.openhab.core.items.Item;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.persistence.FilterCriteria;
import org.openhab.core.persistence.HistoricItem;
import org.openhab.core.persistence.PersistenceItemInfo;
import org.openhab.core.persistence.PersistenceService;
import org.openhab.core.persistence.PersistenceServiceRegistry;
import org.openhab.core.persistence.QueryablePersistenceService;
import org.openhab.core.types.State;

/**
 * The one class here that reaches the database, so the invariant that matters is not what it returns
 * but what it is allowed to ask for. An unbounded query against the dense counters took this server
 * down on 2026-09-12; these pin the guardrails that keep it away from them.
 *
 * @author Stamate Viorel - Initial contribution
 */
class DailySeriesSourceTest {

    static final ZoneId ZONE = ZoneId.of("Europe/Brussels");

    /** Records every FilterCriteria it is handed and answers with one point per day. */
    static class RecordingService implements QueryablePersistenceService {
        final List<FilterCriteria> seen = new ArrayList<>();
        boolean blowUp;

        @Override
        public String getId() {
            return "influxdb";
        }

        @Override
        public String getLabel(java.util.@org.eclipse.jdt.annotation.Nullable Locale locale) {
            return "rec";
        }

        @Override
        public void store(Item item) {
        }

        @Override
        public void store(Item item, @org.eclipse.jdt.annotation.Nullable String alias) {
        }

        @Override
        public Set<PersistenceItemInfo> getItemInfo() {
            return Set.of();
        }

        @Override
        public List<org.openhab.core.persistence.strategy.PersistenceStrategy> getDefaultStrategies() {
            return List.of();
        }

        @Override
        public Iterable<HistoricItem> query(FilterCriteria filter) {
            seen.add(filter);
            if (blowUp) {
                throw new IllegalStateException("influx is down");
            }
            List<HistoricItem> out = new ArrayList<>();
            // one rollup point per day, written at 23:58 local like the real rollup
            out.add(point(filter.getItemName(), LocalDate.of(2026, 9, 10).atTime(23, 58).atZone(ZONE), 11.0));
            out.add(point(filter.getItemName(), LocalDate.of(2026, 9, 11).atTime(23, 58).atZone(ZONE), 12.0));
            // a restart writing just after midnight: whose day is this?
            out.add(point(filter.getItemName(), LocalDate.of(2026, 9, 12).atTime(0, 3).atZone(ZONE), 99.0));
            return out;
        }

        static HistoricItem point(String name, ZonedDateTime at, double value) {
            return new HistoricItem() {
                @Override
                public java.time.Instant getInstant() {
                    return at.toInstant();
                }

                @Override
                public ZonedDateTime getTimestamp() {
                    return at;
                }

                @Override
                public State getState() {
                    return new DecimalType(value);
                }

                @Override
                public String getName() {
                    return name;
                }
            };
        }
    }

    static class Registry implements PersistenceServiceRegistry {
        final org.openhab.core.persistence.@org.eclipse.jdt.annotation.Nullable PersistenceService svc;

        Registry(org.openhab.core.persistence.@org.eclipse.jdt.annotation.Nullable PersistenceService svc) {
            this.svc = svc;
        }

        @Override
        public org.openhab.core.persistence.@org.eclipse.jdt.annotation.Nullable PersistenceService getDefault() {
            return svc;
        }

        @Override
        public org.openhab.core.persistence.@org.eclipse.jdt.annotation.Nullable PersistenceService get(
                @org.eclipse.jdt.annotation.Nullable String serviceId) {
            return svc;
        }

        @Override
        public @org.eclipse.jdt.annotation.Nullable String getDefaultId() {
            return "influxdb";
        }

        @Override
        public Set<PersistenceService> getAll() {
            PersistenceService s = svc;
            return s == null ? Set.of() : Set.of(s);
        }
    }

    @Test
    public void onlyTheRollupTierIsEverQueried() {
        RecordingService svc = new RecordingService();
        DailySeriesSource src = new DailySeriesSource(new Registry(svc), ZONE);
        src.load(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 12));
        List<String> names = svc.seen.stream().map(FilterCriteria::getItemName).sorted().toList();
        assertEquals(5, names.size());
        names.forEach(n -> assertTrue(n.startsWith("EMS_Stat_"), n));
    }

    @Test
    public void aThousandDayRequestIsClampedToMaxDays() {
        RecordingService svc = new RecordingService();
        DailySeriesSource src = new DailySeriesSource(new Registry(svc), ZONE);
        LocalDate to = LocalDate.of(2026, 9, 12);
        src.load(to.minusDays(1000), to);
        ZonedDateTime begin = svc.seen.get(0).getBeginDate();
        long span = java.time.temporal.ChronoUnit.DAYS.between(begin.toLocalDate(), to);
        assertTrue(span <= DailySeriesSource.MAX_DAYS + 1, "span was " + span);
    }

    @Test
    public void repeatsInsideTheTtlDoNotQueryAgain() {
        RecordingService svc = new RecordingService();
        AtomicLong now = new AtomicLong(1_000_000L);
        DailySeriesSource src = new DailySeriesSource(new Registry(svc), ZONE, now::get);
        LocalDate to = LocalDate.of(2026, 9, 12);
        src.load(to.minusDays(60), to);
        int after1 = svc.seen.size();
        for (int i = 0; i < 100; i++) {
            src.load(to.minusDays(60), to);
        }
        int after100 = svc.seen.size();
        now.addAndGet(10L * 60L * 1000L + 1);
        src.load(to.minusDays(60), to);
        int afterTtl = svc.seen.size();
        src.load(to.minusDays(59), to);
        int afterNewWindow = svc.seen.size();
        assertEquals(5, after100);
        assertEquals(10, afterTtl);
        assertEquals(15, afterNewWindow);
    }

    @Test
    public void pointsMapToTheDayTheyTotal() {
        RecordingService svc = new RecordingService();
        DailySeriesSource src = new DailySeriesSource(new Registry(svc), ZONE);
        LocalDate to = LocalDate.of(2026, 9, 12);
        src.load(to.minusDays(10), to);
        assertEquals(11.0, src.valueOn("EMS_Supply_kWh", LocalDate.of(2026, 9, 10)));
        assertNull(src.valueOn("EMS_Nonsense", LocalDate.of(2026, 9, 11)));
    }

    @Test
    public void noPersistenceAndAThrowingPersistenceAreBothSurvivable() {
        DailySeriesSource none = new DailySeriesSource(new Registry(null), ZONE);
        none.load(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 12));
        assertNull(none.valueOn("EMS_Supply_kWh", LocalDate.of(2026, 9, 10)));

        PersistenceService notQueryable = new PersistenceService() {
            @Override
            public String getId() {
                return "x";
            }

            @Override
            public String getLabel(java.util.@org.eclipse.jdt.annotation.Nullable Locale locale) {
                return "x";
            }

            @Override
            public void store(Item item) {
            }

            @Override
            public void store(Item item, @org.eclipse.jdt.annotation.Nullable String alias) {
            }

            @Override
            public List<org.openhab.core.persistence.strategy.PersistenceStrategy> getDefaultStrategies() {
                return List.of();
            }
        };
        DailySeriesSource dumb = new DailySeriesSource(new Registry(notQueryable), ZONE);
        dumb.load(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 12));
        assertNull(dumb.valueOn("EMS_Supply_kWh", LocalDate.of(2026, 9, 10)));

        RecordingService boom = new RecordingService();
        boom.blowUp = true;
        DailySeriesSource broken = new DailySeriesSource(new Registry(boom), ZONE);
        broken.load(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 12));
        assertNull(broken.valueOn("EMS_Supply_kWh", LocalDate.of(2026, 9, 10)));
    }
}

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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.emsmanager.internal.asset.AssetWriteOutcome;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the {@link ActionJournal} for the Past tab's Actions view.
 *
 * <p>
 * Each entry becomes one line in the reader's own terms — a time, what changed, who asked and why —
 * rather than the asset ids and setpoint kinds the dispatcher speaks in.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class JournalPublisher {

    /** More than a phone can scroll and more than the page needs; the label says when it bites. */
    public static final int MAX_ROWS = 120;

    private static final Logger LOGGER = LoggerFactory.getLogger(JournalPublisher.class);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final EventPublisher eventPublisher;
    private final ItemRegistry itemRegistry;
    private final ActionJournal journal;
    private final ZoneId zone;

    private String lastRows = "";
    private String lastLabel = "";

    public JournalPublisher(EventPublisher eventPublisher, ItemRegistry itemRegistry, ActionJournal journal,
            ZoneId zone) {
        this.eventPublisher = eventPublisher;
        this.itemRegistry = itemRegistry;
        this.journal = journal;
        this.zone = zone;
    }

    public void publish(LocalDate today) {
        try {
            String filter = seed("EMS_Journal_Filter", "all");
            List<ActionJournal.Entry> all = journal.entries();
            List<ActionJournal.Entry> kept = all.stream().filter(e -> keeps(filter, e.outcome)).toList();
            String rows = json(kept, today);
            String label = label(kept.size(), all, filter);
            // the page only redraws on a state change, and an unchanged table every five seconds is
            // an event-bus post nobody reads
            if (!rows.equals(lastRows)) {
                publishText("EMS_Journal_Rows_JSON", rows);
                lastRows = rows;
            }
            if (!label.equals(lastLabel)) {
                publishText("EMS_Journal_Label", label);
                lastLabel = label;
            }
            publishNumber("EMS_Journal_Count", all.size());
        } catch (Throwable t) {
            LOGGER.debug("Journal publish failed: {}", t.toString());
        }
    }

    private static boolean keeps(String filter, AssetWriteOutcome outcome) {
        return switch (filter) {
            case "writes" -> outcome == AssetWriteOutcome.WROTE;
            case "blocked" -> outcome == AssetWriteOutcome.HELD || outcome == AssetWriteOutcome.REFUSED
                    || outcome == AssetWriteOutcome.FAILED;
            default -> true;
        };
    }

    private String label(int shown, List<ActionJournal.Entry> all, String filter) {
        if (all.isEmpty()) {
            return "Nothing has been dispatched yet";
        }
        ZonedDateTime oldest = ZonedDateTime.ofInstant(Instant.ofEpochMilli(all.get(all.size() - 1).firstAt), zone);
        String since = oldest.getDayOfMonth() + " " + oldest.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        String what = switch (filter) {
            case "writes" -> "commands sent";
            case "blocked" -> "held or refused";
            default -> "actions";
        };
        String capped = shown > MAX_ROWS ? " · newest " + MAX_ROWS + " shown" : "";
        return "Since " + since + " · " + shown + " " + what + capped;
    }

    /**
     * One row per entry, newest first. The bar is the repeat count against the busiest entry, which
     * is the only quantity a journal line has.
     */
    private String json(List<ActionJournal.Entry> entries, LocalDate today) {
        int max = 1;
        for (ActionJournal.Entry e : entries) {
            max = Math.max(max, e.count);
        }
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        for (ActionJournal.Entry e : entries) {
            if (n >= MAX_ROWS) {
                break;
            }
            ZonedDateTime at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(e.lastAt), zone);
            if (n > 0) {
                sb.append(',');
            }
            sb.append("{\"k\":\"").append(e.firstAt).append('-').append(n).append("\",\"t\":\"")
                    .append(at.format(CLOCK)).append("\",\"d\":\"").append(escape(day(at.toLocalDate(), today)))
                    .append("\",\"l\":\"").append(escape(headline(e))).append("\",\"s\":\"").append(escape(sub(e)))
                    .append("\",\"o\":\"").append(escape(outcomeWord(e.outcome))).append("\",\"c\":\"")
                    .append(colour(e.outcome)).append("\",\"n\":").append(e.count).append(",\"bar\":")
                    .append(Math.round(100.0 * e.count / max)).append('}');
            n++;
        }
        return sb.append(']').toString();
    }

    /** "Boiler → ON", "Auto 3 → 16 A". The asset id is the site's, so it is titled, not translated. */
    private static String headline(ActionJournal.Entry e) {
        return pretty(e.asset) + " → " + e.value;
    }

    private static String sub(ActionJournal.Entry e) {
        String why = e.reason.isBlank() ? e.controller : e.controller + " · " + e.reason;
        return e.count > 1 ? why + " (×" + e.count + ")" : why;
    }

    /** car3 → Auto 3; ems-battery → Ems battery. */
    private static String pretty(String assetId) {
        String spaced = assetId.replace('-', ' ').replace('_', ' ');
        if (spaced.matches("car ?\\d+")) {
            return "Auto " + spaced.replaceAll("\\D+", "");
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String outcomeWord(AssetWriteOutcome outcome) {
        return switch (outcome) {
            case WROTE -> "sent";
            case SHADOWED -> "would have";
            case HELD -> "held";
            case REFUSED -> "refused";
            case FAILED -> "failed";
            default -> "no change";
        };
    }

    private static String colour(AssetWriteOutcome outcome) {
        return switch (outcome) {
            case WROTE -> "#3bb273";
            case SHADOWED -> "#7d6cd6";
            case HELD -> "#f0a83c";
            case FAILED -> "#db3b56";
            default -> "#8e8e93";
        };
    }

    private String day(LocalDate date, LocalDate today) {
        if (date.equals(today)) {
            return "today";
        }
        if (date.equals(today.minusDays(1))) {
            return "yesterday";
        }
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + date.getDayOfMonth() + " "
                + date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private String seed(String item, String fallback) {
        State state = stateOf(item);
        if (state == null || state instanceof UnDefType) {
            publishText(item, fallback);
            return fallback;
        }
        return state.toString();
    }

    private @Nullable State stateOf(String itemName) {
        try {
            Item item = itemRegistry.getItem(itemName);
            return item.getState();
        } catch (ItemNotFoundException e) {
            return null;
        }
    }

    private void publishText(String item, String value) {
        post(item, new StringType(value));
    }

    private void publishNumber(String item, double value) {
        post(item, new DecimalType(value));
    }

    private void post(String itemName, State value) {
        try {
            itemRegistry.getItem(itemName);
            eventPublisher.post(ItemEventFactory.createStateEvent(itemName, value, null));
        } catch (ItemNotFoundException e) {
            // the site has not declared this Item; the page hides what does not exist
        }
    }
}

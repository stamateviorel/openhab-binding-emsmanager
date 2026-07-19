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
package org.openhab.binding.emsmanager.internal.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Weekly aggregator + HTML report (e.g. run Sunday 18:00).
 *
 * <p>
 * Generates a one-page HTML summary of the past 7 days and writes it to a
 * {@code weekly-<date>.html} file under the reports directory. Email delivery
 * is left to the user (e.g. a {@code mail} action in a rule to attach the
 * file).
 *
 * <p>
 * {@link #generate(java.time.LocalDate)} is callable on demand (used by tests
 * and the manual-trigger item).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class WeeklyReportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WeeklyReportService.class);
    private static final Path REPORTS_DIR = Path.of("/var/lib/openhab/reports");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final ItemRegistry itemRegistry;

    public WeeklyReportService(ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    /** Build a report for the 7 days ending on {@code endDate} (inclusive). */
    public String generate(LocalDate endDate) {
        // Pull the headline numbers from LongTermStats items + CO₂ + per-device.
        double prodKwh = read("EMS_SelfConsumption_kWh_Last7Days") + read("EMS_FeedIn_kWh_Last7Days");
        double consKwh = read("EMS_SelfConsumption_kWh_Last7Days") + read("EMS_Supply_kWh_Last7Days");
        double importKwh = read("EMS_Supply_kWh_Last7Days");
        double exportKwh = read("EMS_FeedIn_kWh_Last7Days");
        double costEur = read("EMS_Cost_EUR_Last7Days");
        double earnEur = read("EMS_Earnings_EUR_Last7Days");
        double savEur = read("EMS_Savings_EUR_Last7Days");
        double co2Saved = read("EMS_CO2_Saved_Today_kg") * 7; // crude weekly estimate
        double co2Emitted = read("EMS_CO2_Today_kg") * 7;

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">");
        html.append("<title>EMS Weekly report — ").append(endDate).append("</title>");
        html.append("<style>");
        html.append(
                "body{font-family:-apple-system,Segoe UI,sans-serif;max-width:720px;margin:2em auto;color:#333;line-height:1.5}");
        html.append("h1{color:#2563eb}");
        html.append(".kpi{display:flex;gap:1em;flex-wrap:wrap;margin:1em 0}");
        html.append(".card{flex:1;min-width:140px;background:#f3f4f6;padding:1em;border-radius:8px}");
        html.append(".card .v{font-size:1.5em;font-weight:700;color:#111}");
        html.append(".card .l{font-size:0.85em;color:#666}");
        html.append(".saved{background:linear-gradient(135deg,#a8e063,#56ab2f);color:#fff}");
        html.append(".saved .v{color:#fff}");
        html.append(".saved .l{color:#fff;opacity:0.85}");
        html.append("</style></head><body>");
        html.append("<h1>EMS — Weekly report ").append(endDate).append("</h1>");
        html.append("<p>The figures for the past 7 days are shown below.</p>");

        html.append("<div class=\"kpi\">");
        html.append(card("Production", "%.1f kWh", prodKwh, false));
        html.append(card("Consumption", "%.1f kWh", consKwh, false));
        html.append(card("Imported", "%.1f kWh", importKwh, false));
        html.append(card("Injected", "%.1f kWh", exportKwh, false));
        html.append("</div>");

        html.append("<div class=\"kpi\">");
        html.append(card("Cost", "€ %.2f", costEur, false));
        html.append(card("Earnings", "€ %.2f", earnEur, false));
        html.append(card("Savings", "€ %.2f", savEur, true));
        html.append(card("CO₂ saved", "%.1f kg", co2Saved, true));
        html.append("</div>");

        html.append("<h2>Inefficiency signals</h2>");
        html.append("<p>");
        if (importKwh > 0 && exportKwh > 0) {
            html.append("This week ").append(String.format("%.1f", importKwh)).append(" kWh was imported ");
            html.append("and ").append(String.format("%.1f", exportKwh)).append(" kWh was injected. ");
            html.append("A larger battery or better timing of EV charging could save up to ");
            html.append(String.format("€ %.2f", Math.min(importKwh, exportKwh) * 0.25));
            html.append(" per week.");
        } else {
            html.append("Optimal balance — no simultaneous import and export this week.");
        }
        html.append("</p>");

        html.append("<p style=\"color:#888;font-size:0.8em;margin-top:3em\">");
        html.append("Generated by EMS Manager binding — ").append(ZonedDateTime.now(ZoneId.systemDefault()));
        html.append("</p>");
        html.append("</body></html>");

        String content = html.toString();
        // Write to disk
        try {
            Files.createDirectories(REPORTS_DIR);
            Path p = REPORTS_DIR.resolve("weekly-" + endDate.format(DATE_FMT) + ".html");
            Files.writeString(p, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            LOGGER.info("Weekly report written: {}", p);
        } catch (IOException e) {
            LOGGER.warn("Failed to write weekly report: {}", e.toString());
        }
        return content;
    }

    private String card(String label, String fmt, double value, boolean saved) {
        return String.format("<div class=\"card%s\"><div class=\"v\">%s</div><div class=\"l\">%s</div></div>",
                saved ? " saved" : "", String.format(java.util.Locale.ROOT, fmt, value), label);
    }

    private double read(String name) {
        try {
            Item item = itemRegistry.getItem(name);
            var state = item.getState();
            if (state instanceof DecimalType d) {
                return d.doubleValue();
            }
            if (state instanceof QuantityType q) {
                return q.doubleValue();
            }
            String s = state.toString();
            if (s == null || s.isEmpty() || "NULL".equals(s) || "UNDEF".equals(s)) {
                return 0.0;
            }
            int sp = s.indexOf(' ');
            return Double.parseDouble(sp > 0 ? s.substring(0, sp) : s);
        } catch (ItemNotFoundException | NumberFormatException e) {
            return 0.0;
        }
    }
}

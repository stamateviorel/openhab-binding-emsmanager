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
package org.openhab.binding.emsmanager.internal.ems;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Turns an item's {@code energy} metadata into an {@link EnergyParticipant}, realising Kai
 * Kreuzer's idea (openhab-core #3478) of marking the participating items with a new
 * {@code energy:} metadata namespace and letting the energy-management service discover them.
 *
 * <p>
 * Metadata shape (value + config):
 *
 * <pre>
 *   Number:Power Solar_Power   "PV"        { energy="provider" [ role="pv" ] }
 *   Number:Power Grid_Power     "Grid"      { energy="provider" [ role="grid", price="Grid_Price" ] }
 *   Number:Power Battery_Power  "Battery"   { energy="provider" [ role="battery", control="Battery_Setpoint", min=-3000, max=3000 ] }
 *   Switch       Boiler_Switch  "Boiler"    { energy="consumer" [ profile="simple", thresholdW=2000, minOnMinutes=10, priority=20 ] }
 *   Switch       PoolPump       "Pool"      { energy="consumer" [ profile="simple", runAtLevel=2, ready="Pool_Enabled" ] }
 *   Switch       Freezer        "Freezer"   { energy="consumer" [ profile="simple", maxOffMinutes=45, minOnMinutes=15 ] }
 *   Number:Power Wallbox_Power  "Wallbox"   { energy="consumer" [ profile="controllable", min=0, max=11000, demandKwh=10, deadlineHour=7 ] }
 *   String       HeatPump_SGr   "Heatpump"  { energy="consumer" [ profile="mode", modes="blocked,normal,encouraged,forced" ] }
 *   Switch       Dishwasher     "Dish"      { energy="consumer" [ profile="batch", ratedW=2000, runtimeHours=2, deadlineHour=17, measure="Dish_Power", shape="0.1,1.0,0.2" ] }
 * </pre>
 *
 * Pure and side-effect free, so the rules can be unit-tested without an ItemRegistry.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class EnergyMetadataParser {

    /** The item metadata namespace, as Kai proposed. */
    public static final String NAMESPACE = "energy";

    private EnergyMetadataParser() {
    }

    /**
     * Parse one item's {@code energy} metadata into a participant.
     *
     * @param itemName the item the metadata is attached to
     * @param value the metadata value: {@code "provider"} or {@code "consumer"}
     * @param config the metadata config map
     * @return the participant, or empty when the metadata is absent/malformed
     */
    public static Optional<EnergyParticipant> parse(String itemName, @Nullable String value,
            Map<String, Object> config) {
        if (value == null) {
            return Optional.empty();
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "provider":
                return parseProvider(itemName, config).map(p -> (EnergyParticipant) p);
            case "consumer":
                return parseConsumer(itemName, config).map(c -> (EnergyParticipant) c);
            default:
                return Optional.empty();
        }
    }

    static Optional<EnergyProvider> parseProvider(String itemName, Map<String, Object> cfg) {
        ProviderRole role = parseRole(str(cfg, "role"));
        if (role == null) {
            return Optional.empty();
        }
        String control = str(cfg, "control");
        boolean controllable = control != null && !control.isBlank();
        return Optional.of(new EnergyProvider(itemName, role, controllable, controllable ? control : null,
                dbl(cfg, "min", Double.NaN), dbl(cfg, "max", Double.NaN), str(cfg, "price"), str(cfg, "schedule"),
                str(cfg, "soc")));
    }

    static Optional<EnergyConsumer> parseConsumer(String itemName, Map<String, Object> cfg) {
        String type = str(cfg, "profile");
        String t = type == null ? "simple" : type.trim().toLowerCase(Locale.ROOT);
        PowerProfile profile;
        switch (t) {
            case "simple":
                profile = new PowerProfile.Simple(itemName, dbl(cfg, "thresholdW", Double.NaN),
                        (int) dbl(cfg, "runAtLevel", 0.0), (int) dbl(cfg, "minOnMinutes", 0.0),
                        (int) dbl(cfg, "maxOnMinutes", 0.0), (int) dbl(cfg, "minOffMinutes", 0.0),
                        (int) dbl(cfg, "maxOffMinutes", 0.0));
                break;
            case "controllable":
                profile = new PowerProfile.Controllable(itemName, dbl(cfg, "min", 0.0), dbl(cfg, "max", Double.NaN));
                break;
            case "mode": {
                // Ordered comma list, most-restricted first (e.g. SG-ready "blocked,normal,encouraged,forced").
                String csv = str(cfg, "modes");
                if (csv == null) {
                    return Optional.empty();
                }
                List<String> modes = new ArrayList<>();
                for (String m : csv.split(",")) {
                    String mm = m.trim();
                    if (!mm.isEmpty()) {
                        modes.add(mm);
                    }
                }
                if (modes.size() < 2) {
                    return Optional.empty();
                }
                profile = new PowerProfile.ModeControllable(itemName, List.copyOf(modes));
                break;
            }
            case "batch":
                profile = new PowerProfile.Batch(itemName, dbl(cfg, "ratedW", Double.NaN),
                        dbl(cfg, "runtimeHours", 1.0), parseShape(str(cfg, "shape")));
                break;
            default:
                return Optional.empty();
        }
        return Optional.of(new EnergyConsumer(itemName, profile, dbl(cfg, "demandKwh", 0.0),
                (int) dbl(cfg, "deadlineHour", -1.0), str(cfg, "measure"), str(cfg, "ready"),
                (int) dbl(cfg, "priority", EnergyConsumer.DEFAULT_PRIORITY)));
    }

    /**
     * Parse a normalized batch shape CSV ({@code "0.1,1.0,0.2"} — fractions of rated power per hour
     * slot, each in {@code [0,1]}). Malformed or out-of-range input degrades to the empty
     * (rectangular) shape rather than failing the consumer.
     */
    static List<Double> parseShape(@Nullable String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<Double> out = new ArrayList<>();
        for (String tok : csv.split(",")) {
            try {
                double v = Double.parseDouble(tok.trim());
                if (Double.isNaN(v) || v < 0.0 || v > 1.0) {
                    return List.of();
                }
                out.add(v);
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        return List.copyOf(out);
    }

    private static @Nullable ProviderRole parseRole(@Nullable String s) {
        if (s == null) {
            return null;
        }
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "grid":
                return ProviderRole.GRID;
            case "pv":
            case "solar":
                return ProviderRole.PV;
            case "battery":
            case "storage":
                return ProviderRole.BATTERY;
            default:
                return null;
        }
    }

    private static @Nullable String str(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static double dbl(Map<String, Object> cfg, String key, double dflt) {
        Object v = cfg.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(v.toString().trim());
            } catch (NumberFormatException e) {
                return dflt;
            }
        }
        return dflt;
    }
}

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
 *   Switch       Boiler_Switch  "Boiler"    { energy="consumer" [ profile="simple" ] }
 *   Number:Power Wallbox_Power  "Wallbox"   { energy="consumer" [ profile="controllable", min=0, max=11000, demandKwh=10, deadlineHour=7 ] }
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
                profile = new PowerProfile.Simple(itemName);
                break;
            case "controllable":
                profile = new PowerProfile.Controllable(itemName, dbl(cfg, "min", 0.0), dbl(cfg, "max", Double.NaN));
                break;
            default:
                return Optional.empty();
        }
        return Optional.of(new EnergyConsumer(itemName, profile, dbl(cfg, "demandKwh", 0.0),
                (int) dbl(cfg, "deadlineHour", -1.0)));
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

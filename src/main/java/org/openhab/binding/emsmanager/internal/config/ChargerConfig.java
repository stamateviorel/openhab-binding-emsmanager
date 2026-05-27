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
package org.openhab.binding.emsmanager.internal.config;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Configuration for a single {@code emsmanager:charger} Thing.
 *
 * <p>
 * Each field names an item the EV coordinator reads (mode / cable /
 * status / amps / power) or writes (current-limit / pause / charging).
 * This is the per-charger alternative to the bridge's {@code car%d}-pattern
 * config: instead of a global numbering scheme, each charger is its own
 * Thing with explicit item bindings — the upstream-friendly path that lets
 * a user add a charger via the UI without touching bridge config.
 *
 * <p>
 * Charger Things are opt-in. When none exist, the bridge falls back to
 * the {@code carCount} + {@code car%d}-pattern behaviour, so existing
 * installs are unaffected.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ChargerConfig {

    public String name = "EV charger";

    /** Snapshot key the controllers use. Defaults to the Thing id. */
    public @Nullable String carKey;

    // Read-side items.
    public @Nullable String modeItem;
    public @Nullable String cableItem;
    public @Nullable String statusItem;
    public @Nullable String powerOcppItem; // W
    public @Nullable String powerKwItem; // kW (external power item, e.g. a CT-clamp reading)
    public @Nullable String ampsL1Item;
    public @Nullable String ampsL2Item;
    public @Nullable String ampsL3Item;

    // Write-side items (the coordinator commands these).
    public @Nullable String currentLimitItem;
    public @Nullable String pauseItem;
    public @Nullable String chargingItem;

    /** Per-charger breaker limit (A/phase). Defaults to the site main breaker. */
    public int breakerLimitA = 63;
}

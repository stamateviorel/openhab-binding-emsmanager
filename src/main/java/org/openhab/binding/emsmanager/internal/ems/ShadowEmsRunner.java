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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.MetadataRegistry;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs Kai Kreuzer's core energy-management design (openhab-core #3478) end-to-end in
 * <b>shadow</b>: it discovers the {@code energy:}-tagged participants, reads their live state
 * from the item model, derives its own surplus from the tagged grid provider, runs the
 * {@link EnergyManagementService} strategy, and <b>logs</b> the plan it would apply — it never
 * writes any item. So it depends only on the participant items (as Kai envisioned) and makes
 * real decisions on a live site, alongside and without disturbing the production pipeline.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ShadowEmsRunner {

    private final Logger logger = LoggerFactory.getLogger(ShadowEmsRunner.class);
    private final MetadataParticipantScanner scanner;
    private final ItemRegistry itemRegistry;
    private final double simpleLoadThresholdW;
    private final @Nullable EmsActuator actuator;

    public ShadowEmsRunner(MetadataRegistry metadataRegistry, ItemRegistry itemRegistry, double simpleLoadThresholdW,
            @Nullable EmsActuator actuator) {
        this.scanner = new MetadataParticipantScanner(metadataRegistry);
        this.itemRegistry = itemRegistry;
        this.simpleLoadThresholdW = simpleLoadThresholdW > 0 ? simpleLoadThresholdW : 1000.0;
        this.actuator = actuator;
    }

    /**
     * Discover {@code energy:}-tagged items, read their live state, run the strategy, log the
     * plan. Never writes.
     *
     * @param fallbackSurplusW surplus to use when no grid provider is tagged to derive it from
     */
    public void run(double fallbackSurplusW) {
        List<EnergyProvider> providers = scanner.providers();
        List<EnergyConsumer> consumers = scanner.consumers();
        if (providers.isEmpty() && consumers.isEmpty()) {
            logger.info("[EMS-SHADOW] enabled, but no items carry 'energy' metadata yet — tag items to see a plan");
            return;
        }

        // Derive surplus from a tagged grid provider's live net power; fall back otherwise.
        double surplus = fallbackSurplusW;
        EnergyProvider grid = null;
        for (EnergyProvider p : providers) {
            if (p.role() == ProviderRole.GRID) {
                grid = p;
                double gridW = readW(p.id());
                if (!Double.isNaN(gridW)) {
                    surplus = EnergyManagementService.surplusFromGridNet(gridW);
                }
                break;
            }
        }

        EmsActuator act = actuator;
        String mode = act != null ? "EMS-APPLY" : "EMS-SHADOW";
        List<EmsAction> actions = EnergyManagementService.planSurplusDispatch(surplus, consumers, simpleLoadThresholdW);
        logger.info("[{}] Kai #3478 model: surplus {} W · {} provider(s) · {} consumer(s) → {} action(s) ({})", mode,
                Math.round(surplus), providers.size(), consumers.size(), actions.size(),
                act != null ? "applying" : "no writes");
        for (EnergyProvider p : providers) {
            double w = readW(p.id());
            String price = "";
            String priceItem = p.priceItem();
            if (priceItem != null) {
                double pr = readW(priceItem);
                if (!Double.isNaN(pr)) {
                    price = String.format(java.util.Locale.ROOT, ", price=%.3f", pr);
                }
            }
            logger.info("[{}]   provider {} role={} power={} W{}{}", mode, p.id(), p.role(),
                    Double.isNaN(w) ? "?" : Long.toString(Math.round(w)), p.controllable() ? " (controllable)" : "",
                    price);
        }
        for (EmsAction a : actions) {
            String v = a.kind() == EmsAction.Kind.SET_WATTS ? Math.round(a.value()) + " W"
                    : (a.value() > 0 ? "ON" : "OFF");
            if (act != null) {
                boolean ok = act.apply(a);
                logger.info("[{}]   {} {} -> {} — {}", mode, ok ? "set" : "skipped (item missing)", a.itemName(), v,
                        a.reason());
            } else {
                logger.info("[{}]   would set {} -> {} — {}", mode, a.itemName(), v, a.reason());
            }
        }

        // Price + deadline strategy: a consumer with a demand is scheduled into the cheapest hours
        // before its daily deadline, using the grid provider's 24 h price schedule when available.
        double[] schedule = grid != null ? EnergyManagementService.parseSchedule(readString(grid.scheduleItem()))
                : new double[0];
        int hourNow = java.time.LocalTime.now().getHour();
        for (EnergyConsumer c : consumers) {
            if (c.demandKwh() > 0 && c.deadlineHour() >= 0 && c.deadlineHour() <= 23) {
                boolean runNow = EnergyManagementService.runNowForDeadline(hourNow, c.deadlineHour(), c.demandKwh(),
                        ratedKw(c), schedule);
                logger.info("[{}]   deadline plan {} -> {} (demand {} kWh by {}:00, {})", mode, c.id(),
                        runNow ? "RUN NOW" : "WAIT", c.demandKwh(), c.deadlineHour(),
                        schedule.length >= 24 ? "cheapest-hour ranking" : "no price schedule, latest-hours fallback");
            }
        }
    }

    /** kW draw used to size cheapest-window planning: a controllable load's max, else the threshold. */
    private double ratedKw(EnergyConsumer c) {
        PowerProfile profile = c.profile();
        if (profile instanceof PowerProfile.Controllable cp && !Double.isNaN(cp.maxW()) && cp.maxW() > 0) {
            return cp.maxW() / 1000.0;
        }
        return simpleLoadThresholdW / 1000.0;
    }

    /** Read an item's state as a string, or null if missing. */
    private @Nullable String readString(@Nullable String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return null;
        }
        try {
            return itemRegistry.getItem(itemName).getState().toString();
        } catch (ItemNotFoundException e) {
            return null;
        }
    }

    /** Read an item's numeric state in its own unit (W expected), or NaN if missing/non-numeric. */
    private double readW(@Nullable String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return Double.NaN;
        }
        try {
            State s = itemRegistry.getItem(itemName).getState();
            if (s instanceof QuantityType<?> q) {
                return q.doubleValue();
            }
            if (s instanceof DecimalType d) {
                return d.doubleValue();
            }
        } catch (ItemNotFoundException e) {
            return Double.NaN;
        }
        return Double.NaN;
    }
}

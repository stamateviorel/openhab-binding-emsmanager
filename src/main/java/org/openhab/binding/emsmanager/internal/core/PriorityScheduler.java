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
package org.openhab.binding.emsmanager.internal.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Priority-ordered registry of {@link Controller}s. Evaluates them in
 * priority order, collects {@link SetpointRequest}s, and returns the
 * (un-resolved) decision list. The bridge then dispatches the resolved
 * requests via asset handlers.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class PriorityScheduler {

    private final List<Controller> controllers = new CopyOnWriteArrayList<>();

    public void register(Controller c) {
        controllers.add(c);
        controllers.sort(Comparator.comparingInt(Controller::priority));
    }

    public void unregister(Controller c) {
        controllers.remove(c);
    }

    public int size() {
        return controllers.size();
    }

    public List<Controller> controllers() {
        return List.copyOf(controllers);
    }

    /**
     * Evaluate every enabled controller against the context, in priority
     * order. Returns the merged list of requests. Dispatch happens in the
     * caller (bridge handler) via asset handlers — and only when neither
     * the bridge's master shadow nor the per-controller shadow is engaged.
     */
    public List<SetpointRequest> run(EnergyContext ctx) {
        if (controllers.isEmpty()) {
            return List.of();
        }
        List<SetpointRequest> collected = new ArrayList<>();
        for (Controller c : controllers) {
            if (!c.enabled()) {
                continue;
            }
            try {
                collected.addAll(c.evaluate(ctx));
            } catch (Throwable t) {
                // Never let one controller's bug take down the tick.
                org.slf4j.LoggerFactory.getLogger(PriorityScheduler.class)
                        .warn("Controller '{}' threw — skipping its requests this tick", c.name(), t);
            }
        }
        return collected;
    }
}

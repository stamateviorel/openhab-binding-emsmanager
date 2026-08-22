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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Collapses competing {@link SetpointRequest}s so one asset is written at most
 * once per tick per kind.
 *
 * <p>
 * The lowest priority number wins. Controllers are evaluated in ascending
 * priority order, so without this step every later controller would overwrite
 * the one before it at the asset handler and the safety breaker — deliberately
 * the lowest number — would be the weakest request rather than the strongest.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class SetpointResolver {

    private SetpointResolver() {
    }

    /** Surviving requests plus the ones they displaced, so the caller can log them. */
    public record Result(List<SetpointRequest> winners, List<SetpointRequest> dropped) {
    }

    /**
     * Resolve one tick's requests. Ties keep the request seen first, which makes
     * a controller that emits two values for the same asset deterministic rather
     * than order-dependent.
     */
    public static Result resolve(List<SetpointRequest> requests) {
        Map<String, SetpointRequest> best = new LinkedHashMap<>();
        List<SetpointRequest> dropped = new ArrayList<>();
        for (SetpointRequest r : requests) {
            String key = r.assetId() + "|" + r.kind();
            SetpointRequest held = best.get(key);
            if (held == null) {
                best.put(key, r);
            } else if (r.priority() < held.priority()) {
                best.put(key, r);
                dropped.add(held);
            } else {
                dropped.add(r);
            }
        }
        return new Result(List.copyOf(best.values()), List.copyOf(dropped));
    }
}

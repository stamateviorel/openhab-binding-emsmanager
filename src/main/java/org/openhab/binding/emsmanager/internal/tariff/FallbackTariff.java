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
package org.openhab.binding.emsmanager.internal.tariff;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Wraps a tariff provider with a second one used only when the first has no price to give.
 *
 * A remote price feed can be unreachable on a cold start, before any day has been cached: the
 * platform is in maintenance, the key is not activated yet, the site has no internet. Without this
 * the whole tariff plane publishes UNDEF and every consumer that plans against price silently
 * stops planning. Falling back to the configured flat price keeps them running on an approximation
 * instead of on nothing.
 *
 * The primary's error is carried through unchanged, so the failure stays visible rather than being
 * masked by a plausible-looking price.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class FallbackTariff implements TariffProvider {

    private final TariffProvider primary;
    private final TariffProvider fallback;

    public FallbackTariff(TariffProvider primary, TariffProvider fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    /** True when this snapshot came from the fallback rather than the primary feed. */
    public static boolean isFallback(TariffSnapshot snap) {
        return snap.lastError() != null && !Double.isNaN(snap.nowPriceEurPerKWh());
    }

    @Override
    public String kind() {
        return primary.kind();
    }

    @Override
    public TariffSnapshot snapshot(Instant now) {
        TariffSnapshot snap = primary.snapshot(now);
        if (!Double.isNaN(snap.nowPriceEurPerKWh())) {
            return snap;
        }
        TariffSnapshot alt = fallback.snapshot(now);
        return new TariffSnapshot(alt.refreshedAt(), alt.nowPriceEurPerKWh(), alt.next1hPriceEurPerKWh(),
                alt.todayMinPrice(), alt.todayMaxPrice(), alt.todayAvgPrice(), alt.cheapestHourStart(),
                alt.mostExpensiveHourStart(), alt.schedule24h(), alt.schedule48h(), snap.lastError());
    }
}

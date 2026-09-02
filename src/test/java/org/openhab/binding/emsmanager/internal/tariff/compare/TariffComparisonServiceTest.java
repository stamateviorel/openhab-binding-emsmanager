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
package org.openhab.binding.emsmanager.internal.tariff.compare;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * The published schedule already carries the site's own markup. Building Tibber, aWATTar and Engie
 * on top of it charged their fees on top of that markup, and every dynamic provider lost the ranking
 * to the flat contract by construction.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class TariffComparisonServiceTest {

    private static final double RAW = 0.10;
    private static final double MARKUP = 0.12;

    private static double[] schedule() {
        double[] s = new double[24];
        Arrays.fill(s, RAW + MARKUP);
        return s;
    }

    @Test
    void otherProvidersAreBuiltOnRawSpotNotOnTheSiteMarkup() {
        Map<String, double[]> curves = TariffComparisonService.candidateCurves(0.30, schedule(), MARKUP);

        double[] tibber = curves.get("Tibber");
        assertNotNull(tibber);
        assertEquals(RAW * 1.21 + 0.045, tibber[0], 1e-9, "Tibber's fees go on raw spot, once");
        double[] engie = curves.get("Engie Dynamic");
        assertNotNull(engie);
        assertEquals(1.15 * RAW + 0.015, engie[0], 1e-9);
    }

    @Test
    void theSiteOwnDynamicContractIsComparedAsPublished() {
        Map<String, double[]> curves = TariffComparisonService.candidateCurves(0.30, schedule(), MARKUP);

        double[] own = curves.get("ENTSO-E spot");
        assertNotNull(own);
        assertEquals(RAW + MARKUP, own[0], 1e-9, "that row IS the configured contract, markup included");
    }

    @Test
    void withoutAScheduleOnlyTheFormulaTariffsCompete() {
        Map<String, double[]> curves = TariffComparisonService.candidateCurves(0.30, null, MARKUP);

        assertEquals(2, curves.size());
    }
}

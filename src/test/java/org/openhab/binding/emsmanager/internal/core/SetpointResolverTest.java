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

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class SetpointResolverTest {

    private SetpointRequest req(String asset, SetpointRequest.Kind kind, double value, int prio, String who) {
        return new SetpointRequest(asset, kind, value, prio, who, "test");
    }

    @Test
    public void safetyOutranksALaterControllerOnTheSameAsset() {
        SetpointRequest pause = req("car1", SetpointRequest.Kind.PAUSE, 1.0, 10, "safety-breaker");
        SetpointRequest resume = req("car1", SetpointRequest.Kind.PAUSE, 0.0, 60, "ev-coordinator");

        SetpointResolver.Result r = SetpointResolver.resolve(List.of(pause, resume));

        assertEquals(1, r.winners().size());
        assertEquals("safety-breaker", r.winners().get(0).controllerName());
        assertEquals(1.0, r.winners().get(0).value());
        assertEquals(List.of(resume), r.dropped());
    }

    @Test
    public void differentKindsOnOneAssetDoNotCompete() {
        SetpointRequest amps = req("car1", SetpointRequest.Kind.AMPS, 16.0, 60, "ev-coordinator");
        SetpointRequest pause = req("car1", SetpointRequest.Kind.PAUSE, 1.0, 10, "safety-breaker");

        SetpointResolver.Result r = SetpointResolver.resolve(List.of(amps, pause));

        assertEquals(2, r.winners().size());
        assertTrue(r.dropped().isEmpty());
    }

    @Test
    public void differentAssetsDoNotCompete() {
        SetpointResolver.Result r = SetpointResolver
                .resolve(List.of(req("car1", SetpointRequest.Kind.PAUSE, 1.0, 10, "a"),
                        req("car2", SetpointRequest.Kind.PAUSE, 1.0, 10, "a")));

        assertEquals(2, r.winners().size());
        assertTrue(r.dropped().isEmpty());
    }

    @Test
    public void aTieKeepsTheRequestSeenFirst() {
        SetpointRequest first = req("boiler", SetpointRequest.Kind.ONOFF, 1.0, 70, "first");
        SetpointRequest second = req("boiler", SetpointRequest.Kind.ONOFF, 0.0, 70, "second");

        SetpointResolver.Result r = SetpointResolver.resolve(List.of(first, second));

        assertEquals(List.of(first), r.winners());
        assertEquals(List.of(second), r.dropped());
    }

    @Test
    public void theWinnerIsIndependentOfArrivalOrder() {
        SetpointRequest safety = req("car1", SetpointRequest.Kind.PAUSE, 1.0, 10, "safety-breaker");
        SetpointRequest late = req("car1", SetpointRequest.Kind.PAUSE, 0.0, 90, "self-consumption");

        assertEquals("safety-breaker",
                SetpointResolver.resolve(List.of(safety, late)).winners().get(0).controllerName());
        assertEquals("safety-breaker",
                SetpointResolver.resolve(List.of(late, safety)).winners().get(0).controllerName());
    }

    @Test
    public void anEmptyTickResolvesToNothing() {
        SetpointResolver.Result r = SetpointResolver.resolve(List.of());
        assertTrue(r.winners().isEmpty());
        assertTrue(r.dropped().isEmpty());
    }

    @Test
    public void survivingRequestsKeepTheirOriginalOrder() {
        SetpointRequest a = req("boiler", SetpointRequest.Kind.ONOFF, 1.0, 67, "boiler-plan");
        SetpointRequest b = req("car1", SetpointRequest.Kind.AMPS, 16.0, 60, "ev-coordinator");
        SetpointRequest c = req("battery", SetpointRequest.Kind.WATTS_BATTERY, -2000, 80, "battery-tou");

        assertEquals(List.of(a, b, c), SetpointResolver.resolve(List.of(a, b, c)).winners());
    }
}

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

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.types.Command;

/**
 * Tests for {@link EmsActuator#toCommand} — the mapping from an {@link EmsAction} to the item
 * command (the actuation half of #3478).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class EmsActuatorTest {

    @Test
    void onOffMapsToSwitchCommand() {
        assertEquals(OnOffType.ON, EmsActuator.toCommand(new EmsAction("X", EmsAction.Kind.ONOFF, 1.0, "")));
        assertEquals(OnOffType.OFF, EmsActuator.toCommand(new EmsAction("X", EmsAction.Kind.ONOFF, 0.0, "")));
    }

    @Test
    void setWattsMapsToNumericCommand() {
        Command c = EmsActuator.toCommand(new EmsAction("Wallbox", EmsAction.Kind.SET_WATTS, 4200.0, ""));
        DecimalType d = assertInstanceOf(DecimalType.class, c);
        assertEquals(4200.0, d.doubleValue(), 1e-9);
    }

    @Test
    void setModeMapsToStringCommandAndHoldToNone() {
        org.openhab.core.types.Command c = EmsActuator
                .toCommand(new EmsAction("WP", EmsAction.Kind.SET_MODE, 2.0, "encouraged", "mode"));
        org.openhab.core.library.types.StringType st = assertInstanceOf(org.openhab.core.library.types.StringType.class,
                c);
        assertEquals("encouraged", st.toString());
        assertNull(EmsActuator.toCommand(new EmsAction("WP", EmsAction.Kind.SET_MODE, 0.0, "gated")),
                "mode without a string (shed by a gate) -> no command");
        assertNull(EmsActuator.toCommand(new EmsAction("DW", EmsAction.Kind.HOLD, 0.0, "waiting")),
                "hold -> no command");
    }
}

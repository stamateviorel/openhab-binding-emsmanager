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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * The {@link EnergyParticipantProvider} whiteboard merge: service-registered participants join
 * the metadata-tagged ones, metadata wins on a duplicate id, and a broken registrant is skipped.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class MetadataParticipantScannerTest {

    private static EnergyConsumer consumer(String id, int priority) {
        return new EnergyConsumer(id, new PowerProfile.Simple(id), 0, -1, null, null, priority);
    }

    @Test
    void serviceParticipantsMergeAndMetadataWins() {
        EnergyConsumer tagged = consumer("Boiler", 10);
        EnergyConsumer registeredDuplicate = consumer("Boiler", 99); // must lose to the tag
        EnergyConsumer registeredNew = consumer("HeatPump", 30);
        EnergyParticipantProvider good = () -> List.of(registeredDuplicate, registeredNew);
        EnergyParticipantProvider broken = () -> {
            throw new IllegalStateException("registrant bug");
        };
        List<EnergyParticipant> merged = MetadataParticipantScanner.mergeParticipants(List.of(tagged),
                List.of(broken, good));
        assertEquals(2, merged.size(), "duplicate collapsed, broken provider skipped");
        assertEquals(10, ((EnergyConsumer) merged.get(0)).priority(), "metadata definition wins on duplicate id");
        assertEquals("HeatPump", merged.get(1).id(), "service-registered participant joins");
        assertEquals(1, MetadataParticipantScanner.mergeParticipants(List.of(tagged), List.of()).size(),
                "no providers -> tagged only");
    }
}

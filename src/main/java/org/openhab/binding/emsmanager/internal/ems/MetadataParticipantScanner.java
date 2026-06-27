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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.items.Metadata;
import org.openhab.core.items.MetadataRegistry;

/**
 * Discovers {@link EnergyParticipant}s by scanning the openHAB {@link MetadataRegistry} for
 * items carrying {@code energy} metadata — the realisation of Kai Kreuzer's idea
 * (openhab-core #3478) to "use our metadata infrastructure to mark all items that we want to
 * use for our energy management" via a new {@code energy:} namespace. Zero-config: any item
 * the user tags is picked up automatically; no item-name patterns required.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class MetadataParticipantScanner {

    private final MetadataRegistry metadataRegistry;

    public MetadataParticipantScanner(MetadataRegistry metadataRegistry) {
        this.metadataRegistry = metadataRegistry;
    }

    /** All energy participants currently tagged in the item model. */
    public List<EnergyParticipant> scan() {
        List<EnergyParticipant> out = new ArrayList<>();
        for (Metadata md : metadataRegistry.getAll()) {
            if (EnergyMetadataParser.NAMESPACE.equals(md.getUID().getNamespace())) {
                EnergyMetadataParser.parse(md.getUID().getItemName(), md.getValue(), md.getConfiguration())
                        .ifPresent(out::add);
            }
        }
        return out;
    }

    public List<EnergyProvider> providers() {
        List<EnergyProvider> out = new ArrayList<>();
        for (EnergyParticipant p : scan()) {
            if (p instanceof EnergyProvider ep) {
                out.add(ep);
            }
        }
        return out;
    }

    public List<EnergyConsumer> consumers() {
        List<EnergyConsumer> out = new ArrayList<>();
        for (EnergyParticipant p : scan()) {
            if (p instanceof EnergyConsumer ec) {
                out.add(ec);
            }
        }
        return out;
    }
}

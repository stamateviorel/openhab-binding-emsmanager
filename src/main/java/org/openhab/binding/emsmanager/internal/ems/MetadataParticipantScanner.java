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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

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
    private final Supplier<? extends Collection<EnergyParticipantProvider>> serviceProviders;

    public MetadataParticipantScanner(MetadataRegistry metadataRegistry) {
        this(metadataRegistry, List::of);
    }

    /**
     * @param serviceProviders live view of the {@link EnergyParticipantProvider} OSGi services
     *            (whiteboard) whose participants are merged with the metadata-tagged ones
     */
    public MetadataParticipantScanner(MetadataRegistry metadataRegistry,
            Supplier<? extends Collection<EnergyParticipantProvider>> serviceProviders) {
        this.metadataRegistry = metadataRegistry;
        this.serviceProviders = serviceProviders;
    }

    /** All energy participants: {@code energy:}-tagged items plus service-registered ones. */
    public List<EnergyParticipant> scan() {
        List<EnergyParticipant> tagged = new ArrayList<>();
        for (Metadata md : metadataRegistry.getAll()) {
            if (EnergyMetadataParser.NAMESPACE.equals(md.getUID().getNamespace())) {
                EnergyMetadataParser.parse(md.getUID().getItemName(), md.getValue(), md.getConfiguration())
                        .ifPresent(tagged::add);
            }
        }
        return mergeParticipants(tagged, serviceProviders.get());
    }

    /**
     * Merge metadata-tagged participants with service-registered ones. The metadata path wins on a
     * duplicate id (a site owner's explicit tag overrides a binding's registration), and a broken
     * provider is skipped rather than failing the whole scan.
     */
    static List<EnergyParticipant> mergeParticipants(List<EnergyParticipant> tagged,
            Collection<EnergyParticipantProvider> providers) {
        List<EnergyParticipant> out = new ArrayList<>(tagged);
        Set<String> seen = new HashSet<>();
        for (EnergyParticipant p : tagged) {
            seen.add(p.id());
        }
        for (EnergyParticipantProvider provider : providers) {
            try {
                for (EnergyParticipant p : provider.participants()) {
                    if (seen.add(p.id())) {
                        out.add(p);
                    }
                }
            } catch (RuntimeException e) {
                // a misbehaving registrant must not take down the engine tick
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

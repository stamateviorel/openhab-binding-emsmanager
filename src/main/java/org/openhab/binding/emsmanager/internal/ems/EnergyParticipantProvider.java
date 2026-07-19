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

import java.util.Collection;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Registration interface for energy participants — an in-binding <b>preview of the core half</b>
 * of Kai Kreuzer's #3478 design, where "bindings implement services like EnergyProvider or
 * EnergyConsumer" and the energy-management service discovers them. Any bundle can register an
 * implementation as an OSGi service; the engine picks it up via the whiteboard pattern and merges
 * its participants with the {@code energy:}-metadata-tagged items (the metadata path wins on a
 * duplicate id, so a site owner's explicit tag always overrides a binding's registration).
 *
 * <p>
 * The interface is deliberately minimal and item-based so it can move to openhab-core verbatim:
 * a binding contributes {@link EnergyParticipant}s referencing the items it already exposes, and
 * everything else (live state, actuation) flows through the item bus as usual.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface EnergyParticipantProvider {

    /** The participants this provider currently contributes. Re-read every engine tick. */
    Collection<EnergyParticipant> participants();
}

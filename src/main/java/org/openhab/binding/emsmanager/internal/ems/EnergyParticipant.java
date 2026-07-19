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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Marker for anything the energy-management service manages — an {@link EnergyProvider} or
 * an {@link EnergyConsumer}. Mirrors the {@code EnergyManagementParticipant} concept from
 * Kai Kreuzer's core design (openhab-core #3478). The {@link #id()} is the openHAB item the
 * participant is wired to, since "the wiring lives at the item level".
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface EnergyParticipant {

    /** The item this participant is bound to. */
    String id();
}

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
package org.openhab.binding.emsmanager.internal.capability;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * A snapshot of the site's energy capabilities for one tick. The abstraction
 * point that decouples the EMS from <em>how</em> each reading is obtained: the
 * shipped implementation reads openHAB items (see {@code ItemSiteReader}), but
 * an implementation backed by Thing channels, a fieldbus, or computed values
 * could be dropped in without touching controllers.
 *
 * <p>
 * All readings are already normalized to the binding's canonical sign
 * convention (grid {@code + = export}, battery {@code + = charging}).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface Site {

    EnergyReading grid();

    EnergyReading solar();

    EnergyReading house();

    Battery battery();

    ControllableLoad boiler();

    ControllableLoad airco();
}

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
 * A switchable load the EMS can shed or boost (boiler, aircon group, …). Read
 * side only here; writes go through the binding's asset handlers.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface ControllableLoad {

    /** Stable identifier (e.g. {@code boiler}, {@code airco-group}). */
    String id();

    /** Whether the load is currently energized. */
    boolean on();

    /** Simple immutable load state. */
    record Of(String id, boolean on) implements ControllableLoad {
        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean on() {
            return on;
        }
    }
}

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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Describes <em>how</em> an {@link EnergyConsumer} draws power, mirroring Kai Kreuzer's
 * {@code PowerProfile} concept from the core energy-management design (openhab-core #3478),
 * extended to the four consumer classes observed across two independent production EMS
 * implementations (see the #3478 discussion and {@code docs/ENERGY_TAXONOMY.md}):
 * <ul>
 * <li>{@link Simple} — a Switch-like load that only reacts to ON/OFF (boiler, immersion
 * heater stage).</li>
 * <li>{@link Controllable} — a Number-like load that is regulated to a commanded power
 * (in watts) within {@code [minW, maxW]}, e.g. a wallbox or a battery.</li>
 * <li>{@link ModeControllable} — a load that only accepts a small ordered set of discrete
 * modes and has <b>no direct power setpoint</b>, e.g. an SG-ready heat pump
 * (block / normal / encourage / force).</li>
 * <li>{@link Batch} — a fixed-program load where the planner only chooses the <b>start
 * moment</b>; once started, the program runs untouched (dishwasher, washing machine).</li>
 * </ul>
 * The {@code itemName} is the item through which the load is actually controlled.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface PowerProfile {

    /** The item this load is controlled through (commanded ON/OFF, to a watt value, or to a mode). */
    String itemName();

    /**
     * A simple on/off load (Kai's {@code SimplePowerProfile}), with the optional protection
     * parameters both production systems converged on — the full {@code {min,max} x {on,off}}
     * symmetry (minutes, 0 = unconstrained):
     * <ul>
     * <li>{@code minOnMinutes} — once started, run at least this long (doubles as a cooling
     * device's "catch-up" time after a forced re-start);</li>
     * <li>{@code maxOnMinutes} — never run longer than this in one stretch;</li>
     * <li>{@code minOffMinutes} — cooldown: once stopped, stay off at least this long;</li>
     * <li>{@code maxOffMinutes} — never stay off longer than this (M. Storm's fridge/freezer
     * case: interruptible, but food safety bounds the off-time — the planner force-restarts).</li>
     * </ul>
     * {@code thresholdW} optionally overrides the planner's global simple-load surplus threshold
     * for this consumer (storm.house's per-device {@code Schwellwert}, typically the rated power;
     * {@code NaN} = use the global). {@code runAtLevel} (storm.house's {@code Schaltniveau})
     * makes the load purely energy-level-driven: 1..3 = ON whenever the site level is at least
     * that value (OFF below), 4+ = never, 0/absent = the surplus/deadline path.
     */
    record Simple(String itemName, double thresholdW, int runAtLevel, int minOnMinutes, int maxOnMinutes,
            int minOffMinutes, int maxOffMinutes) implements PowerProfile {
        /** Unconstrained on/off load. */
        public Simple(String itemName) {
            this(itemName, Double.NaN, 0, 0, 0, 0, 0);
        }

        /** Protection-only constructor (per-consumer threshold/level unset). */
        public Simple(String itemName, int minOnMinutes, int maxOnMinutes, int minOffMinutes, int maxOffMinutes) {
            this(itemName, Double.NaN, 0, minOnMinutes, maxOnMinutes, minOffMinutes, maxOffMinutes);
        }

        @Override
        public String itemName() {
            return itemName;
        }
    }

    /** A load regulated to a commanded watt value in {@code [minW, maxW]} (Kai's {@code ControllablePowerProfile}). */
    record Controllable(String itemName, double minW, double maxW) implements PowerProfile {
        @Override
        public String itemName() {
            return itemName;
        }
    }

    /**
     * A load driven through a small <b>ordered</b> list of discrete modes — index 0 is the most
     * restricted (e.g. SG-ready "blocked"), the last index the most encouraged (e.g. "forced
     * on") — with no direct power setpoint. The planner picks an index from the current energy
     * availability and the commanded value is the mode <em>string</em> itself, so the item can
     * be a String item, a Number item (numeric mode names), or map to SG-ready contacts.
     */
    record ModeControllable(String itemName, List<String> modes) implements PowerProfile {
        @Override
        public String itemName() {
            return itemName;
        }
    }

    /**
     * A fixed-program ("batch") load: the planner only chooses when to <b>start</b> it —
     * ideally in the cheapest contiguous window that still finishes before the consumer's
     * deadline, at the latest {@code runtimeHours} before that deadline — and never interrupts
     * a running program. {@code ratedW} is the (rough) draw used to decide whether live surplus
     * can carry it ({@code NaN} → the planner's simple-load threshold is used instead).
     * The optional {@code shape} — normalized fractions of {@code ratedW} per hour slot, e.g.
     * {@code [0.1, 1.0, 0.2]} (M. Storm's profile idea, #3478) — refines the start choice to
     * shape-weighted price costing; empty = rectangular, and a non-empty shape defines the runtime.
     */
    record Batch(String itemName, double ratedW, double runtimeHours, List<Double> shape) implements PowerProfile {
        /** Rectangular profile (no shape). */
        public Batch(String itemName, double ratedW, double runtimeHours) {
            this(itemName, ratedW, runtimeHours, List.of());
        }

        @Override
        public String itemName() {
            return itemName;
        }
    }
}

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
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.Command;

/**
 * Applies an {@link EmsAction} to its item — the actuation half of Kai Kreuzer's design
 * (openhab-core #3478): a {@link PowerProfile.Simple} load receives ON/OFF, a
 * {@link PowerProfile.Controllable} load receives its commanded watt value. Used only when the
 * energy-management engine is explicitly taken out of shadow; otherwise the plan is log-only.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EmsActuator {

    private final EventPublisher eventPublisher;
    private final ItemRegistry itemRegistry;

    public EmsActuator(EventPublisher eventPublisher, ItemRegistry itemRegistry) {
        this.eventPublisher = eventPublisher;
        this.itemRegistry = itemRegistry;
    }

    /** Map an action to the command it sends (pure, for testing). HOLD deliberately maps to none. */
    public static @Nullable Command toCommand(EmsAction action) {
        switch (action.kind()) {
            case ONOFF:
                return action.value() > 0 ? OnOffType.ON : OnOffType.OFF;
            case SET_WATTS:
                return new DecimalType(action.value());
            case SET_MODE:
                String mode = action.stringValue();
                return mode != null ? StringType.valueOf(mode) : null;
            case HOLD:
            default:
                return null;
        }
    }

    /** Send the action's command to its item; returns false if the item is missing or unmappable. */
    public boolean apply(EmsAction action) {
        Command command = toCommand(action);
        if (command == null) {
            return false;
        }
        try {
            itemRegistry.getItem(action.itemName());
        } catch (ItemNotFoundException e) {
            return false;
        }
        eventPublisher.post(ItemEventFactory.createCommandEvent(action.itemName(), command));
        return true;
    }
}

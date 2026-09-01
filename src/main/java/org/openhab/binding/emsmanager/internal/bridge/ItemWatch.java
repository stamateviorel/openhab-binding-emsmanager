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
package org.openhab.binding.emsmanager.internal.bridge;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.common.registry.RegistryChangeListener;
import org.openhab.core.items.GenericItem;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.StateChangeListener;
import org.openhab.core.types.State;

/**
 * Watches a fixed set of Items across their whole life, not just the instances that exist today.
 * <p>
 * A {@link StateChangeListener} attaches to an Item <em>instance</em>. When an items file is reloaded
 * the registry replaces every instance it defines, and a listener attached to the old one never
 * hears from the new one - the watch silently goes deaf until the bridge is re-initialised. This
 * follows the registry and re-attaches, and also records when each Item was last updated, which
 * is what tells a metering bridge apart from a frozen one.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ItemWatch implements RegistryChangeListener<Item>, StateChangeListener {

    private final ItemRegistry registry;
    private final Set<String> names;
    private final BiConsumer<String, State> onChange;
    private final Map<String, GenericItem> attached = new ConcurrentHashMap<>();
    private final Map<String, Long> lastUpdateMs = new ConcurrentHashMap<>();

    public ItemWatch(ItemRegistry registry, Collection<String> names, BiConsumer<String, State> onChange) {
        this.registry = registry;
        this.names = Set.copyOf(names);
        this.onChange = onChange;
    }

    public void start() {
        registry.addRegistryChangeListener(this);
        for (String name : names) {
            Item item = registry.get(name);
            if (item != null) {
                attach(item);
            }
        }
    }

    public void stop() {
        registry.removeRegistryChangeListener(this);
        for (GenericItem item : attached.values()) {
            item.removeStateChangeListener(this);
        }
        attached.clear();
    }

    /** How many of the watched Items currently exist. */
    public int attachedCount() {
        return attached.size();
    }

    /** Whether any of the named Items exists at all. */
    public boolean watchesAnyOf(Collection<String> some) {
        for (String name : some) {
            if (attached.containsKey(name)) {
                return true;
            }
        }
        return false;
    }

    /** Epoch millis of the most recent update among the given Items, or 0 if none has updated yet. */
    public long newestUpdateMs(Collection<String> some) {
        long newest = 0L;
        for (String name : some) {
            Long t = lastUpdateMs.get(name);
            if (t != null && t > newest) {
                newest = t;
            }
        }
        return newest;
    }

    private void attach(Item item) {
        if (!names.contains(item.getName()) || !(item instanceof GenericItem generic)) {
            return;
        }
        GenericItem previous = attached.put(item.getName(), generic);
        if (previous != null && previous != generic) {
            previous.removeStateChangeListener(this);
        }
        generic.addStateChangeListener(this);
    }

    private void detach(Item item) {
        GenericItem previous = attached.remove(item.getName());
        if (previous != null) {
            previous.removeStateChangeListener(this);
        }
    }

    @Override
    public void added(Item element) {
        attach(element);
    }

    @Override
    public void removed(Item element) {
        detach(element);
    }

    @Override
    public void updated(Item oldElement, Item element) {
        // The registry hands out a new instance on every items-file reload; the old one is dead.
        detach(oldElement);
        attach(element);
    }

    @Override
    public void stateChanged(Item item, State oldState, State newState) {
        lastUpdateMs.put(item.getName(), System.currentTimeMillis());
        onChange.accept(item.getName(), newState);
    }

    @Override
    public void stateUpdated(Item item, State state) {
        // A same-value update is still proof the source is alive.
        lastUpdateMs.put(item.getName(), System.currentTimeMillis());
    }
}

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
package org.openhab.binding.emsmanager.internal;

import static org.openhab.binding.emsmanager.internal.EmsManagerBindingConstants.*;

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.emsmanager.internal.bridge.EmsManagerBridgeHandler;
import org.openhab.binding.emsmanager.internal.charger.ChargerHandler;
import org.openhab.binding.emsmanager.internal.devicemeter.DeviceMeterHandler;
import org.openhab.binding.emsmanager.internal.ems.EnergyParticipantProvider;
import org.openhab.binding.emsmanager.internal.forecast.ForecastSolarHandler;
import org.openhab.binding.emsmanager.internal.heatpump.HeatPumpAssetHandler;
import org.openhab.binding.emsmanager.internal.tariff.TariffHandler;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.MetadataRegistry;
import org.openhab.core.persistence.PersistenceServiceRegistry;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

/**
 * Creates handlers for the EMS Manager binding.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.emsmanager", service = ThingHandlerFactory.class)
public class EmsManagerHandlerFactory extends BaseThingHandlerFactory {

    /**
     * Whiteboard of {@link EnergyParticipantProvider} OSGi services — the preview of #3478's
     * "bindings implement services" half. The live list is handed to the bridge so dynamically
     * (un)registered providers are seen on the next engine tick without a reinitialize.
     */
    private final java.util.List<EnergyParticipantProvider> participantProviders = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    protected void addEnergyParticipantProvider(EnergyParticipantProvider provider) {
        participantProviders.add(provider);
    }

    protected void removeEnergyParticipantProvider(EnergyParticipantProvider provider) {
        participantProviders.remove(provider);
    }

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Set.of(THING_TYPE_BRIDGE,
            THING_TYPE_FORECAST_SOLAR, THING_TYPE_TARIFF, THING_TYPE_DEVICE_METER, THING_TYPE_HEATPUMP,
            THING_TYPE_CHARGER);

    private final ItemRegistry itemRegistry;
    private final MetadataRegistry metadataRegistry;
    private final EventPublisher eventPublisher;
    private final HttpClient httpClient;
    private final ThingRegistry thingRegistry;
    private final PersistenceServiceRegistry persistenceRegistry;

    @Activate
    public EmsManagerHandlerFactory(@Reference ItemRegistry itemRegistry, @Reference MetadataRegistry metadataRegistry,
            @Reference EventPublisher eventPublisher, @Reference HttpClientFactory httpClientFactory,
            @Reference ThingRegistry thingRegistry, @Reference PersistenceServiceRegistry persistenceRegistry) {
        this.itemRegistry = itemRegistry;
        this.metadataRegistry = metadataRegistry;
        this.eventPublisher = eventPublisher;
        this.httpClient = httpClientFactory.getCommonHttpClient();
        this.thingRegistry = thingRegistry;
        this.persistenceRegistry = persistenceRegistry;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (THING_TYPE_BRIDGE.equals(thingTypeUID)) {
            return new EmsManagerBridgeHandler((Bridge) thing, participantProviders, itemRegistry, metadataRegistry,
                    eventPublisher, thingRegistry, persistenceRegistry, httpClient);
        }
        if (THING_TYPE_FORECAST_SOLAR.equals(thingTypeUID)) {
            return new ForecastSolarHandler(thing, httpClient);
        }
        if (THING_TYPE_TARIFF.equals(thingTypeUID)) {
            return new TariffHandler(thing, httpClient);
        }
        if (THING_TYPE_DEVICE_METER.equals(thingTypeUID)) {
            return new DeviceMeterHandler(thing, itemRegistry);
        }
        if (THING_TYPE_HEATPUMP.equals(thingTypeUID)) {
            return new HeatPumpAssetHandler(thing);
        }
        if (THING_TYPE_CHARGER.equals(thingTypeUID)) {
            return new ChargerHandler(thing);
        }

        return null;
    }
}

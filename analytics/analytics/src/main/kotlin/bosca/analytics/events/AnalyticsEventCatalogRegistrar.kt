package bosca.analytics.events

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.events.catalog.EventCatalogFields
import bosca.events.catalog.EventCatalogRegistrar
import bosca.events.catalog.EventDescriptor
import kotlinx.serialization.KSerializer

/**
 * Contributes the batch-level analytics event names ([AnalyticsEventNames]) to the platform Event
 * Catalog.
 *
 * Analytics events are synthesized by the processor's transform chain rather than declared with
 * `@JobEvent`, so KSP generates no catalog entry for them. Without this registrar two things break:
 * the fire-and-forget dispatch path cannot rebuild the payload — `PipelineRunJobExecutor` looks the
 * serializer up by event name via [EventCatalogRegistrar.serializers] and fails with "No catalogued
 * serializer" — and the events do not appear in the pipeline editor's event picker.
 *
 * Both events carry an [AnalyticsScriptEvent] (the whole batch), so each maps to that one serializer.
 */
class AnalyticsEventCatalogRegistrar : EventCatalogRegistrar {

    override val events: List<EventDescriptor> = listOf(
        descriptor(
            fqdn = AnalyticsEventNames.TRANSFORM,
            displayName = "Analytics batch (transform)",
            description = "Runs inline as each analytics event batch is processed; a triggered " +
                "pipeline may transform the whole batch before it is stored.",
        ),
        descriptor(
            fqdn = AnalyticsEventNames.NOTIFY,
            displayName = "Analytics batch (notify)",
            description = "Fires and forgets for each analytics event batch so triggered pipelines " +
                "can react without blocking or modifying ingestion.",
        ),
    )

    override val serializers: Map<String, KSerializer<*>> =
        AnalyticsEventNames.all.associateWith { AnalyticsScriptEvent.serializer() }

    private fun descriptor(fqdn: String, displayName: String, description: String) = EventDescriptor(
        fqdn = fqdn,
        displayName = displayName,
        description = description,
        pubsubChannel = null,
        jobNames = emptyList(),
        fields = EventCatalogFields.of(AnalyticsScriptEvent.serializer().descriptor),
    )
}

/**
 * Registers the [AnalyticsEventCatalogRegistrar] as a named provider so
 * [EventCatalogService][bosca.events.catalog.EventCatalogService] aggregates it via
 * `ProviderRegistry.findAll`. The name matches the module's provider prefix, mirroring the
 * KSP-generated registrars of `@JobEvent`-bearing modules.
 */
@Providers
class AnalyticsEventCatalogConfiguration {

    @Provider(singleton = true, name = "Analytics")
    fun analyticsEventCatalogRegistrar(): EventCatalogRegistrar = AnalyticsEventCatalogRegistrar()
}

package bosca.events.catalog

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.service.annotation.ServiceImplementation

/**
 * Aggregates the per-module [EventCatalogRegistrar]s registered in the DI container into a
 * single, queryable catalog. Each registrar is a named provider, so
 * [ProviderRegistry.findAll] returns one per loaded module that declares events.
 */
@ServiceImplementation
class EventCatalogServiceImpl : EventCatalogService {

    @OptIn(InternalDI::class)
    override suspend fun list(): List<EventDescriptor> =
        ProviderRegistry.findAll(EventCatalogRegistrar::class)
            .filter { it.exists }
            .flatMap { it.get().events }
            .distinctBy { it.fqdn }
            .sortedBy { it.fqdn }

    override suspend fun get(fqdn: String): EventDescriptor? =
        list().firstOrNull { it.fqdn == fqdn }
}

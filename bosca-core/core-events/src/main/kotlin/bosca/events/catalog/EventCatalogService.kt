package bosca.events.catalog

import bosca.service.Service

/**
 * Read access to the platform Event Catalog — the aggregated set of [EventDescriptor]s
 * contributed by every loaded module's [EventCatalogRegistrar].
 *
 * The catalog is assembled from compile-time [@JobEvent][bosca.events.annotation.JobEvent]
 * declarations, so newly added events appear automatically with no manual registration.
 */
interface EventCatalogService : Service {

    /** All catalogued events across loaded modules, de-duplicated and ordered by [EventDescriptor.fqdn]. */
    suspend fun list(): List<EventDescriptor>

    /** The descriptor for [fqdn], or `null` if no such event is catalogued. */
    suspend fun get(fqdn: String): EventDescriptor?
}

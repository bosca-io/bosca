package bosca.events.catalog.graphql

import bosca.events.catalog.EventCatalogService
import bosca.events.catalog.EventDescriptor
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext

/**
 * Resolves the `EventCatalog` GraphQL type. The catalog describes platform events for building
 * triggers and automation rules, so reads are restricted to platform administrators (the `sa`
 * or `administrators` group), mirroring the other system/platform query surfaces.
 */
@TypeController
class EventCatalogController(
    private val service: EventCatalogService,
) : GraphQLController<EventCatalog> {

    @Field
    suspend fun events(authenticationContext: AuthenticationContext): List<EventDescriptor> {
        if (!authenticationContext.isPlatformAdmin()) return emptyList()
        return service.list()
    }

    @Field
    suspend fun event(authenticationContext: AuthenticationContext, fqdn: String): EventDescriptor? {
        if (!authenticationContext.isPlatformAdmin()) return null
        return service.get(fqdn)
    }

    private suspend fun AuthenticationContext.isPlatformAdmin(): Boolean {
        val principal = principal() ?: return false
        return principal.hasGroup("sa") || principal.hasGroup("administrators")
    }
}

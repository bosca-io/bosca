package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute
import bosca.system.ui.generated.SystemTokenOwnerSearch
import bosca.system.ui.graphql.SystemAccess
import bosca.system.ui.graphql.SystemTokenOwners

/** Resolves visible profiles to the principal that owns their API tokens. */
class TokenOwnerSearch : SystemTokenOwnerSearch {
    /** Searches the administration index under the caller's authenticated session. */
    override suspend fun search(gql: GraphQLClient, query: String): List<Map<String, String>> {
        require(gql.execute(SystemAccess, Unit).security.principals.current.groups.any { it.name in setOf("sa", "administrators") }) {
            "Administrator access required to select another account."
        }
        require(query.trim().length >= 2) { "Enter at least two characters." }
        return gql.execute(SystemTokenOwners, SystemTokenOwners.Variables(query.trim())).search.search
            ?.documents.orEmpty().mapNotNull { it.profile }.mapNotNull { profile ->
                profile.principal?.let { mapOf("id" to it.id, "label" to profile.name.ifBlank { profile.id }) }
            }.distinctBy { it.getValue("id") }
    }
}

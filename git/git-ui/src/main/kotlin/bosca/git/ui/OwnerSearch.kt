package bosca.git.ui

import bml.generated.GitOwnerSearch
import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute
import bosca.git.ui.graphql.*

class OwnerSearch : GitOwnerSearch {
    /** Searches only profiles visible through the existing platform search API. */
    override suspend fun search(gql: GraphQLClient, query: String): List<Map<String, String>> {
        require(query.trim().length >= 2) { "Enter at least two characters." }
        return gql.execute(GitUiOwners, GitUiOwners.Variables(query.trim()))
            .search
            .search
            ?.documents
            .orEmpty()
            .mapNotNull { it.profile }
            .distinctBy { it.id }
            .map {
                mapOf(
                    "id" to it.id,
                    "name" to it.name,
                    "label" to OwnerOption(it.id, it.name, it.slug.orEmpty()).label,
                )
            }
    }
}

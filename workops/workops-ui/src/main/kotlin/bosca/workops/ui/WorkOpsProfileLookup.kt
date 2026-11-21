package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute
import bosca.workops.ui.generated.WorkOpsProfileSearch
import bosca.workops.ui.graphql.WorkOpsProfileChoices

/** Supplies visible assignee/mention candidates through the existing profile search surface. */
class WorkOpsProfileLookup : WorkOpsProfileSearch {
    override suspend fun search(gql: GraphQLClient, query: String, offset: Int): List<Map<String, String>> {
        require(query.trim().length >= 2) { "Enter at least two characters." }
        require(offset >= 0) { "Offset must be non-negative." }
        return gql.execute(WorkOpsProfileChoices, WorkOpsProfileChoices.Variables(query.trim(), offset))
            .search.search?.documents.orEmpty().mapNotNull { it.profile }.distinctBy { it.id }
            .map { mapOf("id" to it.id, "label" to it.name, "handle" to it.slug.orEmpty()) }
    }
}

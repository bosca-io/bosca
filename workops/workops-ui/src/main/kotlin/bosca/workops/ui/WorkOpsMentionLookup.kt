package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute
import bosca.workops.ui.generated.WorkOpsMentionSearch
import bosca.workops.ui.graphql.WorkOpsMentionEntities
import bosca.workops.ui.graphql.WorkOpsProfileChoices

/** Supplies authorized mention identities while the shared library owns suggestion behavior. */
class WorkOpsMentionLookup : WorkOpsMentionSearch {
    override suspend fun profiles(gql: GraphQLClient, query: String): List<Map<String, String>> {
        require(query.matches(Regex("[A-Za-z0-9._-]*"))) { "Enter a profile handle." }
        return gql.execute(WorkOpsProfileChoices, WorkOpsProfileChoices.Variables(query, 0))
            .search.search?.documents.orEmpty().mapNotNull { it.profile }.distinctBy { it.id }
            .mapNotNull { profile ->
                val handle = profile.slug?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) } ?: return@mapNotNull null
                if (!profile.name.contains(query, ignoreCase = true) && !handle.contains(query, ignoreCase = true)) return@mapNotNull null
                mapOf("id" to profile.id, "label" to profile.name.ifBlank { handle }, "handle" to handle)
            }
    }

    override suspend fun entities(gql: GraphQLClient, query: String): List<Map<String, String>> {
        require(query.isNotBlank()) { "Enter a mention query." }
        return gql.execute(WorkOpsMentionEntities, WorkOpsMentionEntities.Variables(query.trim()))
            .search.search?.documents.orEmpty().mapNotNull { document ->
                when {
                    document.metadata != null -> mapOf("id" to document.metadata.id,
                        "label" to workOpsContentLabel(document.metadata.name, document.metadata.id, "Untitled document"), "entityType" to "metadata")
                    document.collection != null -> mapOf("id" to document.collection.id,
                        "label" to document.collection.name.ifBlank { "Untitled collection" }, "entityType" to "collection")
                    document.profile != null -> mapOf("id" to document.profile.id,
                        "label" to document.profile.name.ifBlank { document.profile.slug.orEmpty().ifBlank { "Unnamed profile" } }, "entityType" to "profile")
                    else -> null
                }
            }.distinctBy { it.getValue("entityType") to it.getValue("id") }
    }
}

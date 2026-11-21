package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute
import bosca.workops.ui.generated.WorkOpsContextChoices
import bosca.workops.ui.graphql.*
import kotlinx.serialization.json.*

/** WorkOps reference meanings stay here; the library owns async selection and offset navigation. */
class WorkOpsContextChoicesLookup : WorkOpsContextChoices {
    override suspend fun search(gql: GraphQLClient, kind: String, query: String, offset: Int): Map<String, String> {
        require(query.trim().length >= 2 && offset >= 0) { "Enter at least two characters and a valid offset." }
        if (kind == "SPEC") return WorkOpsSpecChoicesLookup().search(gql, query, offset)
        val options: List<Pair<String, String>> = when (kind) {
            "PROJECT" -> gql.execute(WorkOpsSpecCreateChoices, Unit).workOps.projects.all
                .filter { it.name.contains(query.trim(), ignoreCase = true) }.drop(offset).take(26).map { it.id to it.name }
            "TASK" -> gql.execute(WorkOpsContextTasks, WorkOpsContextTasks.Variables("summary ~ ${JsonPrimitive(query.trim())} ORDER BY modified DESC", offset.toLong()))
                .workOps.savedFilters.searchTasks.rows.map { it.id to workOpsContentLabel(it.summary, it.id, "Task") }
            "METADATA", "COLLECTION", "PROFILE" -> {
                val documents = gql.execute(WorkOpsContextItems, WorkOpsContextItems.Variables(query.trim(),
                    listOf("_type = \"${kind.lowercase()}\""), offset)).search.search?.documents.orEmpty()
                // Keep inaccessible entries in the raw page so they cannot hide its next offset.
                val raw = documents.map { item -> when (kind) {
                    "METADATA" -> item.metadata?.let { it.id to workOpsContentLabel(it.name, it.id, "Content") }
                    "COLLECTION" -> item.collection?.let { it.id to it.name.ifBlank { "Collection" } }
                    else -> item.profile?.let { it.id to it.name.ifBlank { "Profile" } }
                } }
                return page(raw, offset)
            }
            else -> error("Choose a searchable context type.")
        }
        return page(options, offset)
    }

    private fun page(raw: List<Pair<String, String>?>, offset: Int): Map<String, String> = buildMap {
        put("options", buildJsonArray { raw.take(25).filterNotNull().distinctBy { it.first }.forEach { (id, name) ->
            addJsonObject { put("value", id); put("label", name) }
        } }.toString())
        if (offset > 0) put("previousOffset", (offset - 25).coerceAtLeast(0).toString())
        if (raw.size > 25) put("nextOffset", (offset + 25).toString())
    }
}

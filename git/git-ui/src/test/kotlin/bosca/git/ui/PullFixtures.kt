package bosca.git.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal fun pullStateFixture(base: String, number: Int): String {
    if (number !in 2..5) return base
    val state = listOf("DRAFT", "MERGED", "CLOSED", "OPEN")[number - 2]
    val id = listOf("pull-draft", "pull-merged", "pull-closed", "pull-conflicts")[number - 2]
    val changes = buildJsonObject {
        put("id", id)
        put("number", number)
        put("status", state)
        put("mergeable", false)
        put(
            "title",
            when (number) {
                2 -> "Prepare search improvements"
                3 -> "Update repository previews"
                4 -> "Replace the branch selector"
                else -> "Resolve Markdown conflicts"
            },
        )
        if (number == 2) put("reviews", JsonArray(emptyList()))
        if (number == 3) {
            put("mergedAt", "2026-10-11T09:00:00Z")
            put("mergeSha", "abc")
            put("mergeStrategy", "SQUASH")
        }
        if (number == 5) {
            put(
                "conflictingFiles",
                JsonArray(listOf("docs/guide.md", "src/main.kt").map(::JsonPrimitive)),
            )
            val assignee = buildJsonObject {
                put("id", "owner")
                put("name", "Alice")
                put("slug", "alice")
            }
            put("assignees", JsonArray(listOf(assignee)))
            val dependency = buildJsonObject {
                put("id", "pull-draft")
                put("repositoryId", "repo")
                put("number", 2)
                put("title", "Prepare search improvements")
                put("status", "DRAFT")
            }
            put("dependencies", JsonArray(listOf(dependency)))
        }
    }
    return JsonObject(Json.parseToJsonElement(base).jsonObject + changes).toString()
}

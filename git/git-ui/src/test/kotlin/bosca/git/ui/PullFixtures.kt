package bosca.git.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal fun pullStateFixture(base: String, number: Int): String {
    if (number !in 2..6) return base
    val state = listOf("DRAFT", "MERGED", "CLOSED", "OPEN", "OPEN")[number - 2]
    val id = listOf("pull-draft", "pull-merged", "pull-closed", "pull-conflicts", "pull-empty")[number - 2]
    val changes = buildJsonObject {
        put("id", id)
        put("number", number)
        put("status", state)
        put("mergeable", number == 6)
        put(
            "title",
            when (number) {
                2 -> "Prepare search improvements"
                3 -> "Update repository previews"
                4 -> "Replace the branch selector"
                6 -> "Add initial folder"
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
        if (number == 6) {
            val now = java.time.OffsetDateTime.now().toString()
            put("created", now)
            put("updated", now)
            put("reviews", JsonArray(emptyList()))
            val hunk = buildJsonObject {
                put("oldStart", 1)
                put("oldCount", 0)
                put("newStart", 1)
                put("newCount", 0)
                put("totalLineCount", 0)
                put("lines", JsonArray(emptyList()))
            }
            val file = buildJsonObject {
                put("oldPath", JsonNull)
                put("newPath", "Test/.gitkeep")
                put("changeType", "ADD")
                put("hunks", JsonArray(listOf(hunk)))
            }
            put("diff", JsonArray(listOf(file)))
        }
    }
    return JsonObject(Json.parseToJsonElement(base).jsonObject + changes).toString()
}

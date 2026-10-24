package bosca.git.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A long, deeply nested path whose name must stay readable in the changed-file tree. */
internal const val CONTEXT_FILE = "src/main/kotlin/bosca/git/ui/review/expansion/ContextExpansionReviewFixtureWithAVeryLongName.kt"

/** New version of [CONTEXT_FILE]: line 5 changed and a line inserted after old line 59. */
internal val contextFileLines: List<String> = (1..101).map {
    when {
        it == 5 -> "changed 5"
        it == 60 -> "inserted"
        it < 60 -> "line $it"
        else -> "line ${it - 1}"
    }
}

/** The two windowed hunks the API returns for [CONTEXT_FILE]. */
private fun contextDiff(): JsonObject {
    fun line(type: String, old: Int?, new: Int?, content: String) = buildJsonObject {
        put("content", content)
        put("type", type)
        put("oldLineNumber", old?.let(::JsonPrimitive) ?: JsonNull)
        put("newLineNumber", new?.let(::JsonPrimitive) ?: JsonNull)
    }
    fun context(old: Int, new: Int) = line("CONTEXT", old, new, contextFileLines[new - 1])
    fun hunk(oldStart: Int, oldCount: Int, newStart: Int, newCount: Int, lines: List<JsonObject>) = buildJsonObject {
        put("oldStart", oldStart)
        put("oldCount", oldCount)
        put("newStart", newStart)
        put("newCount", newCount)
        put("totalLineCount", lines.size)
        put("lines", JsonArray(lines))
    }
    return buildJsonObject {
        put("oldPath", CONTEXT_FILE)
        put("newPath", CONTEXT_FILE)
        put("changeType", "MODIFY")
        put("newLineCount", contextFileLines.size)
        put(
            "hunks",
            JsonArray(
                listOf(
                    hunk(2, 7, 2, 7, listOf(context(2, 2), context(3, 3), context(4, 4),
                        line("DELETE", 5, null, "line 5"), line("ADD", null, 5, "changed 5"),
                        context(6, 6), context(7, 7), context(8, 8))),
                    hunk(57, 6, 57, 7, listOf(context(57, 57), context(58, 58), context(59, 59),
                        line("ADD", null, 60, "inserted"), context(60, 61), context(61, 62), context(62, 63))),
                )
            ),
        )
    }
}

internal fun pullStateFixture(base: String, number: Int): String {
    if (number == 13) return JsonObject(Json.parseToJsonElement(base).jsonObject + mapOf(
        "id" to JsonPrimitive("pull-checks"), "number" to JsonPrimitive(13),
        "title" to JsonPrimitive("Required checks pending"),
        "failingStatusChecks" to JsonArray(listOf(JsonPrimitive("ci/build"), JsonPrimitive("ci/missing"))),
    )).toString()
    if (number !in 2..12) return base
    val state = listOf("DRAFT", "MERGED", "CLOSED", "OPEN", "OPEN", "OPEN", "OPEN", "OPEN", "OPEN", "MERGED", "OPEN")[number - 2]
    val id = listOf("pull-draft", "pull-merged", "pull-closed", "pull-conflicts", "pull-empty", "pull-edit", "pull-missing-branch", "pull-first-comment", "pull-replies", "pull-merged-no-base", "pull-context")[number - 2]
    val changes = buildJsonObject {
        put("id", id)
        put("number", number)
        put("status", state)
        put("mergeable", number >= 6)
        put(
            "title",
            when (number) {
                2 -> "Prepare search improvements"
                3 -> "Update repository previews"
                4 -> "Replace the branch selector"
                6 -> "Add initial folder"
                7 -> "Editable pull request"
                8 -> "Repair missing source branch"
                9 -> "Start commenting"
                10 -> "Review discussion"
                12 -> "Expand review context"
                else -> "Resolve Markdown conflicts"
            },
        )
        if (number == 8) {
            put("sourceBranch", "missing")
            put("mergeable", false)
        }
        if (number == 2) put("reviews", JsonArray(emptyList()))
        if (number == 9) put("reviews", JsonArray(emptyList()))
        if (number == 10) {
            val review = Json.parseToJsonElement(base).jsonObject.getValue("reviews").let { it as JsonArray }.first().jsonObject
            val comment = (review.getValue("comments") as JsonArray).first().jsonObject
            val current = JsonObject(comment + mapOf("authorId" to JsonPrimitive("other"), "resolved" to JsonPrimitive(true)))
            val outdated = JsonObject(comment + mapOf("id" to JsonPrimitive("old-comment"), "authorId" to JsonPrimitive("other"),
                "filePath" to JsonPrimitive("old name.kt"), "oldLineNumber" to JsonPrimitive(3), "newLineNumber" to JsonNull,
                "commitSha" to JsonPrimitive("old-sha"), "content" to JsonPrimitive("Outdated discussion"), "outdated" to JsonPrimitive(true)))
            val fileComment = JsonObject(comment + mapOf("id" to JsonPrimitive("file-comment"),
                "authorId" to JsonPrimitive("other"), "oldLineNumber" to JsonNull, "newLineNumber" to JsonNull,
                "content" to JsonPrimitive("File-level discussion")))
            put("reviews", JsonArray(listOf(JsonObject(review + mapOf("reviewerId" to JsonPrimitive("other"),
                "comments" to JsonArray(listOf(current, outdated, fileComment)))))))
        }
        if (number == 12) {
            val review = Json.parseToJsonElement(base).jsonObject.getValue("reviews").let { it as JsonArray }.first().jsonObject
            val comment = (review.getValue("comments") as JsonArray).first().jsonObject
            val discussion = JsonObject(comment + mapOf("id" to JsonPrimitive("context-comment"),
                "authorId" to JsonPrimitive("other"), "filePath" to JsonPrimitive(CONTEXT_FILE),
                "oldLineNumber" to JsonPrimitive(40), "newLineNumber" to JsonPrimitive(40),
                "content" to JsonPrimitive("Context discussion"), "outdated" to JsonPrimitive(false)))
            put("reviews", JsonArray(listOf(JsonObject(review + mapOf("reviewerId" to JsonPrimitive("other"),
                "comments" to JsonArray(listOf(discussion)))))))
            put("diff", JsonArray(listOf(contextDiff())))
        }
        if (number == 3 || number == 11) {
            put("mergedAt", "2026-10-11T09:00:00Z")
            put("mergeSha", "merged-abc")
            put("mergeBaseSha", if (number == 3) JsonPrimitive("merge-base") else JsonNull)
            put("mergeStrategy", if (number == 3) "SQUASH" else "FAST_FORWARD")
            put("sourceBranch", "deleted-source")
            put("targetBranch", "deleted-target")
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
                put("newLineCount", 0)
            }
            put("diff", JsonArray(listOf(file)))
        }
    }
    return JsonObject(Json.parseToJsonElement(base).jsonObject + changes).toString()
}

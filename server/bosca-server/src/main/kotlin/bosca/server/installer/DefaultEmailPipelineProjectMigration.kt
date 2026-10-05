package bosca.server.installer

import bosca.pipelines.model.Pipeline
import bosca.pipelines.service.PipelineService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val LEGACY_EMAIL_PROJECT = "bosca-emails"
private const val MESSAGE_PROJECT = "bosca-messages"
private const val LEGACY_PAYLOAD_TYPE_PREFIX = "email:$LEGACY_EMAIL_PROJECT/"
private const val MESSAGE_PAYLOAD_TYPE_PREFIX = "email:$MESSAGE_PROJECT/"

/**
 * Updates only the generated project references in a stored default email pipeline. All other
 * graph fields remain structurally unchanged so operator edits survive.
 */
internal fun migrateDefaultEmailPipelineGraph(graph: JsonElement): JsonElement? {
    val root = graph as? JsonObject ?: return null
    val nodes = root["nodes"] as? JsonArray ?: return null
    var changed = false
    val migratedNodes = JsonArray(nodes.map { element ->
        val node = element as? JsonObject ?: return@map element
        val type = node["type"]?.jsonPrimitive?.contentOrNull
        val updates = when (type) {
            "sendEmailTemplate" -> buildMap {
                if (node["project"]?.jsonPrimitive?.contentOrNull == LEGACY_EMAIL_PROJECT) {
                    put("project", JsonPrimitive(MESSAGE_PROJECT))
                }
                val template = node["template"]?.jsonPrimitive?.contentOrNull
                if (template?.startsWith("$LEGACY_EMAIL_PROJECT/") == true) {
                    put("template", JsonPrimitive(MESSAGE_PROJECT + template.removePrefix(LEGACY_EMAIL_PROJECT)))
                }
            }

            "jsonata" -> buildMap {
                val outputType = node["outputType"]?.jsonPrimitive?.contentOrNull
                if (outputType?.startsWith(LEGACY_PAYLOAD_TYPE_PREFIX) == true) {
                    put(
                        "outputType",
                        JsonPrimitive(MESSAGE_PAYLOAD_TYPE_PREFIX + outputType.removePrefix(LEGACY_PAYLOAD_TYPE_PREFIX)),
                    )
                }
            }

            else -> emptyMap()
        }
        if (updates.isEmpty()) {
            element
        } else {
            changed = true
            JsonObject(node + updates)
        }
    })
    return if (changed) JsonObject(root + ("nodes" to migratedNodes)) else null
}

/** Migrates one stored default pipeline, preserving its metadata and optimistic-lock version. */
internal suspend fun PipelineService.migrateDefaultEmailPipeline(pipeline: Pipeline): Boolean {
    val migratedGraph = migrateDefaultEmailPipelineGraph(graphAsJsonElement(pipeline)) ?: return false
    save(
        id = pipeline.id,
        name = pipeline.name,
        description = pipeline.description,
        acceptedInputType = pipeline.acceptedInputType,
        tags = pipeline.tags,
        triggered = pipeline.triggered,
        key = pipeline.key,
        api = pipeline.api,
        public = pipeline.public,
        schedule = pipeline.schedule,
        maxConcurrentRuns = pipeline.maxConcurrentRuns,
        maxRunsPerMinute = pipeline.maxRunsPerMinute,
        version = pipeline.version,
        graph = migratedGraph,
    )
    return true
}

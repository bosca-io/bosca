package bosca.git.ui

import java.time.Duration
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun pipelineElapsed(started: String?, finished: String?): String =
    if (started == null || finished == null) "—"
    else pipelineDuration(
        Duration.between(OffsetDateTime.parse(started), OffsetDateTime.parse(finished))
            .seconds.coerceAtLeast(0)
    )

internal fun artifactRequirementLabels(value: JsonElement?): List<String> =
    requirementObjects(value).map { requirement ->
        val type = requirement.label("type") ?: "artifact"
        val namespace = requirement.label("namespace")?.let { "$it / " }.orEmpty()
        val coordinate = requirement.label("coordinate") ?: "unknown coordinate"
        "$namespace$coordinate ($type)"
    }

internal fun pipelineRequirementLabels(value: JsonElement?): List<String> =
    requirementObjects(value).map { requirement ->
        val repository = requirement.label("repository") ?: "unknown repository"
        val pipeline = requirement.label("pipeline") ?: "unknown pipeline"
        val ref = requirement.label("ref") ?: "this ref"
        "$repository / $pipeline @ $ref"
    }

internal fun pipelineMatrixLabels(value: JsonElement?): List<String> =
    (value as? JsonObject)?.entries?.map { (key, value) ->
        "$key: ${(value as? JsonPrimitive)?.content ?: value}"
    }.orEmpty()

private fun requirementObjects(value: JsonElement?): List<JsonObject> =
    (value as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

private fun JsonObject.label(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

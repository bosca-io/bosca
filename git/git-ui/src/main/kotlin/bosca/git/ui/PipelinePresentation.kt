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

/** Studio's status colours for runs, jobs, agents and commit checks. */
internal fun statusColor(status: String): String = when (status) {
    "SUCCESS", "ONLINE" -> "var(--ok)"
    "FAILURE", "FAILED", "ERROR", "TIMED_OUT" -> "var(--err)"
    "RUNNING", "BUSY" -> "var(--brand-2)"
    "QUEUED", "PENDING", "DRAINING", "WAITING_FOR_APPROVAL" -> "var(--warn)"
    "CANCELLED", "SKIPPED", "OFFLINE" -> "var(--fg-4)"
    else -> "var(--fg-3)"
}

/** Agent heartbeat age in Studio's `149h ago` form. */
internal fun heartbeatAge(value: String?): String {
    if (value.isNullOrBlank()) return "never"
    val timestamp =
        try {
            java.time.OffsetDateTime.parse(value).toInstant()
        } catch (_: java.time.format.DateTimeParseException) {
            return value
        }
    val seconds = java.time.Duration.between(timestamp, java.time.Instant.now()).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        else -> "${seconds / 3600}h ago"
    }
}

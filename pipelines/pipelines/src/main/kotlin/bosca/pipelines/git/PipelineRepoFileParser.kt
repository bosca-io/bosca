package bosca.pipelines.git

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

/** A pipeline definition parsed from a YAML file in a PIPELINE_PROJECT repository. */
data class PipelineFile(
    val path: String,
    val name: String,
    val description: String,
    val acceptedInputType: String,
    /** Free-form categorization labels; empty = untagged. */
    val tags: List<String>,
    val triggered: Boolean,
    /** REST endpoint key; empty = not endpoint-exposed. */
    val key: String,
    /** Whether the pipeline is callable via its REST endpoint. */
    val api: Boolean,
    /** Whether the REST endpoint is callable without an EXECUTE grant. */
    val public: Boolean,
    /** Cron expression for scheduled durable execution; null when not scheduled. */
    val schedule: String?,
    /** The node/edge graph in the same shape `PipelineService.save` accepts (`{nodes, edges}`). */
    val graph: JsonElement,
)

/**
 * Outcome of parsing a single file from a PIPELINE_PROJECT repository: a typed [PipelineFile], a
 * `ParseError` with a human-readable message, or `UnknownPath` for files this sync does not own
 * (outside the pipelines directory, or not a YAML file).
 */
sealed class ParsedPipelineFile {
    data class Parsed(val file: PipelineFile) : ParsedPipelineFile()
    data class ParseError(val path: String, val message: String) : ParsedPipelineFile()
    data class UnknownPath(val path: String) : ParsedPipelineFile()
}

/**
 * Parses a single pipeline YAML file into a typed [ParsedPipelineFile]. Pure — no IO, no DB.
 * Errors are returned as `ParseError` values rather than thrown so a batch caller can collect all
 * parse failures before deciding what to do. Graph validation against the node registry is NOT
 * done here (it needs the aggregated `SerializersModule`); callers run
 * `PipelineService.validateGraph` on the returned [PipelineFile.graph].
 */
class PipelineRepoFileParser {

    fun parse(path: String, content: String): ParsedPipelineFile {
        if (!PipelineRepoLayout.isPipelineFile(path)) return ParsedPipelineFile.UnknownPath(path)
        val root = try {
            @Suppress("UNCHECKED_CAST")
            (Yaml().load(content) as? Map<String, Any?>)
                ?: return ParsedPipelineFile.ParseError(path, "file must be a YAML mapping")
        } catch (e: YAMLException) {
            return ParsedPipelineFile.ParseError(path, "malformed YAML: ${e.message}")
        }
        val errors = mutableListOf<String>()
        val name = root.requireString("name", errors)
        val acceptedInputType = root.requireString("accepted_input_type", errors)
        val description = root.optionalString("description", errors) ?: ""
        val tags = root.optionalStringList("tags", errors)
        val triggered = root.optionalBoolean("triggered", errors) ?: false
        val key = root.optionalString("key", errors) ?: ""
        val api = root.optionalBoolean("api", errors) ?: false
        val public = root.optionalBoolean("public", errors) ?: false
        val schedule = root.optionalString("schedule", errors)?.takeIf { it.isNotBlank() }
        val nodes = root.optionalList("nodes", errors)
        val edges = root.optionalList("edges", errors)
        if (errors.isNotEmpty() || name == null || acceptedInputType == null) {
            return ParsedPipelineFile.ParseError(path, errors.joinToString("; "))
        }
        val graph = JsonObject(
            mapOf(
                "nodes" to JsonArray((nodes ?: emptyList()).map { toJsonElement(it) }),
                "edges" to JsonArray((edges ?: emptyList()).map { toJsonElement(it) }),
            )
        )
        return ParsedPipelineFile.Parsed(
            PipelineFile(
                path = path,
                name = name,
                description = description,
                acceptedInputType = acceptedInputType,
                tags = tags,
                triggered = triggered,
                key = key,
                api = api,
                public = public,
                schedule = schedule,
                graph = graph,
            )
        )
    }

    private fun Map<String, Any?>.requireString(key: String, errors: MutableList<String>): String? {
        val value = this[key]
        return when (value) {
            is String -> value
            null -> { errors += "missing required field '$key'"; null }
            else -> { errors += "field '$key' must be a string (got ${value::class.simpleName})"; null }
        }
    }

    private fun Map<String, Any?>.optionalString(key: String, errors: MutableList<String>): String? {
        val value = this[key] ?: return null
        return when (value) {
            is String -> value
            else -> { errors += "field '$key' must be a string (got ${value::class.simpleName})"; null }
        }
    }

    private fun Map<String, Any?>.optionalBoolean(key: String, errors: MutableList<String>): Boolean? {
        val value = this[key] ?: return null
        return when (value) {
            is Boolean -> value
            else -> { errors += "field '$key' must be a boolean (got ${value::class.simpleName})"; null }
        }
    }

    private fun Map<String, Any?>.optionalList(key: String, errors: MutableList<String>): List<Any?>? {
        val value = this[key] ?: return null
        return when (value) {
            is List<*> -> value
            else -> { errors += "field '$key' must be a list (got ${value::class.simpleName})"; null }
        }
    }

    /** A list of string labels (e.g. `tags`); missing = empty. Each element must be a string. */
    private fun Map<String, Any?>.optionalStringList(key: String, errors: MutableList<String>): List<String> {
        val value = this[key] ?: return emptyList()
        val list = value as? List<*> ?: run {
            errors += "field '$key' must be a list (got ${value::class.simpleName})"
            return emptyList()
        }
        return list.mapNotNull { element ->
            when (element) {
                is String -> element
                else -> { errors += "field '$key' entries must be strings (got ${element?.let { it::class.simpleName }})"; null }
            }
        }
    }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value.toDouble())
        is Number -> JsonPrimitive(value.toDouble())
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) })
        is List<*> -> JsonArray(value.map { toJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }
}

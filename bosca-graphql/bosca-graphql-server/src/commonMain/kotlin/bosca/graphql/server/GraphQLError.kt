package bosca.graphql.server

import bosca.graphql.language.SourceLocation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A single GraphQL error (the response `errors` entries). [path] segments are field names ([String]) and list
 * indices ([Int]); [locations] come from the foundation's [SourceLocation]; [extensions] are an open bag.
 */
data class GraphQLError(
    val message: String,
    val locations: List<SourceLocation> = emptyList(),
    val path: List<Any> = emptyList(),
    val extensions: Map<String, JsonElement>? = null,
) {
    /** The spec-compliant JSON object: `message`, plus `locations`/`path`/`extensions` only when present. */
    fun toJson(): JsonObject = buildJsonObject {
        put("message", message)
        if (locations.isNotEmpty()) {
            put("locations", JsonArray(locations.map { buildJsonObject { put("line", it.line); put("column", it.column) } }))
        }
        if (path.isNotEmpty()) put("path", JsonArray(path.map { pathSegment(it) }))
        extensions?.takeIf { it.isNotEmpty() }?.let { put("extensions", JsonObject(it)) }
    }
}

private fun pathSegment(segment: Any): JsonElement = when (segment) {
    is Int -> JsonPrimitive(segment)
    else -> JsonPrimitive(segment.toString())
}

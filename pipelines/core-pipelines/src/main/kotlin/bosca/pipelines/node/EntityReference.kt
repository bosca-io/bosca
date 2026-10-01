package bosca.pipelines.node

import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A reference to a platform entity extracted from a value flowing through a pipeline: the entity
 * [id] plus an optional [version] for versioned domains (e.g. metadata).
 */
class EntityReference(val id: UUID, val version: Int? = null)

/**
 * Extracts an [EntityReference] from this value, so FETCH (resolver) nodes work anywhere in the
 * graph — wired directly to the Input node (typed event) or downstream of a JSONata/Combine node
 * (plain JSON).
 *
 * The value is bridged to JSON via its own carried serializer (typed events serialize their id
 * fields as UUID strings; already-JSON values pass through), then the first matching field in
 * [idFields] is parsed as a UUID and [versionField] as an Int. Returns `null` when no field
 * matches — callers should raise an error naming the node. No reflection.
 *
 * @param idFields candidate id field names, tried in order (domains with non-`id` event fields
 *   pass theirs first, e.g. `["taskId", "id"]`)
 */
fun PipelineValue.entityReference(
    json: Json,
    idFields: List<String> = listOf("id"),
    versionField: String = "version",
): EntityReference? {
    val obj = encode(json) as? JsonObject ?: return null
    val id = idFields.firstNotNullOfOrNull { field ->
        (obj[field] as? JsonPrimitive)?.content?.let { raw ->
            try {
                UUID.parse(raw)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    } ?: return null
    val version = (obj[versionField] as? JsonPrimitive)?.intOrNull
    return EntityReference(id, version)
}

/**
 * Reads this value as a bare entity identifier — a single [UUID]. The value is bridged to JSON via
 * its carried serializer (a UUID-typed value serializes to a UUID string; a plain JSON string passes
 * through) and parsed; an object, array, or non-UUID string yields `null`. Used by the "Get X"
 * resolver nodes, which take the entity's id directly — callers raise an error naming the node when
 * this returns `null`. No reflection.
 */
fun PipelineValue.uuid(json: Json): UUID? {
    val raw = (encode(json) as? JsonPrimitive)?.contentOrNull ?: return null
    return try {
        UUID.parse(raw)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * An [EntityReference] from EITHER a resolved entity object carrying the id field(s) OR a bare [UUID]
 * — for "Get X" nodes that operate on an entity and so accept the entity itself (e.g. a Metadata,
 * carrying its `id` and `version`) as readily as its id. The object form is tried first so a passed
 * entity's [EntityReference.version] is honored; a bare UUID resolves the latest version. Returns
 * `null` when the value is neither — callers raise an error naming the node. No reflection.
 */
fun PipelineValue.entityRef(json: Json, idFields: List<String> = listOf("id")): EntityReference? =
    entityReference(json, idFields) ?: uuid(json)?.let { EntityReference(it) }

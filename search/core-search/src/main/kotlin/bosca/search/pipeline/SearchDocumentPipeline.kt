package bosca.search.pipeline

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The contract shared between the entity-specific **Build Search Document** pipeline nodes (which
 * produce the search document for an entity and a target index) and the generic **Index Document**
 * node (which writes that JSON to the index). Lives in `core-search` so both `content` (the Build
 * nodes) and `search` (the Index node) can reference it without depending on each other's
 * implementation.
 *
 * A built document is either:
 *  - the search document itself — a JSON object, or a JSON array of documents for collections — to be
 *    upserted into the index; or
 *  - a **removal signal**: a JSON object carrying [ACTION_FIELD] = [ACTION_DELETE] and the entity's
 *    [CONTENT_ID_FIELD], emitted when the entity is not visible for the target index. The Index node
 *    honors the signal by deleting that content id, preserving the "index when visible, else remove"
 *    behavior the index job executors apply per index.
 */
object SearchDocumentPipeline {

    /** Marker field set on a built document to request removal instead of upsert. */
    const val ACTION_FIELD: String = "__action"

    /** The [ACTION_FIELD] value requesting the Index node remove the [CONTENT_ID_FIELD]. */
    const val ACTION_DELETE: String = "delete"

    /** The content id field carried by both real documents and removal signals; the index removal key. */
    const val CONTENT_ID_FIELD: String = "contentId"

    /** The platform's primary, public content search index. */
    const val DEFAULT_INDEX: String = "Default Search Index"

    /** The platform's public profile-only search index. */
    const val PROFILE_INDEX: String = "Profile Search Index"

    /** The platform's admin content search index (includes unpublished content). */
    const val ADMIN_INDEX: String = "Admin Search Index"

    /**
     * The content id a built [document] requests be removed, or `null` if it is a normal document to
     * upsert. A removal signal is a JSON object whose [ACTION_FIELD] is [ACTION_DELETE] with a string
     * [CONTENT_ID_FIELD]; anything else (an array, a non-object, a missing/blank action, a missing or
     * non-string content id) is treated as a normal document. Pure and reflection-free.
     */
    fun removalContentId(document: JsonElement): String? {
        val obj = document as? JsonObject ?: return null
        if ((obj[ACTION_FIELD] as? JsonPrimitive)?.contentOrNull != ACTION_DELETE) return null
        return (obj[CONTENT_ID_FIELD] as? JsonPrimitive)?.contentOrNull
    }

    /**
     * The distinct content ids carried by a built [document] — one object, or an array of objects (a
     * collection's per-variant documents). Elements that are not objects, or carry no string content
     * id, are skipped. Used to clear an entity's existing documents before a `replace` index.
     */
    fun contentIds(document: JsonElement): List<String> {
        val documents = if (document is JsonArray) document else listOf(document)
        return documents
            .mapNotNull { (it as? JsonObject)?.get(CONTENT_ID_FIELD) as? JsonPrimitive }
            .mapNotNull { it.contentOrNull }
            .distinct()
    }
}

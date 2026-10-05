package bosca.content.collaboration

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import yks.types.YMap
import yks.utils.Doc
import yks.utils.applyUpdate
import yks.utils.encodeStateAsUpdate

/**
 * A template attribute reduced to the fields needed to encode it into a
 * collaboration document — decoupling the writer from the concrete template
 * attribute types (collection / document / data templates).
 */
data class CollaborationAttribute(
    val key: String,
    val type: AttributeType,
    val list: Boolean,
    val location: AttributeLocation?,
    val configuration: kotlinx.serialization.json.JsonElement?,
)

/**
 * A metadata relationship enriched with the related metadata's display fields,
 * ready to be encoded into a collaboration document's `metadata`/`metadatas` map.
 */
data class CollaborationRelationship(
    val metadataId: UUID,
    val relationship: String,
    val attributes: JsonElement?,
    val name: String,
    val contentType: String,
)

/** A parent collection, ready to be encoded into a `collection`/`collections` map. */
data class CollaborationParent(
    val id: UUID,
    val name: String,
    val attributes: JsonElement?,
)

/**
 * Writes a collection's attribute values into its collaboration Yjs document so
 * the editor — which reads attribute values from that document, not from the
 * persisted columns — never drifts out of sync after a server-side change.
 *
 * The encoding mirrors the client's `AttributeState` exactly:
 *  - [AttributeType.STRING]                      -> `"textAttributes"`   (key -> string)
 *  - [AttributeType.INT] / [AttributeType.FLOAT] -> `"numberAttributes"` (key -> number)
 *  - [AttributeType.DATE] / DATETIME / DATE_TIME -> `"dateAttributes"`   (key -> epoch millis)
 *  - [AttributeType.METADATA]                    -> `"metadata"` (single) / `"metadatas"` (list),
 *      each entry a JSON string `{id, relationship, contentType, attributes, name}`
 *  - [AttributeType.COLLECTION]                  -> `"collection"` (single) / `"collections"` (list),
 *      each entry a JSON string `{id, name}`
 *
 * The update is merged into the existing document so unrelated state is preserved.
 */
object CollaborationAttributeWriter {

    private const val TEXT_MAP = "textAttributes"
    private const val NUMBER_MAP = "numberAttributes"
    private const val DATE_MAP = "dateAttributes"
    private const val METADATA_MAP = "metadata"
    private const val METADATAS_MAP = "metadatas"
    private const val COLLECTION_MAP = "collection"
    private const val COLLECTIONS_MAP = "collections"

    /**
     * Applies the collection's full attribute state onto [content] (an encoded
     * Yjs state, or null/empty to start fresh) and returns the re-encoded state.
     */
    fun apply(
        content: ByteArray?,
        templateAttributes: List<CollaborationAttribute>,
        attributes: JsonElement?,
        relationships: List<CollaborationRelationship>,
        parents: List<CollaborationParent>,
    ): ByteArray {
        val doc = Doc()
        if (content != null && content.isNotEmpty()) {
            applyUpdate(doc, content)
        }
        val attrs = attributes as? JsonObject
        val textMap = doc.getMap(TEXT_MAP)
        val numberMap = doc.getMap(NUMBER_MAP)
        val dateMap = doc.getMap(DATE_MAP)
        val metadataMap = doc.getMap(METADATA_MAP)
        val metadatasMap = doc.getMap(METADATAS_MAP)
        val collectionMap = doc.getMap(COLLECTION_MAP)
        val collectionsMap = doc.getMap(COLLECTIONS_MAP)

        for (attribute in templateAttributes) {
            // Only ITEM-located attributes live on the entity itself; RELATIONSHIP
            // attributes are stored on relationship edges, not in these maps.
            if (attribute.location != null && attribute.location != AttributeLocation.ITEM) continue
            when (attribute.type) {
                AttributeType.STRING ->
                    setOrDelete(textMap, attribute.key, stringValue(findValue(attribute.key, attrs)))
                AttributeType.INT, AttributeType.FLOAT ->
                    setOrDelete(numberMap, attribute.key, numberValue(findValue(attribute.key, attrs)))
                AttributeType.DATE, AttributeType.DATETIME, AttributeType.DATE_TIME ->
                    setOrDelete(dateMap, attribute.key, millisValue(findValue(attribute.key, attrs)))
                AttributeType.METADATA ->
                    applyMetadata(attribute, relationships, metadataMap, metadatasMap)
                AttributeType.COLLECTION ->
                    applyCollection(attribute, parents, collectionMap, collectionsMap)
                AttributeType.PROFILE -> {
                    // Not represented in the collaboration document.
                }
            }
        }
        return encodeStateAsUpdate(doc)
    }

    private fun applyMetadata(
        attribute: CollaborationAttribute,
        relationships: List<CollaborationRelationship>,
        metadataMap: YMap,
        metadatasMap: YMap,
    ) {
        val relationshipName = configString(attribute, "relationship")
        val matching = relationships.filter { it.relationship == relationshipName }
        if (attribute.list) {
            if (matching.isEmpty()) {
                if (metadatasMap.has(attribute.key)) metadatasMap.delete(attribute.key)
            } else {
                metadatasMap.set(attribute.key, JsonArray(matching.map { metadataJson(it) }).toString())
            }
        } else {
            val match = matching.firstOrNull()
            if (match == null) {
                if (metadataMap.has(attribute.key)) metadataMap.delete(attribute.key)
            } else {
                metadataMap.set(attribute.key, metadataJson(match).toString())
            }
        }
    }

    private fun applyCollection(
        attribute: CollaborationAttribute,
        parents: List<CollaborationParent>,
        collectionMap: YMap,
        collectionsMap: YMap,
    ) {
        val configType = configString(attribute, "type")
        val matching = parents.filter { parentType(it.attributes) == configType }
        if (attribute.list) {
            if (matching.isEmpty()) {
                if (collectionsMap.has(attribute.key)) collectionsMap.delete(attribute.key)
            } else {
                collectionsMap.set(attribute.key, JsonArray(matching.map { collectionJson(it) }).toString())
            }
        } else {
            val match = matching.firstOrNull()
            if (match == null) {
                if (collectionMap.has(attribute.key)) collectionMap.delete(attribute.key)
            } else {
                collectionMap.set(attribute.key, collectionJson(match).toString())
            }
        }
    }

    private fun metadataJson(relationship: CollaborationRelationship): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(relationship.metadataId.toString()),
            "relationship" to JsonPrimitive(relationship.relationship),
            "contentType" to JsonPrimitive(relationship.contentType),
            "attributes" to (relationship.attributes ?: JsonObject(emptyMap())),
            "name" to JsonPrimitive(relationship.name),
        )
    )

    private fun collectionJson(parent: CollaborationParent): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(parent.id.toString()),
            "name" to JsonPrimitive(parent.name),
        )
    )

    private fun parentType(attributes: JsonElement?): String? =
        ((attributes as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull

    private fun configString(attribute: CollaborationAttribute, key: String): String? =
        ((attribute.configuration as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun setOrDelete(map: YMap, key: String, value: Any?) {
        if (value == null) {
            if (map.has(key)) map.delete(key)
        } else {
            map.set(key, value)
        }
    }

    private fun stringValue(value: JsonElement?): String? {
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.ifEmpty { null }
    }

    private fun numberValue(value: JsonElement?): Any? {
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.doubleOrNull
    }

    private fun millisValue(value: JsonElement?): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
    }

    /**
     * Resolves an attribute key against the attributes object, supporting both a
     * flat key and a dotted nested path (e.g. `"video.hls"`), matching the
     * client's `findValue`.
     */
    private fun findValue(key: String, attributes: JsonObject?): JsonElement? {
        if (attributes == null) return null
        attributes[key]?.let { return it }
        if (key.contains('.')) {
            var current: JsonElement = attributes
            for (segment in key.split('.')) {
                val obj = current as? JsonObject ?: return null
                current = obj[segment] ?: return null
            }
            return current
        }
        return null
    }
}

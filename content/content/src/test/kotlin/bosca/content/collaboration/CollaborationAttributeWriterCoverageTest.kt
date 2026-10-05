package bosca.content.collaboration

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import yks.utils.Doc
import yks.utils.applyUpdate
import yks.utils.encodeStateAsUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Branch-completion coverage for [CollaborationAttributeWriter] that complements
 * [CollaborationAttributeWriterTest]. This file targets the arms the existing
 * test does not reach: PROFILE, DATETIME/DATE_TIME, list metadata/collection
 * delete/no-op paths, the null-config / null-parent-type resolvers, numeric and
 * millis coercion fallbacks, nested-path miss cases, and the empty-content path.
 */
class CollaborationAttributeWriterCoverageTest {

    private fun attribute(
        key: String,
        type: AttributeType,
        list: Boolean = false,
        location: AttributeLocation? = AttributeLocation.ITEM,
        configuration: JsonElement? = null,
    ) = CollaborationAttribute(
        key = key,
        type = type,
        list = list,
        location = location,
        configuration = configuration,
    )

    private fun apply(
        content: ByteArray?,
        templateAttributes: List<CollaborationAttribute>,
        attributes: JsonElement?,
        relationships: List<CollaborationRelationship> = emptyList(),
        parents: List<CollaborationParent> = emptyList(),
    ) = CollaborationAttributeWriter.apply(content, templateAttributes, attributes, relationships, parents)

    private fun decode(content: ByteArray): Doc {
        val doc = Doc()
        applyUpdate(doc, content)
        return doc
    }

    private fun mapValue(content: ByteArray, map: String, key: String): Any? =
        decode(content).getMap(map).get(key)

    private fun parsed(content: ByteArray, map: String, key: String): JsonElement =
        Json.parseToJsonElement(mapValue(content, map, key) as String)

    // ── content branches ───────────────────────────────────────────────────────

    @Test
    fun `empty content byte array starts a fresh document`() {
        // content != null but isNotEmpty() == false -> applyUpdate is skipped.
        val content = apply(ByteArray(0), listOf(attribute("title", AttributeType.STRING)), buildJsonObject { put("title", "fresh") })
        assertEquals("fresh", mapValue(content, "textAttributes", "title"))
    }

    @Test
    fun `null attributes object leaves scalar maps empty`() {
        // attributes as? JsonObject == null (a non-object element) -> findValue returns null.
        val content = apply(null, listOf(attribute("title", AttributeType.STRING)), JsonArray(emptyList()))
        assertFalse(decode(content).getMap("textAttributes").has("title"))
    }

    // ── enum arms not otherwise reached ────────────────────────────────────────

    @Test
    fun `PROFILE attribute is ignored`() {
        val content = apply(
            null,
            listOf(attribute("owner", AttributeType.PROFILE)),
            buildJsonObject { put("owner", "someone") },
        )
        val doc = decode(content)
        assertFalse(doc.getMap("textAttributes").has("owner"))
        assertFalse(doc.getMap("numberAttributes").has("owner"))
        assertFalse(doc.getMap("dateAttributes").has("owner"))
    }

    @Test
    fun `DATETIME and DATE_TIME both write into the date map`() {
        val content = apply(
            null,
            listOf(
                attribute("dt", AttributeType.DATETIME),
                attribute("dt2", AttributeType.DATE_TIME),
            ),
            buildJsonObject {
                put("dt", 111L)
                put("dt2", 222L)
            },
        )
        assertEquals(111L, (mapValue(content, "dateAttributes", "dt") as Number).toLong())
        assertEquals(222L, (mapValue(content, "dateAttributes", "dt2") as Number).toLong())
    }

    // ── numberValue / millisValue coercion ─────────────────────────────────────

    @Test
    fun `float date value is coerced to millis via toLong`() {
        // millisValue: longOrNull == null, doubleOrNull != null -> toLong().
        val content = apply(
            null,
            listOf(attribute("when", AttributeType.DATE)),
            buildJsonObject { put("when", 1234.9) },
        )
        assertEquals(1234L, (mapValue(content, "dateAttributes", "when") as Number).toLong())
    }

    @Test
    fun `non-numeric number attribute is dropped`() {
        // numberValue: longOrNull and doubleOrNull both null -> null -> setOrDelete removes.
        val content = apply(
            null,
            listOf(attribute("count", AttributeType.INT)),
            buildJsonObject { put("count", "not-a-number") },
        )
        assertFalse(decode(content).getMap("numberAttributes").has("count"))
    }

    @Test
    fun `non-numeric date attribute is dropped`() {
        val content = apply(
            null,
            listOf(attribute("when", AttributeType.DATE)),
            buildJsonObject { put("when", "not-a-date") },
        )
        assertFalse(decode(content).getMap("dateAttributes").has("when"))
    }

    @Test
    fun `object-valued number attribute is not a primitive and is dropped`() {
        // numberValue: value !is JsonPrimitive -> null.
        val content = apply(
            null,
            listOf(attribute("count", AttributeType.INT)),
            buildJsonObject { put("count", buildJsonObject { put("nested", 1) }) },
        )
        assertFalse(decode(content).getMap("numberAttributes").has("count"))
    }

    @Test
    fun `object-valued date attribute is not a primitive and is dropped`() {
        val content = apply(
            null,
            listOf(attribute("when", AttributeType.DATE)),
            buildJsonObject { put("when", buildJsonObject { put("nested", 1) }) },
        )
        assertFalse(decode(content).getMap("dateAttributes").has("when"))
    }

    @Test
    fun `object-valued string attribute is not a primitive and is dropped`() {
        // stringValue: value !is JsonPrimitive -> null.
        val content = apply(
            null,
            listOf(attribute("title", AttributeType.STRING)),
            buildJsonObject { put("title", buildJsonObject { put("nested", "x") }) },
        )
        assertFalse(decode(content).getMap("textAttributes").has("title"))
    }

    // ── setOrDelete: delete-branch when key not present is a clean no-op ────────

    @Test
    fun `clearing an already-absent scalar key is a no-op`() {
        // First write nothing (empty string => null => setOrDelete null with has()==false).
        val content = apply(
            null,
            listOf(attribute("title", AttributeType.STRING)),
            buildJsonObject { put("title", "") },
        )
        assertFalse(decode(content).getMap("textAttributes").has("title"))
    }

    // ── findValue branches ─────────────────────────────────────────────────────

    @Test
    fun `flat non-dotted missing key resolves to null`() {
        // key has no '.', not present -> returns null (final return null).
        val content = apply(
            null,
            listOf(attribute("missing", AttributeType.STRING)),
            buildJsonObject { put("present", "value") },
        )
        assertFalse(decode(content).getMap("textAttributes").has("missing"))
    }

    @Test
    fun `dotted path where a mid segment is not an object resolves to null`() {
        // video is a primitive, so the nested walk hits `current as? JsonObject == null`.
        val content = apply(
            null,
            listOf(attribute("video.hls", AttributeType.STRING)),
            buildJsonObject { put("video", "a-string-not-an-object") },
        )
        assertFalse(decode(content).getMap("textAttributes").has("video.hls"))
    }

    @Test
    fun `dotted path where a segment is missing resolves to null`() {
        // video exists as object, but .hls is absent -> obj[segment] == null.
        val content = apply(
            null,
            listOf(attribute("video.hls", AttributeType.STRING)),
            buildJsonObject { put("video", buildJsonObject { put("other", "x") }) },
        )
        assertFalse(decode(content).getMap("textAttributes").has("video.hls"))
    }

    @Test
    fun `null string primitive value is dropped`() {
        // stringValue on JsonNull: contentOrNull == null -> null.
        val content = apply(
            null,
            listOf(attribute("title", AttributeType.STRING)),
            buildJsonObject { put("title", JsonNull) },
        )
        assertFalse(decode(content).getMap("textAttributes").has("title"))
    }

    // ── applyMetadata: list branch ─────────────────────────────────────────────

    @Test
    fun `metadata list with no matches and no prior key is a clean no-op`() {
        // list == true, matching empty, metadatasMap.has(key) == false.
        val template = listOf(
            attribute("gallery", AttributeType.METADATA, list = true,
                configuration = buildJsonObject { put("relationship", "gallery") }),
        )
        val content = apply(null, template, buildJsonObject { }, relationships = emptyList())
        assertFalse(decode(content).getMap("metadatas").has("gallery"))
    }

    @Test
    fun `metadata list with no matches deletes a prior key`() {
        // Seed a list, then re-apply with no matching relationships -> delete branch.
        val template = listOf(
            attribute("gallery", AttributeType.METADATA, list = true,
                configuration = buildJsonObject { put("relationship", "gallery") }),
        )
        val seeded = apply(null, template, buildJsonObject { }, relationships = listOf(
            CollaborationRelationship(UUID.random(), "gallery", null, "A", "image/jpeg"),
        ))
        assertTrue(decode(seeded).getMap("metadatas").has("gallery"))

        val cleared = apply(seeded, template, buildJsonObject { }, relationships = emptyList())
        assertFalse(decode(cleared).getMap("metadatas").has("gallery"))
    }

    @Test
    fun `metadata single no-match with no prior key is a clean no-op`() {
        // list == false, match == null, metadataMap.has(key) == false.
        val template = listOf(
            attribute("image", AttributeType.METADATA,
                configuration = buildJsonObject { put("relationship", "featured") }),
        )
        val content = apply(null, template, buildJsonObject { }, relationships = emptyList())
        assertFalse(decode(content).getMap("metadata").has("image"))
    }

    @Test
    fun `metadata attributes default to empty object when relationship attributes are null`() {
        // metadataJson: relationship.attributes ?: JsonObject(emptyMap()).
        val id = UUID.random()
        val template = listOf(
            attribute("image", AttributeType.METADATA,
                configuration = buildJsonObject { put("relationship", "featured") }),
        )
        val content = apply(null, template, buildJsonObject { }, relationships = listOf(
            CollaborationRelationship(id, "featured", null, "Cover", "image/png"),
        ))
        val entry = parsed(content, "metadata", "image").jsonObject
        assertEquals(id.toString(), entry["id"]!!.jsonPrimitive.content)
        assertTrue(entry["attributes"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `metadata with null configuration matches only relationships with null relationship name`() {
        // configString returns null when configuration is null; matching filters on == null.
        val template = listOf(attribute("image", AttributeType.METADATA, configuration = null))
        val content = apply(null, template, buildJsonObject { }, relationships = listOf(
            CollaborationRelationship(UUID.random(), "featured", null, "Cover", "image/png"),
        ))
        // "featured" != null -> no match -> nothing written.
        assertFalse(decode(content).getMap("metadata").has("image"))
    }

    @Test
    fun `metadata configuration that is not an object yields a null relationship name`() {
        // configString: configuration as? JsonObject == null.
        val template = listOf(
            attribute("image", AttributeType.METADATA, configuration = JsonArray(emptyList())),
        )
        val content = apply(null, template, buildJsonObject { }, relationships = listOf(
            CollaborationRelationship(UUID.random(), "featured", null, "Cover", "image/png"),
        ))
        assertFalse(decode(content).getMap("metadata").has("image"))
    }

    @Test
    fun `metadata configuration missing the relationship key yields a null relationship name`() {
        // configString: (obj)[key] == null -> contentOrNull path not reached.
        val template = listOf(
            attribute("image", AttributeType.METADATA,
                configuration = buildJsonObject { put("other", "x") }),
        )
        val content = apply(null, template, buildJsonObject { }, relationships = listOf(
            CollaborationRelationship(UUID.random(), "featured", null, "Cover", "image/png"),
        ))
        assertFalse(decode(content).getMap("metadata").has("image"))
    }

    // ── applyCollection: list + clear branches ─────────────────────────────────

    @Test
    fun `writes a collection list from all matching parents`() {
        val a = UUID.random()
        val b = UUID.random()
        val template = listOf(
            attribute("featured", AttributeType.COLLECTION, list = true,
                configuration = buildJsonObject { put("type", "featured") }),
        )
        val parents = listOf(
            CollaborationParent(a, "A", buildJsonObject { put("type", "featured") }),
            CollaborationParent(b, "B", buildJsonObject { put("type", "featured") }),
            CollaborationParent(UUID.random(), "C", buildJsonObject { put("type", "other") }),
        )
        val content = apply(null, template, buildJsonObject { }, parents = parents)

        val arr = parsed(content, "collections", "featured").jsonArray
        assertEquals(2, arr.size)
        assertEquals(
            setOf(a.toString(), b.toString()),
            arr.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet(),
        )
    }

    @Test
    fun `collection list with no matches and no prior key is a clean no-op`() {
        val template = listOf(
            attribute("featured", AttributeType.COLLECTION, list = true,
                configuration = buildJsonObject { put("type", "featured") }),
        )
        val content = apply(null, template, buildJsonObject { }, parents = emptyList())
        assertFalse(decode(content).getMap("collections").has("featured"))
    }

    @Test
    fun `collection list with no matches deletes a prior key`() {
        val template = listOf(
            attribute("featured", AttributeType.COLLECTION, list = true,
                configuration = buildJsonObject { put("type", "featured") }),
        )
        val seeded = apply(null, template, buildJsonObject { }, parents = listOf(
            CollaborationParent(UUID.random(), "A", buildJsonObject { put("type", "featured") }),
        ))
        assertTrue(decode(seeded).getMap("collections").has("featured"))

        val cleared = apply(seeded, template, buildJsonObject { }, parents = emptyList())
        assertFalse(decode(cleared).getMap("collections").has("featured"))
    }

    @Test
    fun `collection single no-match with no prior key is a clean no-op`() {
        val template = listOf(
            attribute("featured", AttributeType.COLLECTION,
                configuration = buildJsonObject { put("type", "featured") }),
        )
        val content = apply(null, template, buildJsonObject { }, parents = emptyList())
        assertFalse(decode(content).getMap("collection").has("featured"))
    }

    @Test
    fun `collection single no-match deletes a prior key`() {
        val template = listOf(
            attribute("featured", AttributeType.COLLECTION,
                configuration = buildJsonObject { put("type", "featured") }),
        )
        val seeded = apply(null, template, buildJsonObject { }, parents = listOf(
            CollaborationParent(UUID.random(), "A", buildJsonObject { put("type", "featured") }),
        ))
        assertTrue(decode(seeded).getMap("collection").has("featured"))

        val cleared = apply(seeded, template, buildJsonObject { }, parents = emptyList())
        assertFalse(decode(cleared).getMap("collection").has("featured"))
    }

    // ── parentType branches ────────────────────────────────────────────────────

    @Test
    fun `parent with null attributes never matches a typed collection`() {
        // parentType(null) -> null; configType "featured" != null -> no match.
        val template = listOf(
            attribute("featured", AttributeType.COLLECTION,
                configuration = buildJsonObject { put("type", "featured") }),
        )
        val content = apply(null, template, buildJsonObject { }, parents = listOf(
            CollaborationParent(UUID.random(), "A", null),
        ))
        assertFalse(decode(content).getMap("collection").has("featured"))
    }

    @Test
    fun `parent whose attributes are not an object never matches`() {
        // parentType: attributes as? JsonObject == null.
        val template = listOf(
            attribute("featured", AttributeType.COLLECTION,
                configuration = buildJsonObject { put("type", "featured") }),
        )
        val content = apply(null, template, buildJsonObject { }, parents = listOf(
            CollaborationParent(UUID.random(), "A", JsonArray(emptyList())),
        ))
        assertFalse(decode(content).getMap("collection").has("featured"))
    }

    @Test
    fun `parent whose type is not a primitive never matches`() {
        // parentType: obj["type"] as? JsonPrimitive == null.
        val template = listOf(
            attribute("featured", AttributeType.COLLECTION,
                configuration = buildJsonObject { put("type", "featured") }),
        )
        val content = apply(null, template, buildJsonObject { }, parents = listOf(
            CollaborationParent(UUID.random(), "A", buildJsonObject {
                put("type", buildJsonObject { put("nested", "featured") })
            }),
        ))
        assertFalse(decode(content).getMap("collection").has("featured"))
    }

    @Test
    fun `parent with a null configuration type matches a parent whose type is absent`() {
        // configType == null (config missing "type"); parentType == null when parent lacks "type".
        val id = UUID.random()
        val template = listOf(
            attribute("featured", AttributeType.COLLECTION, configuration = null),
        )
        val content = apply(null, template, buildJsonObject { }, parents = listOf(
            CollaborationParent(id, "A", buildJsonObject { put("other", "x") }),
        ))
        // both null -> match -> single collection written.
        val entry = parsed(content, "collection", "featured").jsonObject
        assertEquals(id.toString(), entry["id"]!!.jsonPrimitive.content)
        assertEquals("A", entry["name"]!!.jsonPrimitive.content)
    }
}

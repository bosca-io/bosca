package bosca.content.collaboration

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

class CollaborationAttributeWriterTest {

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

    // ── Scalars ──────────────────────────────────────────────────────────────

    @Test
    fun `writes scalar attributes into their typed maps`() {
        val attributes = buildJsonObject {
            put("title", "Hello World")
            put("count", 42)
            put("ratio", 3.5)
            put("published", 1780080347660L)
        }
        val template = listOf(
            attribute("title", AttributeType.STRING),
            attribute("count", AttributeType.INT),
            attribute("ratio", AttributeType.FLOAT),
            attribute("published", AttributeType.DATE),
        )

        val content = apply(null, template, attributes)

        assertEquals("Hello World", mapValue(content, "textAttributes", "title"))
        assertEquals(42L, (mapValue(content, "numberAttributes", "count") as Number).toLong())
        assertEquals(3.5, (mapValue(content, "numberAttributes", "ratio") as Number).toDouble(), 0.0001)
        assertEquals(1780080347660L, (mapValue(content, "dateAttributes", "published") as Number).toLong())
    }

    @Test
    fun `merges into existing content without disturbing other maps`() {
        val seed = Doc()
        seed.getMap("metadata").set("featured", "{\"id\":\"x\"}")
        val existing = encodeStateAsUpdate(seed)

        val content = apply(existing, listOf(attribute("title", AttributeType.STRING)), buildJsonObject { put("title", "kept") })

        val doc = decode(content)
        assertEquals("kept", doc.getMap("textAttributes").get("title"))
        assertEquals("{\"id\":\"x\"}", doc.getMap("metadata").get("featured"))
    }

    @Test
    fun `removes a key when its attribute value is absent`() {
        val template = listOf(attribute("title", AttributeType.STRING))
        val withValue = apply(null, template, buildJsonObject { put("title", "present") })
        assertEquals("present", mapValue(withValue, "textAttributes", "title"))

        val cleared = apply(withValue, template, buildJsonObject { })
        assertFalse(decode(cleared).getMap("textAttributes").has("title"))
    }

    @Test
    fun `treats an empty string as no value`() {
        val content = apply(null, listOf(attribute("title", AttributeType.STRING)), buildJsonObject { put("title", "") })
        assertFalse(decode(content).getMap("textAttributes").has("title"))
    }

    @Test
    fun `skips relationship-located attributes`() {
        val content = apply(
            null,
            listOf(attribute("edge", AttributeType.STRING, location = AttributeLocation.RELATIONSHIP)),
            buildJsonObject { put("edge", "value") },
        )
        assertFalse(decode(content).getMap("textAttributes").has("edge"))
    }

    @Test
    fun `resolves nested dotted keys`() {
        val content = apply(
            null,
            listOf(attribute("video.hls", AttributeType.STRING)),
            buildJsonObject { put("video", buildJsonObject { put("hls", "https://example/hls.m3u8") }) },
        )
        assertEquals("https://example/hls.m3u8", mapValue(content, "textAttributes", "video.hls"))
    }

    @Test
    fun `prefers a flat dotted key over a nested path`() {
        val content = apply(null, listOf(attribute("a.b", AttributeType.STRING)), buildJsonObject { put("a.b", "flat") })
        assertEquals("flat", mapValue(content, "textAttributes", "a.b"))
    }

    @Test
    fun `no-ops cleanly for empty template attributes`() {
        val content = apply(null, emptyList(), buildJsonObject { put("x", "y") })
        assertTrue(decode(content).getMap("textAttributes").keys().isEmpty())
    }

    // ── Metadata references (e.g. Featured Image) ──────────────────────────────

    @Test
    fun `writes a single metadata reference from a matching relationship`() {
        val imageId = UUID.random()
        val relationship = CollaborationRelationship(
            metadataId = imageId,
            relationship = "featured",
            attributes = buildJsonObject { put("jpeg", buildJsonObject { put("large", "abc123") }) },
            name = "Cover Image",
            contentType = "image/jpeg",
        )
        val template = listOf(
            attribute("image", AttributeType.METADATA,
                configuration = buildJsonObject { put("relationship", "featured") }),
        )

        val content = apply(null, template, buildJsonObject { }, relationships = listOf(relationship))

        val entry = parsed(content, "metadata", "image").jsonObject
        assertEquals(imageId.toString(), entry["id"]!!.jsonPrimitive.content)
        assertEquals("featured", entry["relationship"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", entry["contentType"]!!.jsonPrimitive.content)
        assertEquals("Cover Image", entry["name"]!!.jsonPrimitive.content)
        assertEquals("abc123", entry["attributes"]!!.jsonObject["jpeg"]!!.jsonObject["large"]!!.jsonPrimitive.content)
    }

    @Test
    fun `clears a metadata reference when no relationship matches`() {
        val template = listOf(
            attribute("image", AttributeType.METADATA,
                configuration = buildJsonObject { put("relationship", "featured") }),
        )
        // Seed with a stale value, then sync with no matching relationship.
        val seeded = apply(null, template, buildJsonObject { }, relationships = listOf(
            CollaborationRelationship(UUID.random(), "featured", null, "old", "image/png"),
        ))
        assertTrue(decode(seeded).getMap("metadata").has("image"))

        val cleared = apply(seeded, template, buildJsonObject { }, relationships = emptyList())
        assertFalse(decode(cleared).getMap("metadata").has("image"))
    }

    @Test
    fun `writes a metadata list from all matching relationships`() {
        val a = UUID.random()
        val b = UUID.random()
        val template = listOf(
            attribute("gallery", AttributeType.METADATA, list = true,
                configuration = buildJsonObject { put("relationship", "gallery") }),
        )
        val rels = listOf(
            CollaborationRelationship(a, "gallery", null, "A", "image/jpeg"),
            CollaborationRelationship(b, "gallery", null, "B", "image/jpeg"),
            CollaborationRelationship(UUID.random(), "other", null, "C", "image/jpeg"),
        )

        val content = apply(null, template, buildJsonObject { }, relationships = rels)

        val arr = parsed(content, "metadatas", "gallery").jsonArray
        assertEquals(2, arr.size)
        assertEquals(setOf(a.toString(), b.toString()), arr.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet())
    }

    // ── Collection references ──────────────────────────────────────────────────

    @Test
    fun `writes a single collection reference from a matching parent`() {
        val parentId = UUID.random()
        val parents = listOf(
            CollaborationParent(parentId, "Featured", buildJsonObject { put("type", "featured") }),
            CollaborationParent(UUID.random(), "Other", buildJsonObject { put("type", "other") }),
        )
        val template = listOf(
            attribute("featured", AttributeType.COLLECTION,
                configuration = buildJsonObject { put("type", "featured") }),
        )

        val content = apply(null, template, buildJsonObject { }, parents = parents)

        val entry = parsed(content, "collection", "featured").jsonObject
        assertEquals(parentId.toString(), entry["id"]!!.jsonPrimitive.content)
        assertEquals("Featured", entry["name"]!!.jsonPrimitive.content)
    }
}

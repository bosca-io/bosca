package bosca.search.pipeline

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Exhaustive coverage of the removal-signal / content-id parsing — every reachable branch of
 * [SearchDocumentPipeline.removalContentId] and [SearchDocumentPipeline.contentIds] across well-formed
 * and malformed document shapes.
 */
class SearchDocumentPipelineTest {

    private fun marker(action: kotlinx.serialization.json.JsonElement?, contentId: kotlinx.serialization.json.JsonElement?) =
        buildJsonObject {
            action?.let { put(SearchDocumentPipeline.ACTION_FIELD, it) }
            contentId?.let { put(SearchDocumentPipeline.CONTENT_ID_FIELD, it) }
        }

    // ── removalContentId ──────────────────────────────────────────────────────────

    @Test
    fun `removalContentId returns the content id for a well-formed delete signal`() {
        assertEquals("c-1", SearchDocumentPipeline.removalContentId(marker(JsonPrimitive("delete"), JsonPrimitive("c-1"))))
    }

    @Test
    fun `removalContentId is null for a normal document`() {
        assertNull(SearchDocumentPipeline.removalContentId(buildJsonObject { put("id", "x"); put("contentId", "x") }))
    }

    @Test
    fun `removalContentId is null when the value is not an object`() {
        assertNull(SearchDocumentPipeline.removalContentId(JsonPrimitive("x")))
        assertNull(SearchDocumentPipeline.removalContentId(buildJsonArray { add(buildJsonObject { put("contentId", "x") }) }))
    }

    @Test
    fun `removalContentId is null when the action is absent, null, non-delete, or non-primitive`() {
        assertNull(SearchDocumentPipeline.removalContentId(marker(null, JsonPrimitive("c"))))
        assertNull(SearchDocumentPipeline.removalContentId(marker(JsonNull, JsonPrimitive("c"))))
        assertNull(SearchDocumentPipeline.removalContentId(marker(JsonPrimitive("other"), JsonPrimitive("c"))))
        assertNull(SearchDocumentPipeline.removalContentId(marker(buildJsonObject { put("k", 1) }, JsonPrimitive("c"))))
    }

    @Test
    fun `removalContentId is null when a delete signal has no usable content id`() {
        assertNull(SearchDocumentPipeline.removalContentId(marker(JsonPrimitive("delete"), null)))
        assertNull(SearchDocumentPipeline.removalContentId(marker(JsonPrimitive("delete"), JsonNull)))
        assertNull(SearchDocumentPipeline.removalContentId(marker(JsonPrimitive("delete"), buildJsonObject { put("k", 1) })))
    }

    // ── contentIds ────────────────────────────────────────────────────────────────

    @Test
    fun `contentIds returns the id of a single document`() {
        assertEquals(listOf("c-1"), SearchDocumentPipeline.contentIds(buildJsonObject { put("contentId", "c-1") }))
    }

    @Test
    fun `contentIds collects distinct ids across an array`() {
        val docs = buildJsonArray {
            add(buildJsonObject { put("id", "c-en"); put("contentId", "c") })
            add(buildJsonObject { put("id", "c-fr"); put("contentId", "c") })
            add(buildJsonObject { put("id", "d"); put("contentId", "d") })
        }
        assertEquals(listOf("c", "d"), SearchDocumentPipeline.contentIds(docs))
    }

    @Test
    fun `contentIds skips non-objects, missing, null and non-primitive content ids`() {
        assertEquals(emptyList(), SearchDocumentPipeline.contentIds(JsonPrimitive("x")))
        assertEquals(emptyList(), SearchDocumentPipeline.contentIds(buildJsonObject { put("id", "x") }))
        assertEquals(emptyList(), SearchDocumentPipeline.contentIds(buildJsonObject { put("contentId", JsonNull) }))
        assertEquals(emptyList(), SearchDocumentPipeline.contentIds(buildJsonObject { put("contentId", buildJsonObject { put("k", 1) }) }))
        val mixed = buildJsonArray {
            add(buildJsonObject { put("contentId", "c") })
            add(JsonPrimitive("not-an-object"))
        }
        assertEquals(listOf("c"), SearchDocumentPipeline.contentIds(mixed))
    }
}

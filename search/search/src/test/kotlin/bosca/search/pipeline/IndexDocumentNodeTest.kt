@file:OptIn(InternalDI::class)

package bosca.search.pipeline

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchFilter
import bosca.search.service.SearchService
import bosca.security.service.AuthenticationContext
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IndexDocumentNodeTest {

    private val searchService = mockk<SearchService>(relaxed = true)
    private val json = Json
    private val context get() = PipelineContext(AuthenticationContext(null, null), json)
    private val dryContext get() = PipelineContext(AuthenticationContext(null, null), json, dryRun = true)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<SearchService> { searchService }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun input(value: JsonElement) = NodeInputs(mapOf("in" to PipelineValue.ofJson(value)))

    @Test
    fun `indexes a single document object into the configured index and passes it through`() = runTest {
        val doc = buildJsonObject { put("id", "a"); put("_type", "metadata") }
        val system = slot<IndexStorageSystem>()
        val out = IndexDocumentNode(id = "n1").run(context, input(doc))
        coVerify(exactly = 1) { searchService.index(capture(system), any<JsonElement>()) }
        assertEquals(SearchDocumentPipeline.DEFAULT_INDEX, system.captured.name)
        assertSame(out.result?.value, doc, "the document is passed through unchanged")
    }

    @Test
    fun `indexes each document of a JSON array`() = runTest {
        val docs = buildJsonArray {
            add(buildJsonObject { put("id", "a") })
            add(buildJsonObject { put("id", "b") })
        }
        val captured = slot<List<JsonElement>>()
        IndexDocumentNode(id = "n1").run(context, input(docs))
        coVerify(exactly = 1) { searchService.index(any<IndexStorageSystem>(), capture(captured)) }
        assertEquals(2, captured.captured.size)
    }

    @Test
    fun `an empty array indexes nothing`() = runTest {
        IndexDocumentNode(id = "n1").run(context, input(buildJsonArray { }))
        coVerify(exactly = 0) { searchService.index(any<IndexStorageSystem>(), any<List<JsonElement>>()) }
        coVerify(exactly = 0) { searchService.index(any<IndexStorageSystem>(), any<JsonElement>()) }
    }

    @Test
    fun `a removal signal deletes its content id from the configured index`() = runTest {
        val signal = buildJsonObject {
            put(SearchDocumentPipeline.CONTENT_ID_FIELD, "cid-1")
            put(SearchDocumentPipeline.ACTION_FIELD, SearchDocumentPipeline.ACTION_DELETE)
        }
        val system = slot<IndexStorageSystem>()
        val filter = slot<SearchFilter>()
        IndexDocumentNode(id = "n1", index = "Admin Search Index").run(context, input(signal))
        coVerify(exactly = 1) { searchService.deleteByFilter(capture(system), capture(filter)) }
        coVerify(exactly = 0) { searchService.index(any<IndexStorageSystem>(), any<JsonElement>()) }
        assertEquals("Admin Search Index", system.captured.name)
        assertEquals(SearchFilter.eq(SearchDocumentPipeline.CONTENT_ID_FIELD, "cid-1"), filter.captured)
    }

    @Test
    fun `replace clears each content id before indexing the new document set`() = runTest {
        val docs = buildJsonArray {
            add(buildJsonObject { put("id", "c-en"); put("contentId", "c") })
            add(buildJsonObject { put("id", "c-fr"); put("contentId", "c") })
        }
        val deleted = mutableListOf<SearchFilter>()
        val indexed = slot<List<JsonElement>>()
        IndexDocumentNode(id = "n1", replace = true).run(context, input(docs))
        coVerify(exactly = 1) { searchService.deleteByFilter(any(), capture(deleted)) }
        coVerify(exactly = 1) { searchService.index(any<IndexStorageSystem>(), capture(indexed)) }
        // The shared contentId is cleared once (deduped), then both variant documents are indexed.
        assertEquals(SearchFilter.eq(SearchDocumentPipeline.CONTENT_ID_FIELD, "c"), deleted.single())
        assertEquals(2, indexed.captured.size)
    }

    @Test
    fun `replace skips documents that carry no content id`() = runTest {
        val doc = buildJsonObject { put("id", "x") } // no contentId → nothing to clear
        IndexDocumentNode(id = "n1", replace = true).run(context, input(doc))
        coVerify(exactly = 0) { searchService.deleteByFilter(any(), any()) }
        coVerify(exactly = 1) { searchService.index(any<IndexStorageSystem>(), any<JsonElement>()) }
    }

    @Test
    fun `an object with a non-delete action is indexed, not removed`() = runTest {
        val doc = buildJsonObject { put("id", "a"); put("contentId", "a"); put(SearchDocumentPipeline.ACTION_FIELD, "other") }
        IndexDocumentNode(id = "n1").run(context, input(doc))
        coVerify(exactly = 0) { searchService.deleteByFilter(any(), any()) }
        coVerify(exactly = 1) { searchService.index(any<IndexStorageSystem>(), any<JsonElement>()) }
    }

    @Test
    fun `a delete-action object without a content id is indexed, not removed`() = runTest {
        val doc = buildJsonObject { put("id", "a"); put(SearchDocumentPipeline.ACTION_FIELD, SearchDocumentPipeline.ACTION_DELETE) }
        IndexDocumentNode(id = "n1").run(context, input(doc))
        coVerify(exactly = 0) { searchService.deleteByFilter(any(), any()) }
        coVerify(exactly = 1) { searchService.index(any<IndexStorageSystem>(), any<JsonElement>()) }
    }

    @Test
    fun `without replace, nothing is deleted before indexing`() = runTest {
        IndexDocumentNode(id = "n1").run(context, input(buildJsonObject { put("id", "a"); put("contentId", "a") }))
        coVerify(exactly = 0) { searchService.deleteByFilter(any(), any()) }
        coVerify(exactly = 1) { searchService.index(any<IndexStorageSystem>(), any<JsonElement>()) }
    }

    @Test
    fun `a dry run records the action but performs no live write`() = runTest {
        val out = IndexDocumentNode(id = "n1").run(dryContext, input(buildJsonObject { put("id", "a") }))
        coVerify(exactly = 0) { searchService.index(any<IndexStorageSystem>(), any<JsonElement>()) }
        coVerify(exactly = 0) { searchService.deleteByFilter(any(), any()) }
        assertTrue(out.result?.value != null)
    }

    @Test
    fun `a dry run with a trace records an index action and the replace flag`() = runTest {
        val trace = DryRunTrace()
        val ctx = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        IndexDocumentNode(id = "n1", replace = true).run(ctx, input(buildJsonObject { put("id", "a"); put("contentId", "a") }))
        coVerify(exactly = 0) { searchService.index(any<IndexStorageSystem>(), any<JsonElement>()) }
        val recorded = trace.actions["n1"]?.jsonObject
        assertEquals("index", recorded?.get("action")?.jsonPrimitive?.content)
        assertEquals(true, recorded?.get("replace")?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `a dry run with a trace and no replace records index without the replace flag`() = runTest {
        val trace = DryRunTrace()
        val ctx = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        IndexDocumentNode(id = "n1").run(ctx, input(buildJsonObject { put("id", "a") }))
        val recorded = trace.actions["n1"]?.jsonObject
        assertEquals("index", recorded?.get("action")?.jsonPrimitive?.content)
        assertEquals(null, recorded?.get("replace"), "no replace flag when replace is off")
    }

    @Test
    fun `a dry run with a trace records a delete action for a removal signal`() = runTest {
        val trace = DryRunTrace()
        val ctx = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val signal = buildJsonObject {
            put(SearchDocumentPipeline.CONTENT_ID_FIELD, "cid")
            put(SearchDocumentPipeline.ACTION_FIELD, SearchDocumentPipeline.ACTION_DELETE)
        }
        IndexDocumentNode(id = "n1").run(ctx, input(signal))
        coVerify(exactly = 0) { searchService.deleteByFilter(any(), any()) }
        val recorded = trace.actions["n1"]?.jsonObject
        assertEquals("delete", recorded?.get("action")?.jsonPrimitive?.content)
        assertEquals("cid", recorded?.get("contentId")?.jsonPrimitive?.content)
    }

    @Test
    fun `requires an input`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            IndexDocumentNode(id = "n1").run(context, NodeInputs(emptyMap()))
        }
        assertTrue("n1" in (e.message ?: "") && "requires a search document" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `requires an input - names the node by name when set`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            IndexDocumentNode(id = "n1", name = "Doc node").run(context, NodeInputs(emptyMap()))
        }
        assertTrue("Doc node" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `a delete action with an explicit null content id is indexed, not removed`() = runTest {
        val doc = buildJsonObject {
            put("id", "a")
            put(SearchDocumentPipeline.ACTION_FIELD, SearchDocumentPipeline.ACTION_DELETE)
            put(SearchDocumentPipeline.CONTENT_ID_FIELD, JsonNull)
        }
        IndexDocumentNode(id = "n1").run(context, input(doc))
        coVerify(exactly = 0) { searchService.deleteByFilter(any(), any()) }
        coVerify(exactly = 1) { searchService.index(any<IndexStorageSystem>(), any<JsonElement>()) }
    }

    @Test
    fun `replace ignores array elements that are not objects`() = runTest {
        val docs = buildJsonArray {
            add(buildJsonObject { put("id", "a"); put("contentId", "a") })
            add(JsonPrimitive("not-an-object"))
        }
        IndexDocumentNode(id = "n1", replace = true).run(context, input(docs))
        coVerify(exactly = 1) { searchService.deleteByFilter(any(), any()) } // only the object's contentId
        coVerify(exactly = 1) { searchService.index(any<IndexStorageSystem>(), any<List<JsonElement>>()) }
    }

    @Test
    fun `replace skips array elements whose content id is null`() = runTest {
        val docs = buildJsonArray { add(buildJsonObject { put("id", "a"); put("contentId", JsonNull) }) }
        IndexDocumentNode(id = "n1", replace = true).run(context, input(docs))
        coVerify(exactly = 0) { searchService.deleteByFilter(any(), any()) }
        coVerify(exactly = 1) { searchService.index(any<IndexStorageSystem>(), any<List<JsonElement>>()) }
    }

    @Test
    fun `an explicit null action is indexed, not removed`() = runTest {
        val doc = buildJsonObject { put("id", "a"); put("contentId", "a"); put(SearchDocumentPipeline.ACTION_FIELD, JsonNull) }
        IndexDocumentNode(id = "n1").run(context, input(doc))
        coVerify(exactly = 0) { searchService.deleteByFilter(any(), any()) }
        coVerify(exactly = 1) { searchService.index(any<IndexStorageSystem>(), any<JsonElement>()) }
    }

    @Test
    fun `serializes and round-trips with defaults`() {
        val node = IndexDocumentNode(id = "n1", name = "Index doc", description = "d", index = "Admin Search Index", replace = true, position = NodePosition(2.0, 3.0))
        val decoded = json.decodeFromString(IndexDocumentNode.serializer(), json.encodeToString(IndexDocumentNode.serializer(), node))
        assertEquals(node.id, decoded.id)
        assertEquals(node.index, decoded.index)
        assertEquals(true, decoded.replace)
        assertEquals(node.position, decoded.position)
        val minimal = json.decodeFromString(IndexDocumentNode.serializer(), """{"id":"only"}""")
        assertEquals("only", minimal.id)
        assertEquals(SearchDocumentPipeline.DEFAULT_INDEX, minimal.index, "index defaults to the Default Search Index")
        assertEquals(false, minimal.replace, "replace defaults to off")
    }

    @Test
    fun `round-trips every setting under both encode modes`() {
        // Full + default values under both encode modes exercise the serializer's value/default and
        // shouldEncodeElementDefault arms.
        for (j in listOf(json, Json { encodeDefaults = true })) {
            for (n in listOf(
                IndexDocumentNode(id = "n1", name = "I", description = "d", index = "Admin Search Index", replace = true, position = NodePosition(2.0, 3.0)),
                IndexDocumentNode(id = "n1"),
            )) {
                val enc = j.encodeToString(IndexDocumentNode.serializer(), n)
                assertEquals(enc, j.encodeToString(IndexDocumentNode.serializer(), j.decodeFromString(IndexDocumentNode.serializer(), enc)))
            }
        }
    }

    @Test
    fun `rejects malformed serialized graphs`() {
        assertFailsWith<SerializationException> { json.decodeFromString(IndexDocumentNode.serializer(), "{}") }
        assertFailsWith<SerializationException> { json.decodeFromString(IndexDocumentNode.serializer(), """{"id":"x","bogus":1}""") }
    }
}

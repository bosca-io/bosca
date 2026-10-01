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
import bosca.search.Indexable
import bosca.search.service.SearchService
import bosca.security.service.AuthenticationContext
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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

class SearchPipelineNodesTest {

    @Serializable
    private data class FakeIndexable(val tag: String) : Indexable {
        override val isSearchable: Boolean get() = true
    }

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

    private fun indexableInput(tag: String = "x") =
        NodeInputs(mapOf("in" to PipelineValue.of(FakeIndexable(tag), FakeIndexable.serializer())))

    // ── Index ─────────────────────────────────────────────────────────────────

    @Test
    fun `index indexes an indexable input and passes it through`() = runTest {
        val item = FakeIndexable("a")
        val input = PipelineValue.of(item, FakeIndexable.serializer())
        val out = IndexNode(id = "n1").run(context, NodeInputs(mapOf("in" to input)))
        coVerify(exactly = 1) { searchService.index(item) }
        assertSame(input, out.result, "the entity is passed through unchanged")
    }

    @Test
    fun `index in a dry run records nothing live and passes through`() = runTest {
        val out = IndexNode(id = "n1").run(dryContext, indexableInput())
        coVerify(exactly = 0) { searchService.index(any<Indexable>()) }
        assertTrue(out.result?.value != null)
    }

    @Test
    fun `index in a dry run with a trace records the index action and type`() = runTest {
        val trace = DryRunTrace()
        val ctx = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        IndexNode(id = "n1").run(ctx, indexableInput())
        coVerify(exactly = 0) { searchService.index(any<Indexable>()) }
        val recorded = trace.actions["n1"]?.jsonObject
        assertEquals("index", recorded?.get("action")?.jsonPrimitive?.content)
        assertTrue(recorded?.get("type") != null, "the entity's type name is recorded")
    }

    @Test
    fun `index requires an input`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            IndexNode(id = "n1").run(context, NodeInputs(emptyMap()))
        }
        assertTrue("n1" in (e.message ?: "") && "requires an entity" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `index rejects a non-indexable JSON input naming the node id`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            IndexNode(id = "n1").run(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(buildJsonObject { put("x", 1) }))))
        }
        assertTrue("n1" in (e.message ?: ""), e.message ?: "")
        assertTrue("Metadata, Collection, or Profile" in (e.message ?: ""), e.message ?: "")
        assertTrue("non-indexable value" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `index rejects a non-indexable typed input naming the node name and the type`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            IndexNode(id = "n1", name = "Index it").run(
                context,
                NodeInputs(mapOf("in" to PipelineValue.of("hello", String.serializer()))),
            )
        }
        assertTrue("Index it" in (e.message ?: ""), e.message ?: "")
    }

    // ── Remove from Index ───────────────────────────────────────────────────────

    @Test
    fun `remove deletes an indexable input and passes it through`() = runTest {
        val item = FakeIndexable("b")
        val input = PipelineValue.of(item, FakeIndexable.serializer())
        val out = RemoveFromIndexNode(id = "n1").run(context, NodeInputs(mapOf("in" to input)))
        coVerify(exactly = 1) { searchService.delete(item) }
        assertSame(input, out.result)
    }

    @Test
    fun `remove in a dry run records nothing live and passes through`() = runTest {
        val out = RemoveFromIndexNode(id = "n1").run(dryContext, indexableInput())
        coVerify(exactly = 0) { searchService.delete(any<Indexable>()) }
        assertTrue(out.result?.value != null)
    }

    @Test
    fun `remove in a dry run with a trace records the remove action`() = runTest {
        val trace = DryRunTrace()
        val ctx = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        RemoveFromIndexNode(id = "n1").run(ctx, indexableInput())
        coVerify(exactly = 0) { searchService.delete(any<Indexable>()) }
        assertEquals("removeFromIndex", trace.actions["n1"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
    }

    @Test
    fun `remove requires an input`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            RemoveFromIndexNode(id = "n1").run(context, NodeInputs(emptyMap()))
        }
        assertTrue("requires an entity to remove" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `remove rejects a non-indexable input naming the node name`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            RemoveFromIndexNode(id = "n1", name = "Drop it").run(
                context,
                NodeInputs(mapOf("in" to PipelineValue.ofJson(buildJsonObject { put("x", 1) }))),
            )
        }
        assertTrue("Drop it" in (e.message ?: ""), e.message ?: "")
    }

    // ── identity / serialization ─────────────────────────────────────────────────

    @Test
    fun `index node serializes and round-trips with defaults`() {
        val node = IndexNode(id = "n1", name = "Index", description = "d", position = NodePosition(2.0, 3.0))
        val decoded = json.decodeFromString(IndexNode.serializer(), json.encodeToString(IndexNode.serializer(), node))
        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.position, decoded.position)
        val minimal = json.decodeFromString(IndexNode.serializer(), """{"id":"only"}""")
        assertEquals("only", minimal.id)
        assertEquals("", minimal.name)
        assertEquals(NodePosition(), minimal.position)
    }

    @Test
    fun `remove node serializes and round-trips with defaults`() {
        val node = RemoveFromIndexNode(id = "n1", name = "Remove", description = "d", position = NodePosition(2.0, 3.0))
        val decoded = json.decodeFromString(RemoveFromIndexNode.serializer(), json.encodeToString(RemoveFromIndexNode.serializer(), node))
        assertEquals(node.id, decoded.id)
        assertEquals(node.position, decoded.position)
        val minimal = json.decodeFromString(RemoveFromIndexNode.serializer(), """{"id":"only"}""")
        assertEquals("only", minimal.id)
        assertEquals("", minimal.description)
    }

    @Test
    fun `index and remove nodes round-trip every setting under both encode modes`() {
        // Full + default values under both encode modes exercise the serializer's value/default and
        // shouldEncodeElementDefault arms (write$Self + synthetic ctor).
        for (j in listOf(json, Json { encodeDefaults = true })) {
            for (n in listOf(IndexNode(id = "n1", name = "Index", description = "d", position = NodePosition(2.0, 3.0)), IndexNode(id = "n1"))) {
                val enc = j.encodeToString(IndexNode.serializer(), n)
                assertEquals(enc, j.encodeToString(IndexNode.serializer(), j.decodeFromString(IndexNode.serializer(), enc)))
            }
            for (n in listOf(RemoveFromIndexNode(id = "n1", name = "Remove", description = "d", position = NodePosition(2.0, 3.0)), RemoveFromIndexNode(id = "n1"))) {
                val enc = j.encodeToString(RemoveFromIndexNode.serializer(), n)
                assertEquals(enc, j.encodeToString(RemoveFromIndexNode.serializer(), j.decodeFromString(RemoveFromIndexNode.serializer(), enc)))
            }
        }
    }

    @Test
    fun `remove rejects a non-indexable typed input naming the node id`() = runTest {
        // No name (id arm) + a typed value (non-null typeName arm) for the remove node's as?-error.
        val e = assertFailsWith<IllegalStateException> {
            RemoveFromIndexNode(id = "n1").run(context, NodeInputs(mapOf("in" to PipelineValue.of("hello", String.serializer()))))
        }
        assertTrue("n1" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `index and remove name the node by name when set, by id otherwise`() = runTest {
        // exercises the name.ifBlank { id } arms in both nodes' error messages.
        val empty = NodeInputs(emptyMap())
        assertTrue("Idx" in (assertFailsWith<IllegalStateException> { IndexNode(id = "n1", name = "Idx").run(context, empty) }.message ?: ""))
        assertTrue("n1" in (assertFailsWith<IllegalStateException> { IndexNode(id = "n1").run(context, empty) }.message ?: ""))
        assertTrue("Rm" in (assertFailsWith<IllegalStateException> { RemoveFromIndexNode(id = "n1", name = "Rm").run(context, empty) }.message ?: ""))
        assertTrue("n1" in (assertFailsWith<IllegalStateException> { RemoveFromIndexNode(id = "n1").run(context, empty) }.message ?: ""))
    }

    @Test
    fun `index and remove nodes reject malformed serialized graphs`() {
        assertFailsWith<SerializationException> { json.decodeFromString(IndexNode.serializer(), "{}") }
        assertFailsWith<SerializationException> { json.decodeFromString(IndexNode.serializer(), """{"id":"x","bogus":1}""") }
        assertFailsWith<SerializationException> { json.decodeFromString(RemoveFromIndexNode.serializer(), "{}") }
        assertFailsWith<SerializationException> { json.decodeFromString(RemoveFromIndexNode.serializer(), """{"id":"x","bogus":1}""") }
    }
}

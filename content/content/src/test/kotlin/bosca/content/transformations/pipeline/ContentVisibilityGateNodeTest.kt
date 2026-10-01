package bosca.content.transformations.pipeline

import bosca.configuration.model.Configuration
import bosca.content.metadata.model.Metadata
import bosca.content.pipeline.executeForTestValue
import bosca.content.metadata.model.MetadataType
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ContentVisibilityGateNodeTest {

    private val json = Json
    private val context get() = PipelineContext(AuthenticationContext(null, null), json)

    private fun metadata(name: String = "Doc", workflowStateId: String = "published", public: Boolean = true) = Metadata(
        id = UUID.random(),
        name = name,
        type = MetadataType.STANDARD,
        languageTag = "en",
        contentType = "text/plain",
        contentLength = 10,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        public = public,
        workflowStateId = workflowStateId,
    )

    private fun inputs(value: PipelineValue) = NodeInputs(mapOf("in" to value))

    @Test
    fun `routes a visible entity to the visible port, unchanged`() = runTest {
        val md = metadata()
        val out = ContentVisibilityGateNode(id = "n1").executeForTestValue(context, inputs(PipelineValue.of(md, Metadata.serializer())))
        assertEquals("visible", out.port)
        assertSame(md, out.value)
    }

    @Test
    fun `routes an unpublished entity to the blocked port`() = runTest {
        val md = metadata(workflowStateId = "draft")
        val out = ContentVisibilityGateNode(id = "n1").executeForTestValue(context, inputs(PipelineValue.of(md, Metadata.serializer())))
        assertEquals("blocked", out.port)
    }

    @Test
    fun `passes an unpublished entity when the published requirement is off`() = runTest {
        val md = metadata(workflowStateId = "draft")
        val node = ContentVisibilityGateNode(id = "n1", requirePublished = false)
        assertEquals("visible", node.executeForTestValue(context, inputs(PipelineValue.of(md, Metadata.serializer()))).port)
    }

    @Test
    fun `blocks when the extra constraint fails`() = runTest {
        val md = metadata(name = "Doc")
        val node = ContentVisibilityGateNode(id = "n1", condition = "name = 'Other'")
        assertEquals("blocked", node.executeForTestValue(context, inputs(PipelineValue.of(md, Metadata.serializer()))).port)
    }

    @Test
    fun `passes when the extra constraint matches`() = runTest {
        val md = metadata(name = "Doc")
        val node = ContentVisibilityGateNode(id = "n1", condition = "name = 'Doc'")
        assertEquals("visible", node.executeForTestValue(context, inputs(PipelineValue.of(md, Metadata.serializer()))).port)
    }

    @Test
    fun `fails clearly on a non-entity input`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            ContentVisibilityGateNode(id = "n1", name = "Gate it")
                .executeForTestValue(context, inputs(PipelineValue.of("x", String.serializer())))
        }
        assertTrue("Gate it" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `requires an input`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            ContentVisibilityGateNode(id = "n1").executeForTestValue(context, NodeInputs(emptyMap()))
        }
        assertTrue("n1" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `treats a non-indexable permissible entity as searchable`() = runTest {
        // Configuration is a PermissibleEntity but not Indexable → the (value as? Indexable)?.isSearchable
        // ?: true arm; a public configuration passes the gate.
        val cfg = Configuration(id = UUID.random(), key = "k", description = "d", public = true)
        val out = ContentVisibilityGateNode(id = "n1").executeForTestValue(context, inputs(PipelineValue.of(cfg, Configuration.serializer())))
        assertEquals("visible", out.port)
    }

    @Test
    fun `rejects malformed serialized graphs`() {
        assertFailsWith<SerializationException> { json.decodeFromString(ContentVisibilityGateNode.serializer(), "{}") }
        assertFailsWith<SerializationException> { json.decodeFromString(ContentVisibilityGateNode.serializer(), """{"id":"x","bogus":1}""") }
    }

    @Test
    fun `every setting survives a round-trip`() {
        // All settings non-default, so all are written and the round-trip exercises the serializer's
        // value-provided path and proves no setting is dropped.
        val node = ContentVisibilityGateNode(
            id = "n1", name = "N", description = "D",
            requirePublic = false, requirePublished = false, requireSearchable = false, excludeDeleted = false,
            condition = "name = 'x'", position = NodePosition(1.0, 2.0),
        )
        assertRoundTrips(node)
        // Default-valued node round-trips too (the serializer's skip-default / absent-field arms).
        assertRoundTrips(ContentVisibilityGateNode(id = "n1"))
    }

    private fun assertRoundTrips(node: ContentVisibilityGateNode) {
        val ser = ContentVisibilityGateNode.serializer()
        for (j in listOf(json, Json { encodeDefaults = true })) {
            val encoded = j.encodeToString(ser, node)
            assertEquals(encoded, j.encodeToString(ser, j.decodeFromString(ser, encoded)), "a setting did not survive the round-trip")
        }
    }

    @Test
    fun `falls back to defaults when settings are absent`() {
        val minimal = json.decodeFromString(ContentVisibilityGateNode.serializer(), """{"id":"only"}""")
        assertEquals(true, minimal.requirePublic)
        assertEquals(true, minimal.requirePublished)
        assertEquals(true, minimal.requireSearchable)
        assertEquals(true, minimal.excludeDeleted)
        assertEquals("", minimal.condition)
    }
}

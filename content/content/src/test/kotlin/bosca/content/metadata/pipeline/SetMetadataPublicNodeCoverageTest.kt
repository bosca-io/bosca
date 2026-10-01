@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.content.metadata.pipeline

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.pipeline.executeForTest
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUIDSerializer
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Focused coverage for [SetMetadataPublicNode]: the branches the shared `ContentMutationNodesTest`
 * leaves untouched — the [SetMetadataPublicNode.dryRun] trace across every target/public variant, the
 * not-found-after-update guard in [SetMetadataPublicNode.execute] (both blank/non-blank label arms),
 * the non-Metadata / missing `in` rejection through the shared resolver, the target-normalisation
 * (`trim().lowercase()`) path, the unknown-target rejection under both `execute` and `dryRun`, and a
 * `@Serializable` round-trip that exercises the constructor defaults and settings shape. The happy
 * per-target toggle, the unknown-target rejection under execute, and the dry-run-intent smoke are
 * already covered there; this only fills the remaining branches.
 */
class SetMetadataPublicNodeCoverageTest {

    private val metadataService = mockk<MetadataService>(relaxUnitFun = true)

    private val json = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }

    private val context = PipelineContext(AuthenticationContext(null, null), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<MetadataService> { metadataService }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    private fun metadataMock(id: Uuid = Uuid.random()) =
        mockk<Metadata> { every { this@mockk.id } returns id }

    /** A real [Metadata] (not a mock) so the generated deserialize can bridge it through JSON. */
    private fun metadata(id: Uuid = Uuid.random()) = Metadata(
        id = id,
        name = "Doc",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "draft",
    )

    private fun metadataInput(metadata: Metadata) =
        NodeInputs(mapOf("in" to PipelineValue.of(metadata, Metadata.serializer())))

    // ── dryRun: trace across every target + public variant ────────────────────────────────────────

    @Test
    fun `dry run records the metadata target with public on and passes the input through`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val metadata = metadataMock()

        val out = SetMetadataPublicNode(id = "pub", target = "metadata", public = true)
            .executeForTest(dry, metadataInput(metadata))

        // The `in` Metadata is passed straight through (the same wrapped value, not a fresh fetch).
        assertSame(metadata, out?.value)
        val recorded = trace.actions["pub"] as? JsonObject
        assertTrue(recorded != null)
        assertEquals("setMetadataPublic", recorded?.get("action")?.jsonPrimitive?.content)
        assertEquals("metadata", recorded?.get("target")?.jsonPrimitive?.content)
        assertEquals("true", recorded?.get("public")?.jsonPrimitive?.content)
        // No service side effects under dry run.
        coVerify(exactly = 0) { metadataService.setPublic(any(), any()) }
        coVerify(exactly = 0) { metadataService.setPublicContent(any(), any()) }
        coVerify(exactly = 0) { metadataService.setPublicSupplementary(any(), any()) }
        coVerify(exactly = 0) { metadataService.getById(any<Uuid>()) }
    }

    @Test
    fun `dry run records the content target with public off`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        SetMetadataPublicNode(id = "pub", target = "content", public = false)
            .executeForTest(dry, metadataInput(metadataMock()))

        val recorded = trace.actions["pub"] as? JsonObject
        assertEquals("content", recorded?.get("target")?.jsonPrimitive?.content)
        assertEquals("false", recorded?.get("public")?.jsonPrimitive?.content)
    }

    @Test
    fun `dry run records the supplementary target`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        SetMetadataPublicNode(id = "pub", target = "supplementary", public = true)
            .executeForTest(dry, metadataInput(metadataMock()))

        val recorded = trace.actions["pub"] as? JsonObject
        assertEquals("supplementary", recorded?.get("target")?.jsonPrimitive?.content)
    }

    @Test
    fun `dry run rejects an unknown target through the shared resolver`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        val e = assertFailsWith<IllegalArgumentException> {
            SetMetadataPublicNode(id = "n", name = "Publish", target = "bogus")
                .executeForTest(dry, metadataInput(metadataMock()))
        }
        // Non-blank name is used as the label; the offending target is quoted.
        assertTrue(e.message?.contains("Publish") == true)
        assertTrue(e.message?.contains("bogus") == true)
    }

    @Test
    fun `dry run passes a non-Metadata in port through untouched`() = runTest {
        // The dry run reads the raw inbound value only — it neither decodes nor validates it.
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        val out = SetMetadataPublicNode(id = "the-node")
            .executeForTest(dry, NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("nope")))))

        assertEquals(JsonPrimitive("nope"), out?.value)
        assertTrue(trace.actions.containsKey("the-node"))
    }

    @Test
    fun `dry run without a trace records nothing but still passes the input through`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true)
        val metadata = metadataMock()

        val out = SetMetadataPublicNode(id = "pub").executeForTest(dry, metadataInput(metadata))

        assertSame(metadata, out?.value)
    }

    @Test
    fun `execute rejects an unknown target using the node id when the name is blank`() = runTest {
        val e = assertFailsWith<IllegalArgumentException> {
            SetMetadataPublicNode(id = "blank-target-id", target = "bogus")
                .executeForTest(context, metadataInput(metadata()))
        }
        assertTrue(e.message?.contains("bogus") == true)
        assertTrue(e.message?.contains("blank-target-id") == true)
    }

    // ── execute: not-found-after-update guard (both label arms) ───────────────────────────────────

    @Test
    fun `execute fails when the metadata cannot be re-fetched after the update, quoting the named label`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)
        coEvery { metadataService.getById(id) } returns null

        val node = SetMetadataPublicNode(id = "n", name = "Publish It", target = "metadata", public = true)
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, metadataInput(metadata))
        }
        // Non-blank name is the label; the id is quoted in the not-found message.
        assertTrue(e.message?.contains("Publish It") == true)
        assertTrue(e.message?.contains(id.toString()) == true)
        coVerify(exactly = 1) { metadataService.setPublic(metadata, true) }
    }

    @Test
    fun `execute falls back to the node id as the label when name is blank`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)
        coEvery { metadataService.getById(id) } returns null

        val e = assertFailsWith<IllegalStateException> {
            SetMetadataPublicNode(id = "node-99", target = "content", public = false)
                .executeForTest(context, metadataInput(metadata))
        }
        assertTrue(e.message?.contains("node-99") == true)
        coVerify(exactly = 1) { metadataService.setPublicContent(metadata, false) }
    }

    // ── execute: target normalisation (trim + lowercase) reaches the right service arm ─────────────

    @Test
    fun `execute normalises a padded upper-case supplementary target before dispatch`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)
        val fresh = metadataMock()
        coEvery { metadataService.getById(id) } returns fresh

        val out = SetMetadataPublicNode(id = "n", target = "  SUPPLEMENTARY  ", public = false)
            .executeForTest(context, metadataInput(metadata))

        assertSame(fresh, out?.value)
        // trim()+lowercase() maps "  SUPPLEMENTARY  " to the supplementary arm.
        coVerify(exactly = 1) { metadataService.setPublicSupplementary(metadata, false) }
        coVerify(exactly = 0) { metadataService.setPublic(any(), any()) }
        coVerify(exactly = 0) { metadataService.setPublicContent(any(), any()) }
    }

    @Test
    fun `execute treats a normalised content target as content`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)
        coEvery { metadataService.getById(id) } returns metadataMock()

        SetMetadataPublicNode(id = "n", target = "Content", public = true)
            .executeForTest(context, metadataInput(metadata))

        coVerify(exactly = 1) { metadataService.setPublicContent(metadata, true) }
    }

    // ── execute: input rejection through the shared resolver ───────────────────────────────────────

    @Test
    fun `execute rejects an in port that is not a Metadata`() = runTest {
        // The generated deserialize bridges through Metadata.serializer(), so a non-Metadata value
        // fails decoding.
        assertFailsWith<SerializationException> {
            SetMetadataPublicNode(id = "the-node")
                .executeForTest(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("not a metadata")))))
        }
    }

    @Test
    fun `execute rejects a missing in port with the generated required-input message`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            SetMetadataPublicNode(id = "n", name = "Make Public")
                .executeForTest(context, NodeInputs(emptyMap()))
        }
        assertTrue(e.message?.contains("required input 'in'") == true)
    }

    // ── @Serializable round-trip: constructor defaults + settings shape ───────────────────────────

    @Test
    fun `node round-trips through its serializer preserving target, public flag, and position`() {
        val node = SetMetadataPublicNode(
            id = "n1",
            name = "Publisher",
            description = "flips visibility",
            target = "supplementary",
            public = false,
            position = NodePosition(12.0, 34.0),
        )

        val encoded = json.encodeToString(SetMetadataPublicNode.serializer(), node)
        val decoded = json.decodeFromString(SetMetadataPublicNode.serializer(), encoded)

        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.target, decoded.target)
        assertEquals(node.public, decoded.public)
        assertEquals(node.position, decoded.position)
    }

    @Test
    fun `node applies its constructor defaults when only the id is supplied`() {
        val node = SetMetadataPublicNode(id = "bare")

        assertEquals("bare", node.id)
        assertEquals("", node.name)
        assertEquals("", node.description)
        // target defaults to the metadata itself; public defaults to on; position to the origin.
        assertEquals("metadata", node.target)
        assertTrue(node.public)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `decoding a minimal graph fragment fills every default`() {
        val decoded = json.decodeFromString(
            SetMetadataPublicNode.serializer(),
            """{"id":"only-id"}""",
        )

        assertEquals("only-id", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertEquals("metadata", decoded.target)
        assertTrue(decoded.public)
        assertEquals(NodePosition(), decoded.position)
        assertNull(decoded.retry)
        assertNull(decoded.timeoutSeconds)
    }
}

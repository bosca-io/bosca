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
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
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
import kotlinx.serialization.json.booleanOrNull
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
 * Focused coverage for [SetMetadataReadyNode]: the paths the shared `ContentMutationNodesTest` leaves
 * untouched — the [SetMetadataReadyNode.dryRun] trace (both the `ready=true` and `ready=false` flag
 * variants), the not-found-after-revoke guard (blank and non-blank display-label branches), the
 * missing-`in`-port rejection, the non-Metadata rejection through the shared resolver on the dry-run
 * path, and a `@Serializable` round-trip that exercises the constructor defaults and settings shape.
 *
 * The happy `setReady`/`setNotReady` arms, the missing-principal guard, and the non-Metadata rejection
 * on the execute path are already covered there; this only fills the remaining branches.
 */
class SetMetadataReadyNodeCoverageTest {

    private val metadataService = mockk<MetadataService>(relaxUnitFun = true)

    private val json = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }

    /** The principal a node sees on a run that carries an authenticated identity. */
    private val principal = Principal()
    private val context = PipelineContext(AuthenticationContext(null, null), json)
    private val authedContext = PipelineContext(ImpersonatedAuthenticationContext(principal, emptyList()), json)

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

    // ── dryRun ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `dry run with ready on records the intent, passes the metadata through, and mutates nothing`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val metadata = metadataMock()

        val node = SetMetadataReadyNode(id = "ready", ready = true)
        val out = node.executeForTest(dry, metadataInput(metadata))

        // The `in` Metadata is passed straight through (the same wrapped value), not a fresh fetch.
        assertSame(metadata, out?.value)

        val recorded = trace.actions["ready"] as? JsonObject
        assertTrue(recorded != null)
        assertEquals("setMetadataReady", recorded?.get("action")?.jsonPrimitive?.content)
        // `put("ready", true)` records a JSON boolean primitive.
        assertEquals(true, recorded?.get("ready")?.jsonPrimitive?.booleanOrNull)

        // No service side effects under dry run.
        coVerify(exactly = 0) { metadataService.setReady(any(), any()) }
        coVerify(exactly = 0) { metadataService.setNotReady(any()) }
        coVerify(exactly = 0) { metadataService.getById(any<Uuid>()) }
    }

    @Test
    fun `dry run records the ready flag as false when ready is off`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val metadata = metadataMock()

        val out = SetMetadataReadyNode(id = "revoke", ready = false)
            .executeForTest(dry, metadataInput(metadata))

        assertSame(metadata, out?.value)
        val recorded = trace.actions["revoke"] as? JsonObject
        assertTrue(recorded != null)
        assertEquals(false, recorded?.get("ready")?.jsonPrimitive?.booleanOrNull)
        assertEquals("setMetadataReady", recorded?.get("action")?.jsonPrimitive?.content)
        // Dry run never touches the service, even on the revoke path.
        coVerify(exactly = 0) { metadataService.setNotReady(any()) }
    }

    @Test
    fun `dry run passes a non-Metadata in port through untouched`() = runTest {
        // The dry run reads the raw inbound value only — it neither decodes nor validates it.
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        val out = SetMetadataReadyNode(id = "n")
            .executeForTest(dry, NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("nope")))))

        assertEquals(JsonPrimitive("nope"), out?.value)
        assertTrue(trace.actions.containsKey("n"))
    }

    @Test
    fun `dry run without a trace records nothing but still passes the input through`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true)
        val metadata = metadataMock()

        val out = SetMetadataReadyNode(id = "ready").executeForTest(dry, metadataInput(metadata))

        assertSame(metadata, out?.value)
    }

    // ── execute: not-found-after-revoke guard + label branches ────────────────────────────────────

    @Test
    fun `execute fails when the metadata cannot be re-fetched after revoking readiness`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)
        coEvery { metadataService.getById(id) } returns null

        val node = SetMetadataReadyNode(id = "n", name = "Revoke Ready", ready = false)
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(authedContext, metadataInput(metadata))
        }
        // Non-blank name is used as the label and the id is quoted in the not-found message.
        assertTrue(e.message?.contains("Revoke Ready") == true)
        assertTrue(e.message?.contains(id.toString()) == true)
        coVerify(exactly = 1) { metadataService.setNotReady(metadata) }
    }

    @Test
    fun `execute falls back to the node id as the label when name is blank on the not-found guard`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)
        coEvery { metadataService.getById(id) } returns null

        // Blank name → label is the node id, exercised via the not-found error message.
        val e = assertFailsWith<IllegalStateException> {
            SetMetadataReadyNode(id = "node-77", ready = false)
                .executeForTest(authedContext, metadataInput(metadata))
        }
        assertTrue(e.message?.contains("node-77") == true)
    }

    // ── execute: ready=true wraps the refreshed metadata; principal-missing label branch ───────────

    @Test
    fun `execute marks ready and wraps the refreshed metadata with the Metadata serializer`() = runTest {
        val metadata = metadata()
        val updated = metadataMock()
        coEvery { metadataService.setReady(metadata, principal) } returns updated

        val out = SetMetadataReadyNode(id = "n", ready = true)
            .executeForTest(authedContext, metadataInput(metadata))

        assertSame(updated, out?.value)
        // The output rides the explicit Metadata serializer for downstream chaining.
        assertEquals(Metadata.serializer().descriptor.serialName, out?.serializer?.descriptor?.serialName)
    }

    @Test
    fun `execute uses the node id as the label in the missing-principal error when name is blank`() = runTest {
        // Blank name + no authenticated principal → label falls back to the node id.
        val e = assertFailsWith<IllegalStateException> {
            SetMetadataReadyNode(id = "approver-node", ready = true)
                .executeForTest(context, metadataInput(metadata()))
        }
        assertTrue(e.message?.contains("approver-node") == true)
    }

    // ── resolve: missing in port ──────────────────────────────────────────────────────────────────

    @Test
    fun `execute rejects a missing in port with the generated required-input message`() = runTest {
        val node = SetMetadataReadyNode(id = "n", name = "Approve Content", ready = true)
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(authedContext, NodeInputs(emptyMap()))
        }
        assertTrue(e.message?.contains("required input 'in'") == true)
    }

    @Test
    fun `execute rejects an in port that is not a Metadata`() = runTest {
        // The generated deserialize bridges through Metadata.serializer(), so a non-Metadata value
        // fails decoding.
        val node = SetMetadataReadyNode(id = "the-node")
        assertFailsWith<SerializationException> {
            node.executeForTest(
                authedContext,
                NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("not a metadata")))),
            )
        }
    }

    // ── @Serializable round-trip: constructor defaults + settings shape ───────────────────────────

    @Test
    fun `node round-trips through its serializer preserving the ready flag and position`() {
        val node = SetMetadataReadyNode(
            id = "n1",
            name = "Ready Setter",
            description = "marks ready",
            ready = false,
            position = NodePosition(12.0, 34.0),
        )

        val encoded = json.encodeToString(SetMetadataReadyNode.serializer(), node)
        val decoded = json.decodeFromString(SetMetadataReadyNode.serializer(), encoded)

        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.ready, decoded.ready)
        assertEquals(node.position, decoded.position)
    }

    @Test
    fun `node applies its constructor defaults when only the id is supplied`() {
        val node = SetMetadataReadyNode(id = "bare")

        assertEquals("bare", node.id)
        assertEquals("", node.name)
        assertEquals("", node.description)
        // ready defaults on; position defaults to the origin.
        assertTrue(node.ready)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `decoding a minimal graph fragment fills every default`() {
        val decoded = json.decodeFromString(
            SetMetadataReadyNode.serializer(),
            """{"id":"only-id"}""",
        )

        assertEquals("only-id", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertTrue(decoded.ready)
        assertEquals(NodePosition(), decoded.position)
        assertNull(decoded.retry)
        assertNull(decoded.timeoutSeconds)
    }
}

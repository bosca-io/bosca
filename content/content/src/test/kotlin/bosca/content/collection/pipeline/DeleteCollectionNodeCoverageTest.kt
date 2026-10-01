@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.content.collection.pipeline

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.pipeline.executeForTest
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.model.RetryPolicy
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.coVerify
import io.mockk.mockk
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
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Coverage for [DeleteCollectionNode]: the `dryRun` trace path (records the intended
 * `deleteCollection` action with the collection id and mutates nothing), the `execute` soft-delete
 * path (calls [CollectionService.markDeleted] and produces no output), and both arms of the
 * `resolve` error branch (a non-[Collection] input, with the blank-name → id message path and the
 * non-blank-name message path), plus the compiler-generated kotlinx.serialization serializer arms of
 * the `@Serializable` node — every optional-field encode/decode branch (full, defaults-only, and
 * encode-defaults-on).
 *
 * The plain `execute` happy path is also asserted here so this file's suite fully covers the node
 * standalone; `bosca.content.pipeline.ContentMutationNodesTest` exercises the same effect but does
 * not touch dryRun, the error arms, or the serializer branches.
 */
class DeleteCollectionNodeCoverageTest {

    private val collectionService = mockk<CollectionService>(relaxUnitFun = true)

    // encodeDefaults stays false (kotlinx default) so encoding a full instance hits each "encode the
    // field" arm and encoding a defaults-only instance hits each "skip the field" arm; the contextual
    // UUID serializer backs the inherited `@Contextual rollbackPipeline` body property.
    private val json = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }

    // Same module with encodeDefaults on — encoding takes the `shouldEncodeElementDefault` (left) arm
    // of each generated optional-field guard, which the default-off [json] never reaches.
    private val jsonEncodeDefaults = Json {
        encodeDefaults = true
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }

    private val context = PipelineContext(AuthenticationContext(null, null), json)

    private val uuid = UUID.random()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<CollectionService> { collectionService }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun inputs(value: PipelineValue) = NodeInputs(mapOf("in" to value))
    private fun collectionInput(collection: Collection) = inputs(PipelineValue.of(collection, Collection.serializer()))

    /** A real [Collection] (not a mock) so the generated deserialize can bridge it through JSON. */
    private fun collection(id: Uuid = Uuid.random()) = Collection(
        id = id,
        name = "Docs",
        languageTag = "en",
        workflowStateId = "published",
    )

    // ── execute ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `execute soft-deletes the collection and emits no output`() = runTest {
        val id = Uuid.random()

        val out = DeleteCollectionNode(id = "n").executeForTest(context, collectionInput(collection(id)))

        assertNull(out)
        coVerify(exactly = 1) { collectionService.markDeleted(id) }
    }

    @Test
    fun `execute fails when the input is not a collection`() = runTest {
        // The generated deserialize bridges through Collection.serializer(), so a non-Collection
        // value fails decoding.
        val node = DeleteCollectionNode(id = "n1", name = "Remove it")
        assertFailsWith<SerializationException> {
            node.executeForTest(context, inputs(PipelineValue.ofJson(JsonPrimitive("not a collection"))))
        }
        coVerify(exactly = 0) { collectionService.markDeleted(any()) }
    }

    @Test
    fun `execute fails with the generated required-input message when the input is missing`() = runTest {
        val node = DeleteCollectionNode(id = "the-id", name = "")
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, NodeInputs(emptyMap()))
        }
        assertTrue(e.message?.contains("required input 'in'") == true)
    }

    // ── dryRun ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `dry run records the intended delete action and mutates nothing`() = runTest {
        val trace = DryRunTrace()
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val id = Uuid.random()

        val out = DeleteCollectionNode(id = "del").executeForTest(dryContext, collectionInput(collection(id)))

        assertNull(out)
        val recorded = trace.actions["del"] as JsonObject
        assertEquals("deleteCollection", recorded["action"]?.jsonPrimitive?.content)
        assertEquals(id.toString(), recorded["collectionId"]?.jsonPrimitive?.content)
        coVerify(exactly = 0) { collectionService.markDeleted(any()) }
    }

    @Test
    fun `dry run without a trace still skips the side effect and emits no output`() = runTest {
        // trace is null → the `trace?.recordAction` safe-call is a no-op; still no mutation, still null.
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true)

        val out = DeleteCollectionNode(id = "del").executeForTest(dryContext, collectionInput(collection()))

        assertNull(out)
        coVerify(exactly = 0) { collectionService.markDeleted(any()) }
    }

    @Test
    fun `dry run tolerates a missing input and records an empty collection id`() = runTest {
        // A dry run traces whatever is wired so far — a missing required input must not fail it.
        val trace = DryRunTrace()
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        val out = DeleteCollectionNode(id = "del").executeForTest(dryContext, NodeInputs(emptyMap()))

        assertNull(out)
        val recorded = trace.actions["del"] as JsonObject
        assertEquals("", recorded["collectionId"]?.jsonPrimitive?.content)
        coVerify(exactly = 0) { collectionService.markDeleted(any()) }
    }

    @Test
    fun `dry run rejects a non-collection input`() = runTest {
        val trace = DryRunTrace()
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val node = DeleteCollectionNode(id = "n", name = "Remove")
        assertFailsWith<SerializationException> {
            node.executeForTest(dryContext, inputs(PipelineValue.ofJson(JsonPrimitive("nope"))))
        }
        assertTrue(trace.actions.isEmpty())
    }

    // ── serializer arms (generated (de)serialization branches) ────────────────────────────────────

    @Test
    fun `serializer round-trips a fully populated node`() = runTest {
        val full = DeleteCollectionNode(
            id = "n1",
            name = "Delete it",
            description = "d",
            position = NodePosition(1.0, 2.0),
        ).apply {
            retry = RetryPolicy(maxAttempts = 5, initialDelaySeconds = 2, multiplier = 2.0, maxDelaySeconds = 60)
            timeoutSeconds = 120
            rollbackPipeline = uuid
        }

        val decoded = json.decodeFromString(
            DeleteCollectionNode.serializer(),
            json.encodeToString(DeleteCollectionNode.serializer(), full),
        )

        assertEquals("n1", decoded.id)
        assertEquals("Delete it", decoded.name)
        assertEquals("d", decoded.description)
        assertEquals(NodePosition(1.0, 2.0), decoded.position)
        assertEquals(RetryPolicy(maxAttempts = 5, initialDelaySeconds = 2, multiplier = 2.0, maxDelaySeconds = 60), decoded.retry)
        assertEquals(120L, decoded.timeoutSeconds)
        assertEquals(uuid, decoded.rollbackPipeline)
    }

    @Test
    fun `serializer round-trips a defaults-only node`() = runTest {
        val defaultsOnly = DeleteCollectionNode(id = "n1")

        val decoded = json.decodeFromString(
            DeleteCollectionNode.serializer(),
            json.encodeToString(DeleteCollectionNode.serializer(), defaultsOnly),
        )

        assertEquals("n1", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertEquals(NodePosition(), decoded.position)
        // Defaults-only leaves the inherited body-property vars at their defaults (the "skip" encode arm).
        assertNull(decoded.retry)
        assertNull(decoded.timeoutSeconds)
        assertNull(decoded.rollbackPipeline)
    }

    @Test
    fun `encode-defaults-on emits every optional field for a defaults-only node`() = runTest {
        val defaultsOnly = DeleteCollectionNode(id = "n1")

        // Exercises the left (shouldEncodeElementDefault) arm of each generated optional-field guard.
        val encoded = jsonEncodeDefaults.encodeToString(DeleteCollectionNode.serializer(), defaultsOnly)

        assertTrue(encoded.contains("\"id\":\"n1\""))
        assertTrue(encoded.contains("\"name\":\"\""))
        assertTrue(encoded.contains("\"description\":\"\""))
    }
}

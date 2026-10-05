@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.content.collection.pipeline

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.pipeline.executeForTest
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.PipelineContext
import bosca.pipelines.model.RetryPolicy
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
 * Coverage for [CollectionEventToCollectionNode]: the two `execute` error arms — a missing/invalid
 * collection UUID and a not-found collection — each exercised down both the blank-name → id and the
 * non-blank-name message paths, plus the bare-UUID-string input path. Also round-trips the
 * `@Serializable` node (every optional-field encode/decode branch: full, defaults-only, and
 * encode-defaults-on) to cover the compiler-generated kotlinx.serialization serializer arms.
 *
 * The uuid-value happy path is already covered by `bosca.content.pipeline.ContentResolverNodesTest`
 * ("collection fromEvent resolves the collection for a uuid value"); this file covers what it does not.
 */
class CollectionEventToCollectionNodeCoverageTest {

    private val collectionService = mockk<CollectionService>()

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

    // ── execute ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `execute resolves the collection for a bare uuid string`() = runTest {
        val id = Uuid.random()
        val collection = mockk<Collection>()
        coEvery { collectionService.getById(id) } returns collection

        val node = CollectionEventToCollectionNode(id = "n1")
        val out = node.executeForTest(context, inputs(PipelineValue.ofJson(JsonPrimitive(id.toString()))))

        assertSame(collection, out?.value)
        coVerify(exactly = 1) { collectionService.getById(id) }
    }

    @Test
    fun `execute fails when the input is not a uuid`() = runTest {
        // The generated deserialize bridges through UUIDSerializer, so a non-UUID string fails parsing.
        val node = CollectionEventToCollectionNode(id = "n1", name = "Load collection")
        assertFailsWith<IllegalArgumentException> {
            node.executeForTest(context, inputs(PipelineValue.ofJson(JsonPrimitive("not a uuid"))))
        }
    }

    @Test
    fun `execute fails with the generated required-input message when the input is missing`() = runTest {
        val node = CollectionEventToCollectionNode(id = "the-id", name = "")
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, NodeInputs(emptyMap()))
        }
        assertTrue(e.message?.contains("required input 'in'") == true)
    }

    @Test
    fun `execute fails clearly using the node name when the collection does not exist`() = runTest {
        val id = Uuid.random()
        coEvery { collectionService.getById(id) } returns null

        val node = CollectionEventToCollectionNode(id = "n1", name = "Load collection")
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, inputs(PipelineValue.of(id, UUIDSerializer())))
        }
        assertTrue(e.message?.contains("Load collection") == true)
        assertTrue(e.message?.contains("not found") == true)
        assertTrue(e.message?.contains(id.toString()) == true)
    }

    @Test
    fun `execute not-found error falls back to the id when the name is blank`() = runTest {
        val id = Uuid.random()
        coEvery { collectionService.getById(id) } returns null

        val node = CollectionEventToCollectionNode(id = "the-id", name = "")
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, inputs(PipelineValue.of(id, UUIDSerializer())))
        }
        assertTrue(e.message?.contains("the-id") == true)
        assertTrue(e.message?.contains("not found") == true)
    }

    // ── serializer arms (generated (de)serialization branches) ────────────────────────────────────

    @Test
    fun `serializer round-trips a fully populated node`() = runTest {
        val full = CollectionEventToCollectionNode(
            id = "n1",
            name = "Get collection",
            description = "d",
            position = NodePosition(1.0, 2.0),
        ).apply {
            retry = RetryPolicy(maxAttempts = 5, initialDelaySeconds = 2, multiplier = 2.0, maxDelaySeconds = 60)
            timeoutSeconds = 120
            rollbackPipeline = uuid
        }

        val decoded = json.decodeFromString(
            CollectionEventToCollectionNode.serializer(),
            json.encodeToString(CollectionEventToCollectionNode.serializer(), full),
        )

        assertEquals("n1", decoded.id)
        assertEquals("Get collection", decoded.name)
        assertEquals("d", decoded.description)
        assertEquals(NodePosition(1.0, 2.0), decoded.position)
        assertEquals(RetryPolicy(maxAttempts = 5, initialDelaySeconds = 2, multiplier = 2.0, maxDelaySeconds = 60), decoded.retry)
        assertEquals(120L, decoded.timeoutSeconds)
        assertEquals(uuid, decoded.rollbackPipeline)
    }

    @Test
    fun `serializer round-trips a defaults-only node`() = runTest {
        val defaultsOnly = CollectionEventToCollectionNode(id = "n1")

        val decoded = json.decodeFromString(
            CollectionEventToCollectionNode.serializer(),
            json.encodeToString(CollectionEventToCollectionNode.serializer(), defaultsOnly),
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
        val defaultsOnly = CollectionEventToCollectionNode(id = "n1")

        // Exercises the left (shouldEncodeElementDefault) arm of each generated optional-field guard.
        val encoded = jsonEncodeDefaults.encodeToString(CollectionEventToCollectionNode.serializer(), defaultsOnly)

        assertTrue(encoded.contains("\"name\":\"\""))
        assertTrue(encoded.contains("\"description\":\"\""))
    }
}

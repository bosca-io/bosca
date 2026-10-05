@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.content.collection.pipeline

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionItem
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
import kotlinx.serialization.SerializationException
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
 * Coverage for [CollectionItemsNode]: the `execute` error arm (a non-[Collection] input, with both
 * the blank-name → id and the non-blank-name message paths) and the default-settings happy path,
 * plus the compiler-generated kotlinx.serialization serializer arms of the `@Serializable` node
 * (every optional-field encode/decode branch — full, defaults-only, and encode-defaults-on).
 *
 * The passthrough-of-configured-settings happy path is already covered by
 * `bosca.content.pipeline.ContentResolverNodesTest`; this file covers what it does not.
 */
class CollectionItemsNodeCoverageTest {

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

    /** A real [Collection] (not a mock) so the generated deserialize can bridge it through JSON. */
    private fun collection(id: Uuid = Uuid.random()) = Collection(
        id = id,
        name = "Docs",
        languageTag = "en",
        workflowStateId = "published",
    )

    // ── execute ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `execute with default settings resolves all items from the start of the ordering`() = runTest {
        val id = Uuid.random()
        val items = listOf(mockk<CollectionItem>())
        coEvery {
            collectionService.getItems(id, null, 0, 100, null, null, true, true)
        } returns items

        val node = CollectionItemsNode(id = "n1")
        val out = node.executeForTest(context, inputs(PipelineValue.of(collection(id), Collection.serializer())))

        assertSame(items, out?.value)
        coVerify(exactly = 1) { collectionService.getItems(id, null, 0, 100, null, null, true, true) }
    }

    @Test
    fun `execute fails when the input is not a collection`() = runTest {
        // The generated deserialize bridges through Collection.serializer(), so a non-Collection
        // value fails decoding.
        val node = CollectionItemsNode(id = "n1", name = "Load items")
        assertFailsWith<SerializationException> {
            node.executeForTest(context, inputs(PipelineValue.ofJson(JsonPrimitive("not a collection"))))
        }
    }

    @Test
    fun `execute fails with the generated required-input message when the input is missing`() = runTest {
        val node = CollectionItemsNode(id = "the-id", name = "")
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, NodeInputs(emptyMap()))
        }
        assertTrue(e.message?.contains("required input 'in'") == true)
    }

    // ── serializer arms (generated (de)serialization branches) ────────────────────────────────────

    @Test
    fun `serializer round-trips a fully populated node`() = runTest {
        val full = CollectionItemsNode(
            id = "n1",
            name = "Get items",
            description = "d",
            state = "published",
            limit = 25,
            contentTypes = listOf("bosca/v-document", "bosca/v-image"),
            languageTag = "en",
            position = NodePosition(1.0, 2.0),
        ).apply {
            retry = RetryPolicy(maxAttempts = 5, initialDelaySeconds = 2, multiplier = 2.0, maxDelaySeconds = 60)
            timeoutSeconds = 120
            rollbackPipeline = uuid
        }

        val decoded = json.decodeFromString(
            CollectionItemsNode.serializer(),
            json.encodeToString(CollectionItemsNode.serializer(), full),
        )

        assertEquals("n1", decoded.id)
        assertEquals("Get items", decoded.name)
        assertEquals("d", decoded.description)
        assertEquals("published", decoded.state)
        assertEquals(25, decoded.limit)
        assertEquals(listOf("bosca/v-document", "bosca/v-image"), decoded.contentTypes)
        assertEquals("en", decoded.languageTag)
        assertEquals(NodePosition(1.0, 2.0), decoded.position)
        assertEquals(RetryPolicy(maxAttempts = 5, initialDelaySeconds = 2, multiplier = 2.0, maxDelaySeconds = 60), decoded.retry)
        assertEquals(120L, decoded.timeoutSeconds)
        assertEquals(uuid, decoded.rollbackPipeline)
    }

    @Test
    fun `serializer round-trips a defaults-only node`() = runTest {
        val defaultsOnly = CollectionItemsNode(id = "n1")

        val decoded = json.decodeFromString(
            CollectionItemsNode.serializer(),
            json.encodeToString(CollectionItemsNode.serializer(), defaultsOnly),
        )

        assertEquals("n1", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertNull(decoded.state)
        assertEquals(100, decoded.limit)
        assertNull(decoded.contentTypes)
        assertNull(decoded.languageTag)
        assertEquals(NodePosition(), decoded.position)
        // Defaults-only leaves the inherited body-property vars at their defaults (the "skip" encode arm).
        assertNull(decoded.retry)
        assertNull(decoded.timeoutSeconds)
        assertNull(decoded.rollbackPipeline)
    }

    @Test
    fun `encode-defaults-on emits every optional field for a defaults-only node`() = runTest {
        val defaultsOnly = CollectionItemsNode(id = "n1")

        // Exercises the left (shouldEncodeElementDefault) arm of each generated optional-field guard.
        val encoded = jsonEncodeDefaults.encodeToString(CollectionItemsNode.serializer(), defaultsOnly)

        assertTrue(encoded.contains("\"limit\":100"))
        assertTrue(encoded.contains("\"name\":\"\""))
    }
}

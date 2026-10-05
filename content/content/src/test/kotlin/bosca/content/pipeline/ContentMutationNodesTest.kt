@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.content.pipeline

import bosca.content.collection.model.Collection
import bosca.content.collection.pipeline.DeleteCollectionNode
import bosca.content.collection.pipeline.SetCollectionAttributesNode
import bosca.content.collection.pipeline.SetCollectionPublicNode
import bosca.content.collection.pipeline.SetCollectionRecommendableNode
import bosca.content.collection.pipeline.SetCollectionReadyNode
import bosca.content.collection.pipeline.SetCollectionSearchableNode
import bosca.content.collection.pipeline.TransitionCollectionNode
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.pipeline.DeleteMetadataNode
import bosca.content.metadata.pipeline.SetMetadataAttributesNode
import bosca.content.metadata.pipeline.SetMetadataPublicNode
import bosca.content.metadata.pipeline.SetMetadataRecommendableNode
import bosca.content.metadata.pipeline.SetMetadataReadyNode
import bosca.content.metadata.pipeline.SetMetadataSearchableNode
import bosca.content.metadata.pipeline.TransitionMetadataNode
import bosca.content.metadata.service.MetadataService
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * Unit coverage for the content-manipulation action nodes (metadata + collection lifecycle): readiness,
 * public visibility, searchability, workflow transition, attribute writes, and soft delete. Each node
 * is driven through its real [bosca.pipelines.node.PipelineNode.run] entry point via [executeForTest].
 */
class ContentMutationNodesTest {

    private val metadataService = mockk<MetadataService>(relaxUnitFun = true)
    private val collectionService = mockk<CollectionService>(relaxUnitFun = true)

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
        provides<CollectionService> { collectionService }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun input(value: PipelineValue) = NodeInputs(mapOf("in" to value))
    private fun metadataInput(metadata: Metadata) = input(PipelineValue.of(metadata, Metadata.serializer()))
    private fun collectionInput(collection: Collection) = input(PipelineValue.of(collection, Collection.serializer()))

    /** Real model instances (not mocks) so the generated deserialize can bridge them through JSON. */
    private fun metadata(id: Uuid = Uuid.random()) = Metadata(
        id = id,
        name = "Doc",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "draft",
    )

    private fun collection(id: Uuid = Uuid.random()) = Collection(
        id = id,
        name = "Docs",
        languageTag = "en",
        workflowStateId = "draft",
    )

    // ── Set Metadata Ready ────────────────────────────────────────────────────────────────────────

    @Test
    fun `set metadata ready marks ready with the run principal`() = runTest {
        val metadata = metadata()
        val updated = mockk<Metadata>()
        coEvery { metadataService.setReady(metadata, principal) } returns updated

        val node = SetMetadataReadyNode(id = "n", ready = true)
        val out = node.executeForTest(authedContext, metadataInput(metadata))

        assertSame(updated, out?.value)
        coVerify(exactly = 1) { metadataService.setReady(metadata, principal) }
    }

    @Test
    fun `set metadata not ready revokes and re-fetches`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)
        val fresh = mockk<Metadata>()
        coEvery { metadataService.getById(id) } returns fresh

        val node = SetMetadataReadyNode(id = "n", ready = false)
        val out = node.executeForTest(authedContext, metadataInput(metadata))

        assertSame(fresh, out?.value)
        coVerify(exactly = 1) { metadataService.setNotReady(metadata) }
    }

    @Test
    fun `set metadata ready fails without an authenticated principal`() = runTest {
        val node = SetMetadataReadyNode(id = "n", name = "Approve", ready = true)
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, metadataInput(metadata()))
        }
        assertTrue(e.message?.contains("Approve") == true)
    }

    @Test
    fun `set metadata ready rejects a non-metadata input`() = runTest {
        // The generated deserialize bridges through Metadata.serializer(), so a non-Metadata value
        // fails decoding.
        val node = SetMetadataReadyNode(id = "n")
        assertFailsWith<SerializationException> {
            node.executeForTest(authedContext, input(PipelineValue.ofJson(JsonPrimitive("nope"))))
        }
    }

    // ── Set Metadata Public ───────────────────────────────────────────────────────────────────────

    @Test
    fun `set metadata public toggles each target then re-fetches`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)
        val fresh = mockk<Metadata>()
        coEvery { metadataService.getById(id) } returns fresh

        SetMetadataPublicNode(id = "n", target = "metadata", public = true)
            .executeForTest(context, metadataInput(metadata))
        SetMetadataPublicNode(id = "n", target = "content", public = false)
            .executeForTest(context, metadataInput(metadata))
        SetMetadataPublicNode(id = "n", target = "supplementary", public = true)
            .executeForTest(context, metadataInput(metadata))

        coVerify(exactly = 1) { metadataService.setPublic(metadata, true) }
        coVerify(exactly = 1) { metadataService.setPublicContent(metadata, false) }
        coVerify(exactly = 1) { metadataService.setPublicSupplementary(metadata, true) }
    }

    @Test
    fun `set metadata public rejects an unknown target`() = runTest {
        val node = SetMetadataPublicNode(id = "n", name = "Publish", target = "bogus")
        val e = assertFailsWith<IllegalArgumentException> {
            node.executeForTest(context, metadataInput(metadata()))
        }
        assertTrue(e.message?.contains("bogus") == true)
    }

    @Test
    fun `set metadata public dry run records intent and mutates nothing`() = runTest {
        val trace = DryRunTrace()
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val metadata = metadata()

        val out = SetMetadataPublicNode(id = "pub", target = "metadata", public = true)
            .executeForTest(dryContext, metadataInput(metadata))

        assertSame(metadata, out?.value)
        assertTrue(trace.actions.containsKey("pub"))
        coVerify(exactly = 0) { metadataService.setPublic(any(), any()) }
    }

    // ── Set Metadata Searchable ───────────────────────────────────────────────────────────────────

    @Test
    fun `set metadata searchable updates then re-fetches`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)
        val fresh = mockk<Metadata>()
        coEvery { metadataService.getById(id) } returns fresh

        val out = SetMetadataSearchableNode(id = "n", searchable = false)
            .executeForTest(context, metadataInput(metadata))

        assertSame(fresh, out?.value)
        coVerify(exactly = 1) { metadataService.setSearchable(id, false) }
    }

    @Test
    fun `set metadata recommendable updates then re-fetches`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)
        val fresh = mockk<Metadata>()
        coEvery { metadataService.getById(id) } returns fresh

        val out = SetMetadataRecommendableNode(id = "n", recommendable = false)
            .executeForTest(context, metadataInput(metadata))

        assertSame(fresh, out?.value)
        coVerify(exactly = 1) { metadataService.setRecommendable(id, false) }
    }

    @Test
    fun `set metadata recommendable dry run records intent without updating`() = runTest {
        val trace = DryRunTrace()
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val metadata = metadata()

        val out = SetMetadataRecommendableNode(id = "recommend", recommendable = false)
            .executeForTest(dryContext, metadataInput(metadata))

        assertSame(metadata, out?.value)
        assertTrue(trace.actions.containsKey("recommend"))
        coVerify(exactly = 0) { metadataService.setRecommendable(any(), any()) }
    }

    @Test
    fun `set metadata recommendable fails when the updated item cannot be reloaded`() = runTest {
        val metadata = metadata()
        coEvery { metadataService.getById(metadata.id) } returns null

        val error = assertFailsWith<IllegalStateException> {
            SetMetadataRecommendableNode(id = "n", name = "Recommendation Gate")
                .executeForTest(context, metadataInput(metadata))
        }

        assertTrue(error.message?.contains("Recommendation Gate") == true)
    }

    // ── Transition Metadata ───────────────────────────────────────────────────────────────────────

    @Test
    fun `transition metadata sets the state with the run principal`() = runTest {
        val metadata = metadata()
        val updated = mockk<Metadata>()
        coEvery { metadataService.setState(metadata, "published", "go", principal) } returns updated

        val out = TransitionMetadataNode(id = "n", state = "published", status = "go")
            .executeForTest(authedContext, metadataInput(metadata))

        assertSame(updated, out?.value)
    }

    @Test
    fun `transition metadata requires a target state`() = runTest {
        val node = TransitionMetadataNode(id = "n", name = "Move", state = "  ")
        assertFailsWith<IllegalArgumentException> {
            node.executeForTest(authedContext, metadataInput(metadata()))
        }
    }

    // ── Set Metadata Attributes ───────────────────────────────────────────────────────────────────

    @Test
    fun `set metadata attributes merges by default`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)
        val fresh = mockk<Metadata>()
        coEvery { metadataService.getById(id) } returns fresh
        val attrs = buildJsonObject { put("k", "v") }

        val node = SetMetadataAttributesNode(id = "n", merge = true)
        val inputs = NodeInputs(
            mapOf(
                "in" to PipelineValue.of(metadata, Metadata.serializer()),
                "attributes" to PipelineValue.ofJson(attrs),
            ),
        )
        val out = node.executeForTest(context, inputs)

        assertSame(fresh, out?.value)
        coVerify(exactly = 1) { metadataService.mergeAttributes(metadata, attrs) }
        coVerify(exactly = 0) { metadataService.setAttributes(any(), any()) }
    }

    @Test
    fun `set metadata attributes replaces when merge is off`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)
        coEvery { metadataService.getById(id) } returns mockk<Metadata>()
        val attrs = buildJsonObject { put("k", "v") }

        val node = SetMetadataAttributesNode(id = "n", merge = false)
        val inputs = NodeInputs(
            mapOf(
                "in" to PipelineValue.of(metadata, Metadata.serializer()),
                "attributes" to PipelineValue.ofJson(attrs),
            ),
        )
        node.executeForTest(context, inputs)

        coVerify(exactly = 1) { metadataService.setAttributes(metadata, attrs) }
    }

    @Test
    fun `set metadata attributes fails without an attributes input`() = runTest {
        val node = SetMetadataAttributesNode(id = "n", name = "Attrs")
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, metadataInput(metadata()))
        }
        assertTrue(e.message?.contains("attributes") == true)
    }

    // ── Delete Metadata ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `delete metadata soft-deletes and emits no output`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)

        val out = DeleteMetadataNode(id = "n").executeForTest(context, metadataInput(metadata))

        assertNull(out)
        coVerify(exactly = 1) { metadataService.markDeleted(id) }
    }

    @Test
    fun `delete metadata dry run records intent without deleting`() = runTest {
        val trace = DryRunTrace()
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val id = Uuid.random()

        val out = DeleteMetadataNode(id = "del").executeForTest(dryContext, metadataInput(metadata(id)))

        assertNull(out)
        assertTrue(trace.actions.containsKey("del"))
        coVerify(exactly = 0) { metadataService.markDeleted(any()) }
    }

    @Test
    fun `delete metadata dry run tolerates a missing input and still records the intent`() = runTest {
        // A dry run traces whatever is wired so far — a missing required input must not fail it.
        val trace = DryRunTrace()
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        val out = DeleteMetadataNode(id = "del").executeForTest(dryContext, NodeInputs(emptyMap()))

        assertNull(out)
        assertTrue(trace.actions.containsKey("del"))
        coVerify(exactly = 0) { metadataService.markDeleted(any()) }
    }

    @Test
    fun `delete metadata dry run without a trace still skips the delete`() = runTest {
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true)

        val out = DeleteMetadataNode(id = "del").executeForTest(dryContext, metadataInput(metadata()))

        assertNull(out)
        coVerify(exactly = 0) { metadataService.markDeleted(any()) }
    }

    // ── Set Collection Ready ──────────────────────────────────────────────────────────────────────

    @Test
    fun `set collection ready marks the variant ready with the run principal`() = runTest {
        val id = Uuid.random()
        val collection = collection(id)
        val fresh = mockk<Collection>()
        coEvery { collectionService.getById(id) } returns fresh

        val node = SetCollectionReadyNode(id = "n", ready = true, languageTag = "en")
        val out = node.executeForTest(authedContext, collectionInput(collection))

        assertSame(fresh, out?.value)
        coVerify(exactly = 1) { collectionService.setReady(id, principal, "en") }
    }

    @Test
    fun `set collection not ready revokes readiness`() = runTest {
        val id = Uuid.random()
        val collection = collection(id)
        coEvery { collectionService.getById(id) } returns mockk<Collection>()

        SetCollectionReadyNode(id = "n", ready = false).executeForTest(authedContext, collectionInput(collection))

        coVerify(exactly = 1) { collectionService.setNotReady(collection) }
    }

    // ── Set Collection Public ─────────────────────────────────────────────────────────────────────

    @Test
    fun `set collection public toggles each target then re-fetches`() = runTest {
        val id = Uuid.random()
        val collection = collection(id)
        coEvery { collectionService.getById(id) } returns mockk<Collection>()

        SetCollectionPublicNode(id = "n", target = "collection", public = true)
            .executeForTest(context, collectionInput(collection))
        SetCollectionPublicNode(id = "n", target = "list", public = false, languageTag = "fr")
            .executeForTest(context, collectionInput(collection))
        SetCollectionPublicNode(id = "n", target = "supplementary", public = true)
            .executeForTest(context, collectionInput(collection))

        coVerify(exactly = 1) { collectionService.setPublic(id, true, null) }
        coVerify(exactly = 1) { collectionService.setPublicList(id, false, "fr") }
        coVerify(exactly = 1) { collectionService.setPublicSupplementary(id, true, null) }
    }

    // ── Set Collection Searchable ─────────────────────────────────────────────────────────────────

    @Test
    fun `set collection searchable updates then re-fetches`() = runTest {
        val id = Uuid.random()
        val collection = collection(id)
        val fresh = mockk<Collection>()
        coEvery { collectionService.getById(id) } returns fresh

        val out = SetCollectionSearchableNode(id = "n", searchable = false)
            .executeForTest(context, collectionInput(collection))

        assertSame(fresh, out?.value)
        coVerify(exactly = 1) { collectionService.setSearchable(id, false) }
    }

    @Test
    fun `set collection recommendable updates then re-fetches`() = runTest {
        val id = Uuid.random()
        val collection = collection(id)
        val fresh = mockk<Collection>()
        coEvery { collectionService.getById(id) } returns fresh

        val out = SetCollectionRecommendableNode(id = "n", recommendable = false)
            .executeForTest(context, collectionInput(collection))

        assertSame(fresh, out?.value)
        coVerify(exactly = 1) { collectionService.setRecommendable(id, false) }
    }

    @Test
    fun `set collection recommendable dry run records intent without updating`() = runTest {
        val trace = DryRunTrace()
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val collection = collection()

        val out = SetCollectionRecommendableNode(id = "recommend", recommendable = false)
            .executeForTest(dryContext, collectionInput(collection))

        assertSame(collection, out?.value)
        assertTrue(trace.actions.containsKey("recommend"))
        coVerify(exactly = 0) { collectionService.setRecommendable(any(), any()) }
    }

    @Test
    fun `set collection recommendable fails when the updated item cannot be reloaded`() = runTest {
        val collection = collection()
        coEvery { collectionService.getById(collection.id) } returns null

        val error = assertFailsWith<IllegalStateException> {
            SetCollectionRecommendableNode(id = "n", name = "Recommendation Gate")
                .executeForTest(context, collectionInput(collection))
        }

        assertTrue(error.message?.contains("Recommendation Gate") == true)
    }

    // ── Transition Collection ─────────────────────────────────────────────────────────────────────

    @Test
    fun `transition collection sets the state then re-fetches`() = runTest {
        val id = Uuid.random()
        val collection = collection(id)
        val fresh = mockk<Collection>()
        coEvery { collectionService.setState(collection, "published", "go", principal) } returns mockk()
        coEvery { collectionService.getById(id) } returns fresh

        val out = TransitionCollectionNode(id = "n", state = "published", status = "go")
            .executeForTest(authedContext, collectionInput(collection))

        assertSame(fresh, out?.value)
        coVerify(exactly = 1) { collectionService.setState(collection, "published", "go", principal) }
    }

    // ── Set Collection Attributes ─────────────────────────────────────────────────────────────────

    @Test
    fun `set collection attributes merges and replaces by flag`() = runTest {
        val id = Uuid.random()
        val collection = collection(id)
        coEvery { collectionService.getById(id) } returns mockk<Collection>()
        val attrs = buildJsonObject { put("k", "v") }

        fun inputs() = NodeInputs(
            mapOf(
                "in" to PipelineValue.of(collection, Collection.serializer()),
                "attributes" to PipelineValue.ofJson(attrs),
            ),
        )

        SetCollectionAttributesNode(id = "n", merge = true).executeForTest(context, inputs())
        SetCollectionAttributesNode(id = "n", merge = false).executeForTest(context, inputs())

        coVerify(exactly = 1) { collectionService.mergeAttributes(id, attrs) }
        coVerify(exactly = 1) { collectionService.setAttributes(id, attrs) }
    }

    // ── Delete Collection ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `delete collection soft-deletes and emits no output`() = runTest {
        val id = Uuid.random()
        val collection = collection(id)

        val out = DeleteCollectionNode(id = "n").executeForTest(context, collectionInput(collection))

        assertNull(out)
        coVerify(exactly = 1) { collectionService.markDeleted(id) }
    }
}

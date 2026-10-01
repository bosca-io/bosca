package bosca.content.metadata.jobs

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.model.ContentRelationship
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.polymorphic
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Coverage test for [MetadataRelationshipRemovedExecutor.syncVariantRelationship], driven through the
 * inherited [MetadataSyncExecutor.execute] flow. The companion job data class is already covered by
 * [MetadataRelationshipJobsTest]; this test exercises every branch of the executor's sync logic:
 * the sync-disabled short-circuit, the variant filter, the language-variant `let`, and the try/catch.
 */
@OptIn(InternalDI::class)
class MetadataRelationshipRemovedExecutorCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val jobQueue = mockk<JobQueue>()
    private val connectionManager = mockk<ConnectionManager>(relaxed = true)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
            polymorphic(ContentRelationship::class, MetadataRelationship::class, MetadataRelationship.serializer())
        }
    }

    private val executor = MetadataRelationshipRemovedExecutor(metadataService)

    @BeforeTest
    fun setup() {
        provides<Json> { json }
        provides<JobQueue>(name = "contentQueue") { jobQueue }
        coEvery { jobQueue.enqueue(any()) } returns UUID.random()
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    private fun metadata(
        id: UUID,
        parentId: UUID? = null,
        languageTag: String = "en",
        syncVariantCollections: Boolean = true,
        syncVariantRelationships: Boolean = true,
    ): Metadata = Metadata(
        id = id,
        parentId = parentId,
        name = "test",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = languageTag,
        workflowStateId = "pending",
        syncVariantCollections = syncVariantCollections,
        syncVariantRelationships = syncVariantRelationships,
    )

    private fun relationship(id1: UUID, id2: UUID, name: String = "related"): MetadataRelationship =
        MetadataRelationship(metadataId1 = id1, metadataId2 = id2, relationship = name)

    private suspend fun run(job: MetadataRelationshipRemovedJob) {
        val jobObject = InternalJobConstructor(
            definition = json.encodeToJsonElement(job),
            executor = MetadataRelationshipRemovedExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(jobObject) + connectionManager.asCoroutineContext()) {
            executor.execute()
        }
    }

    @Test
    fun `syncs variant relationships when enabled and language variant exists`() = runTest {
        val parentId = UUID.random()
        val relatedId = UUID.random()
        val variantId = UUID.random()
        val languageVariantId = UUID.random()

        val parent = metadata(parentId, syncVariantRelationships = true)
        val variant = metadata(variantId, parentId = parentId, languageTag = "es", syncVariantRelationships = true)
        val rel = relationship(parentId, relatedId)

        coEvery { metadataService.getById(parentId) } returns parent
        coEvery { metadataService.getByParentId(parentId) } returns listOf(parent, variant)
        coEvery { metadataService.getLanguageVariantById(relatedId, "es") } returns languageVariantId
        coEvery { metadataService.removeRelationship(any(), any(), any()) } returns Unit
        coEvery { metadataService.markCollaborationRelationshipsDirty(any()) } returns Unit

        run(MetadataRelationshipRemovedJob(id = parentId, relationship = rel))

        coVerify(exactly = 1) { metadataService.removeRelationship(variantId, languageVariantId, "related") }
        coVerify(exactly = 1) { metadataService.removeRelationship(variantId, relatedId, "related") }
        coVerify(exactly = 1) { metadataService.markCollaborationRelationshipsDirty(variantId) }
        coVerify(exactly = 1) { metadataService.markCollaborationRelationshipsDirty(parentId) }
        coVerify(exactly = 2) { jobQueue.enqueue(any()) }
    }

    @Test
    fun `skips language variant removal when no language variant exists`() = runTest {
        val parentId = UUID.random()
        val relatedId = UUID.random()
        val variantId = UUID.random()

        val parent = metadata(parentId, syncVariantRelationships = true)
        val variant = metadata(variantId, parentId = parentId, languageTag = "fr", syncVariantRelationships = true)
        val rel = relationship(parentId, relatedId)

        coEvery { metadataService.getById(parentId) } returns parent
        coEvery { metadataService.getByParentId(parentId) } returns listOf(parent, variant)
        coEvery { metadataService.getLanguageVariantById(relatedId, "fr") } returns null
        coEvery { metadataService.removeRelationship(any(), any(), any()) } returns Unit
        coEvery { metadataService.markCollaborationRelationshipsDirty(any()) } returns Unit

        run(MetadataRelationshipRemovedJob(id = parentId, relationship = rel))

        coVerify(exactly = 1) { metadataService.removeRelationship(variantId, relatedId, "related") }
        coVerify(exactly = 0) { metadataService.getLanguageVariantById(relatedId, "en") }
    }

    @Test
    fun `filters out variants that do not sync relationships`() = runTest {
        val parentId = UUID.random()
        val relatedId = UUID.random()
        val syncingVariantId = UUID.random()
        val nonSyncingVariantId = UUID.random()

        val parent = metadata(parentId, syncVariantRelationships = true)
        val syncingVariant = metadata(syncingVariantId, parentId = parentId, languageTag = "es", syncVariantRelationships = true)
        val nonSyncingVariant = metadata(nonSyncingVariantId, parentId = parentId, languageTag = "de", syncVariantRelationships = false)
        val rel = relationship(parentId, relatedId)

        coEvery { metadataService.getById(parentId) } returns parent
        coEvery { metadataService.getByParentId(parentId) } returns listOf(parent, syncingVariant, nonSyncingVariant)
        coEvery { metadataService.getLanguageVariantById(relatedId, "es") } returns null
        coEvery { metadataService.removeRelationship(any(), any(), any()) } returns Unit
        coEvery { metadataService.markCollaborationRelationshipsDirty(any()) } returns Unit

        run(MetadataRelationshipRemovedJob(id = parentId, relationship = rel))

        coVerify(exactly = 1) { metadataService.removeRelationship(syncingVariantId, relatedId, "related") }
        coVerify(exactly = 0) { metadataService.removeRelationship(nonSyncingVariantId, any(), any()) }
        coVerify(exactly = 0) { metadataService.markCollaborationRelationshipsDirty(nonSyncingVariantId) }
    }

    @Test
    fun `swallows exception thrown while removing variant relationship`() = runTest {
        val parentId = UUID.random()
        val relatedId = UUID.random()
        val variantId = UUID.random()

        val parent = metadata(parentId, syncVariantRelationships = true)
        val variant = metadata(variantId, parentId = parentId, languageTag = "es", syncVariantRelationships = true)
        val rel = relationship(parentId, relatedId)

        coEvery { metadataService.getById(parentId) } returns parent
        coEvery { metadataService.getByParentId(parentId) } returns listOf(parent, variant)
        coEvery { metadataService.getLanguageVariantById(relatedId, "es") } returns null
        coEvery { metadataService.removeRelationship(variantId, relatedId, "related") } throws RuntimeException("boom")
        coEvery { metadataService.markCollaborationRelationshipsDirty(any()) } returns Unit

        // Exception inside the per-variant try/catch is logged and swallowed; parent-level work still runs.
        run(MetadataRelationshipRemovedJob(id = parentId, relationship = rel))

        coVerify(exactly = 1) { metadataService.markCollaborationRelationshipsDirty(parentId) }
    }

    @Test
    fun `marks collaboration dirty and returns when metadata does not sync relationships`() = runTest {
        val parentId = UUID.random()
        val relatedId = UUID.random()

        val parent = metadata(parentId, syncVariantRelationships = false)
        val rel = relationship(parentId, relatedId)

        coEvery { metadataService.getById(parentId) } returns parent
        coEvery { metadataService.markCollaborationRelationshipsDirty(parentId) } returns Unit

        run(MetadataRelationshipRemovedJob(id = parentId, relationship = rel))

        coVerify(exactly = 1) { metadataService.markCollaborationRelationshipsDirty(parentId) }
        coVerify(exactly = 0) { metadataService.getByParentId(any()) }
        coVerify(exactly = 0) { jobQueue.enqueue(any()) }
    }

    @Test
    fun `resolves variants via self id when metadata has no parent`() = runTest {
        val rootId = UUID.random()
        val relatedId = UUID.random()
        val variantId = UUID.random()

        val root = metadata(rootId, parentId = null, syncVariantRelationships = true)
        val variant = metadata(variantId, parentId = rootId, languageTag = "es", syncVariantRelationships = true)
        val rel = relationship(rootId, relatedId)

        coEvery { metadataService.getById(rootId) } returns root
        coEvery { metadataService.getByParentId(rootId) } returns listOf(root, variant)
        coEvery { metadataService.getLanguageVariantById(relatedId, "es") } returns null
        coEvery { metadataService.removeRelationship(any(), any(), any()) } returns Unit
        coEvery { metadataService.markCollaborationRelationshipsDirty(any()) } returns Unit

        run(MetadataRelationshipRemovedJob(id = rootId, relationship = rel))

        coVerify(exactly = 1) { metadataService.getByParentId(rootId) }
        coVerify(exactly = 1) { metadataService.removeRelationship(variantId, relatedId, "related") }
    }

    @Test
    fun `does nothing when syncVariantCollections is disabled`() = runTest {
        val parentId = UUID.random()
        val relatedId = UUID.random()

        val parent = metadata(parentId, syncVariantCollections = false, syncVariantRelationships = true)
        val rel = relationship(parentId, relatedId)

        coEvery { metadataService.getById(parentId) } returns parent

        run(MetadataRelationshipRemovedJob(id = parentId, relationship = rel))

        coVerify(exactly = 0) { metadataService.getByParentId(any()) }
        coVerify(exactly = 0) { metadataService.markCollaborationRelationshipsDirty(any()) }
    }

    @Test
    fun `does nothing when relationship is null`() = runTest {
        val parentId = UUID.random()
        val parent = metadata(parentId, syncVariantCollections = true, syncVariantRelationships = true)

        coEvery { metadataService.getById(parentId) } returns parent

        run(MetadataRelationshipRemovedJob(id = parentId, relationship = null))

        coVerify(exactly = 0) { metadataService.getByParentId(any()) }
        coVerify(exactly = 0) { metadataService.markCollaborationRelationshipsDirty(any()) }
    }

    @Test
    fun `errors when metadata id is missing`() = runTest {
        assertFailsWith<IllegalStateException> {
            run(MetadataRelationshipRemovedJob(id = null, relationship = relationship(UUID.random(), UUID.random())))
        }
    }

    @Test
    fun `errors when metadata is not found`() = runTest {
        val parentId = UUID.random()
        coEvery { metadataService.getById(parentId) } returns null

        assertFailsWith<IllegalStateException> {
            run(MetadataRelationshipRemovedJob(id = parentId, relationship = relationship(parentId, UUID.random())))
        }
    }
}

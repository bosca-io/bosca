package bosca.content.metadata.jobs

import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.model.ContentRelationship
import bosca.core.annotations.Internal
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.LocalDateTimeSerializer
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.serialization.ZonedDateTimeSerializer
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

/**
 * Unit coverage for [MetadataRelationshipAddedExecutor] driven through the public [execute] entry
 * point. The sibling end-to-end tests exercise the full publish-and-sync flow against a real
 * database; this test drives every branch of the executor's relationship-sync logic with mocked
 * collaborators only.
 */
@OptIn(InternalDI::class)
class MetadataRelationshipAddedExecutorCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val jobQueue = mockk<JobQueue>(relaxed = true)
    private val connectionManager = mockk<ConnectionManager>(relaxed = true)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
            contextual(OffsetDateTimeSerializer())
            contextual(LocalDateTimeSerializer())
            contextual(ZonedDateTimeSerializer())
            polymorphic(ContentRelationship::class, MetadataRelationship::class, MetadataRelationship.serializer())
            polymorphic(ContentRelationship::class, CollectionMetadataRelationship::class, CollectionMetadataRelationship.serializer())
        }
    }

    private val executor = MetadataRelationshipAddedExecutor(metadataService)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
        provides<JobQueue>(name = "contentQueue") { jobQueue }
        coEvery { jobQueue.enqueue(any()) } returns UUID.random()
        coEvery { metadataService.markCollaborationRelationshipsDirty(any()) } returns Unit
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    private fun metadata(
        id: UUID = UUID.random(),
        parentId: UUID? = null,
        syncVariantCollections: Boolean = true,
        syncVariantRelationships: Boolean = true,
        languageTag: String = "en",
        version: Int = 1,
    ): Metadata = Metadata(
        id = id,
        version = version,
        parentId = parentId,
        name = "meta-$id",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = languageTag,
        workflowStateId = "published",
        syncVariantCollections = syncVariantCollections,
        syncVariantRelationships = syncVariantRelationships,
    )

    private fun relationship(id1: UUID, id2: UUID): MetadataRelationship = MetadataRelationship(
        metadataId1 = id1,
        metadataId2 = id2,
        relationship = "related",
    )

    /**
     * Runs [MetadataRelationshipAddedExecutor.execute] with the given parent metadata as the job
     * target and [rel] as the job's relationship, inside a coroutine context that supplies both the
     * running [job] and a [ConnectionManager] for the `transaction { }` blocks.
     */
    @OptIn(Internal::class)
    private suspend fun execute(parent: Metadata, rel: MetadataRelationship?) {
        val config = MetadataRelationshipAddedJob(id = parent.id, relationship = rel)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = MetadataRelationshipAddedExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(job) + connectionManager.asCoroutineContext()) {
            executor.execute()
        }
    }

    @Test
    fun `skips sync entirely when metadata does not sync variant collections`() = runTest {
        val parent = metadata(syncVariantCollections = false)
        coEvery { metadataService.getById(parent.id) } returns parent
        val rel = relationship(parent.id, UUID.random())

        execute(parent, rel)

        coVerify(exactly = 0) { metadataService.getByParentId(any()) }
        coVerify(exactly = 0) { metadataService.markCollaborationRelationshipsDirty(any()) }
    }

    @Test
    fun `marks dirty and returns early when parent does not sync variant relationships`() = runTest {
        val parent = metadata(syncVariantRelationships = false)
        coEvery { metadataService.getById(parent.id) } returns parent
        val rel = relationship(parent.id, UUID.random())

        execute(parent, rel)

        val parentId = parent.id
        coVerify(exactly = 1) { metadataService.markCollaborationRelationshipsDirty(parentId) }
        coVerify(exactly = 0) { metadataService.getByParentId(any()) }
        coVerify(exactly = 0) { metadataService.addRelationship(any<MetadataRelationship>()) }
    }

    @Test
    fun `syncs to variant using resolved language variant id`() = runTest {
        val parent = metadata()
        val relatedId = UUID.random()
        val rel = relationship(parent.id, relatedId)

        val variant = metadata(languageTag = "fr")
        val resolvedVariantId = UUID.random()

        coEvery { metadataService.getById(parent.id) } returns parent
        coEvery { metadataService.getByParentId(parent.id) } returns listOf(variant, parent)
        coEvery { metadataService.getLanguageVariantById(relatedId, "fr") } returns resolvedVariantId
        coEvery { metadataService.addRelationship(any<MetadataRelationship>()) } answers { firstArg() }

        execute(parent, rel)

        val variantId = variant.id
        val parentId = parent.id
        // addRelationship called once with the resolved variant id as metadataId2
        coVerify(exactly = 1) {
            metadataService.addRelationship(match<MetadataRelationship> {
                it.metadataId1 == variantId && it.metadataId2 == resolvedVariantId && it.relationship == "related"
            })
        }
        coVerify(exactly = 1) { metadataService.markCollaborationRelationshipsDirty(variantId) }
        coVerify(exactly = 1) { metadataService.markCollaborationRelationshipsDirty(parentId) }
        coVerify(exactly = 1) { metadataService.getLanguageVariantById(relatedId, "fr") }
        coVerify(exactly = 2) { jobQueue.enqueue(any()) }
    }

    @Test
    fun `falls back to original related id when language variant is missing`() = runTest {
        val parent = metadata()
        val relatedId = UUID.random()
        val rel = relationship(parent.id, relatedId)

        val variant = metadata(languageTag = "es")

        coEvery { metadataService.getById(parent.id) } returns parent
        coEvery { metadataService.getByParentId(parent.id) } returns listOf(variant, parent)
        coEvery { metadataService.getLanguageVariantById(relatedId, "es") } returns null
        coEvery { metadataService.addRelationship(any<MetadataRelationship>()) } answers { firstArg() }

        execute(parent, rel)

        val variantId = variant.id
        coVerify(exactly = 1) {
            metadataService.addRelationship(match<MetadataRelationship> {
                it.metadataId1 == variantId && it.metadataId2 == relatedId
            })
        }
        coVerify(exactly = 1) { metadataService.markCollaborationRelationshipsDirty(variantId) }
    }

    @Test
    fun `skips variants that do not sync variant relationships`() = runTest {
        val parent = metadata()
        val relatedId = UUID.random()
        val rel = relationship(parent.id, relatedId)

        val nonSyncingVariant = metadata(syncVariantRelationships = false, languageTag = "de")

        coEvery { metadataService.getById(parent.id) } returns parent
        coEvery { metadataService.getByParentId(parent.id) } returns listOf(nonSyncingVariant, parent)

        execute(parent, rel)

        val variantId = nonSyncingVariant.id
        val parentId = parent.id
        // The non-syncing variant is filtered out: no relationship added, no per-variant dirty mark.
        coVerify(exactly = 0) { metadataService.addRelationship(any<MetadataRelationship>()) }
        coVerify(exactly = 0) { metadataService.getLanguageVariantById(any(), any()) }
        coVerify(exactly = 0) { metadataService.markCollaborationRelationshipsDirty(variantId) }
        // The parent is still marked dirty and enqueued.
        coVerify(exactly = 1) { metadataService.markCollaborationRelationshipsDirty(parentId) }
        coVerify(exactly = 1) { jobQueue.enqueue(any()) }
    }

    @Test
    fun `catches and logs exception while adding variant relationship then continues`() = runTest {
        val parent = metadata()
        val relatedId = UUID.random()
        val rel = relationship(parent.id, relatedId)

        val variant = metadata(languageTag = "it")

        coEvery { metadataService.getById(parent.id) } returns parent
        coEvery { metadataService.getByParentId(parent.id) } returns listOf(variant, parent)
        coEvery { metadataService.getLanguageVariantById(relatedId, "it") } returns null
        coEvery { metadataService.addRelationship(any<MetadataRelationship>()) } throws RuntimeException("boom")

        execute(parent, rel)

        val variantId = variant.id
        val parentId = parent.id
        // The per-variant dirty mark is skipped because addRelationship threw before it.
        coVerify(exactly = 0) { metadataService.markCollaborationRelationshipsDirty(variantId) }
        // But the parent-level bookkeeping still runs after the caught error.
        coVerify(exactly = 1) { metadataService.markCollaborationRelationshipsDirty(parentId) }
        coVerify(exactly = 1) { jobQueue.enqueue(any()) }
    }

    @Test
    fun `resolves variants via parent id when metadata has a parent`() = runTest {
        val parentContentId = UUID.random()
        val child = metadata(parentId = parentContentId)
        val relatedId = UUID.random()
        val rel = relationship(child.id, relatedId)

        val sibling = metadata(languageTag = "fr")

        coEvery { metadataService.getById(child.id) } returns child
        // getVariants uses parentId when non-null.
        coEvery { metadataService.getByParentId(parentContentId) } returns listOf(sibling, child)
        coEvery { metadataService.getLanguageVariantById(relatedId, "fr") } returns null
        coEvery { metadataService.addRelationship(any<MetadataRelationship>()) } answers { firstArg() }

        execute(child, rel)

        val siblingId = sibling.id
        coVerify(exactly = 1) { metadataService.getByParentId(parentContentId) }
        coVerify(exactly = 1) { metadataService.markCollaborationRelationshipsDirty(siblingId) }
    }
}

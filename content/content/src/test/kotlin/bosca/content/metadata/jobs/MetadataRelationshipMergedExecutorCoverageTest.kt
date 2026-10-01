package bosca.content.metadata.jobs

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.content.model.ContentRelationship
import bosca.core.annotations.Internal
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.polymorphic
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Unit coverage for [MetadataRelationshipMergedExecutor.syncVariantRelationship], driven through the
 * inherited [MetadataSyncExecutor.execute] entry point. The DI/coroutine-context scaffolding mirrors
 * how the runner invokes a job executor: a job in the coroutine context, a JSON provider for
 * deserialization, and a JobQueue registered under the `contentQueue` name for the generated
 * `MetadataIndexJob.enqueue()` extension.
 */
@OptIn(InternalDI::class, Internal::class)
class MetadataRelationshipMergedExecutorCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val jobQueue = mockk<JobQueue>(relaxed = true)
    private val connectionManager = mockk<ConnectionManager>(relaxed = true)
    private val eventManager = EventManager()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
            contextual(OffsetDateTimeSerializer())
            polymorphic(ContentRelationship::class, MetadataRelationship::class, MetadataRelationship.serializer())
            polymorphic(ContentRelationship::class, CollectionMetadataRelationship::class, CollectionMetadataRelationship.serializer())
        }
    }

    private val executor = MetadataRelationshipMergedExecutor(metadataService)

    @BeforeTest
    fun setup() {
        provides<Json> { json }
        provides<MetadataService> { metadataService }
        provides<JobQueue>(name = "contentQueue") { jobQueue }
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
    ): Metadata = Metadata(
        id = id,
        parentId = parentId,
        name = "m-$id",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published",
        syncVariantCollections = syncVariantCollections,
        syncVariantRelationships = syncVariantRelationships,
    )

    private fun relationship(
        id1: UUID,
        id2: UUID = UUID.random(),
        rel: String = "variant",
        attributes: JsonElement? = null,
    ): MetadataRelationship = MetadataRelationship(
        metadataId1 = id1,
        metadataId2 = id2,
        relationship = rel,
        attributes = attributes,
    )

    private fun buildJob(rel: ContentRelationship?, id: UUID?): Job {
        val config = MetadataRelationshipMergedJob(id = id, relationship = rel)
        return InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = MetadataRelationshipMergedExecutor::class,
        )
    }

    private suspend fun run(job: Job) {
        withContext(
            jobQueue.asCoroutineContext(job) +
                connectionManager.asCoroutineContext() +
                eventManager.asCoroutineContext()
        ) {
            executor.execute()
        }
    }

    @Test
    fun `syncs relationship to variants when sync flags enabled`() = runTest {
        val parent = metadata(syncVariantCollections = true, syncVariantRelationships = true)
        val variant = metadata(parentId = parent.id, syncVariantRelationships = true)
        val rel = relationship(id1 = parent.id, attributes = JsonObject(emptyMap()))

        coEvery { metadataService.getById(parent.id) } returns parent
        coEvery { metadataService.getByParentId(parent.id) } returns listOf(parent, variant)
        coEvery { metadataService.mergeAttributes(any(), any(), any(), any()) } returns Unit
        coEvery { metadataService.markCollaborationRelationshipsDirty(any()) } returns Unit

        val variantId = variant.id
        val parentId = parent.id
        val relId2 = rel.id2
        run(buildJob(rel, parent.id))

        coVerify(exactly = 1) { metadataService.mergeAttributes(variantId, relId2, "variant", JsonObject(emptyMap())) }
        coVerify { metadataService.markCollaborationRelationshipsDirty(variantId) }
        coVerify { metadataService.markCollaborationRelationshipsDirty(parentId) }
    }

    @Test
    fun `uses empty attributes when relationship attributes are null`() = runTest {
        val parent = metadata()
        val variant = metadata(parentId = parent.id, syncVariantRelationships = true)
        val rel = relationship(id1 = parent.id, attributes = null)

        coEvery { metadataService.getById(parent.id) } returns parent
        coEvery { metadataService.getByParentId(parent.id) } returns listOf(variant)
        coEvery { metadataService.mergeAttributes(any(), any(), any(), any()) } returns Unit
        coEvery { metadataService.markCollaborationRelationshipsDirty(any()) } returns Unit

        val variantId = variant.id
        val relId2 = rel.id2
        run(buildJob(rel, parent.id))

        coVerify(exactly = 1) { metadataService.mergeAttributes(variantId, relId2, "variant", JsonObject(emptyMap())) }
    }

    @Test
    fun `marks metadata dirty and returns early when metadata does not sync variant relationships`() = runTest {
        val parent = metadata(syncVariantCollections = true, syncVariantRelationships = false)
        val rel = relationship(id1 = parent.id)

        coEvery { metadataService.getById(parent.id) } returns parent
        coEvery { metadataService.markCollaborationRelationshipsDirty(parent.id) } returns Unit

        val parentId = parent.id
        run(buildJob(rel, parent.id))

        coVerify(exactly = 1) { metadataService.markCollaborationRelationshipsDirty(parentId) }
        coVerify(exactly = 0) { metadataService.getByParentId(any()) }
        coVerify(exactly = 0) { metadataService.mergeAttributes(any(), any(), any(), any()) }
    }

    @Test
    fun `filters out variants that do not sync variant relationships`() = runTest {
        val parent = metadata(syncVariantRelationships = true)
        val syncingVariant = metadata(parentId = parent.id, syncVariantRelationships = true)
        val nonSyncingVariant = metadata(parentId = parent.id, syncVariantRelationships = false)
        val rel = relationship(id1 = parent.id)

        coEvery { metadataService.getById(parent.id) } returns parent
        coEvery { metadataService.getByParentId(parent.id) } returns listOf(syncingVariant, nonSyncingVariant)
        coEvery { metadataService.mergeAttributes(any(), any(), any(), any()) } returns Unit
        coEvery { metadataService.markCollaborationRelationshipsDirty(any()) } returns Unit

        val syncingId = syncingVariant.id
        val nonSyncingId = nonSyncingVariant.id
        run(buildJob(rel, parent.id))

        coVerify(exactly = 1) { metadataService.mergeAttributes(syncingId, any(), any(), any()) }
        coVerify(exactly = 0) { metadataService.mergeAttributes(nonSyncingId, any(), any(), any()) }
    }

    @Test
    fun `swallows exception from mergeAttributes and logs error`() = runTest {
        val parent = metadata(syncVariantRelationships = true)
        val variant = metadata(parentId = parent.id, syncVariantRelationships = true)
        val rel = relationship(id1 = parent.id)

        coEvery { metadataService.getById(parent.id) } returns parent
        coEvery { metadataService.getByParentId(parent.id) } returns listOf(variant)
        coEvery { metadataService.mergeAttributes(any(), any(), any(), any()) } throws RuntimeException("boom")
        coEvery { metadataService.markCollaborationRelationshipsDirty(any()) } returns Unit

        val parentId = parent.id
        // Should not throw: the try/catch inside the forEach swallows and logs the error.
        run(buildJob(rel, parent.id))

        // The outer markCollaborationRelationshipsDirty(metadata.id) still runs after the caught failure.
        coVerify { metadataService.markCollaborationRelationshipsDirty(parentId) }
    }

    @Test
    fun `does nothing when relationship is null`() = runTest {
        val parent = metadata(syncVariantCollections = true, syncVariantRelationships = true)

        coEvery { metadataService.getById(parent.id) } returns parent

        run(buildJob(null, parent.id))

        coVerify(exactly = 0) { metadataService.getByParentId(any()) }
        coVerify(exactly = 0) { metadataService.markCollaborationRelationshipsDirty(any()) }
    }
}

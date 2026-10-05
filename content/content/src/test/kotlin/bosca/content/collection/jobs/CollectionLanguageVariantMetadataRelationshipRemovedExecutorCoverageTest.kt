package bosca.content.collection.jobs

import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.content.collection.service.CollectionService
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.OffsetDateTimeSerializer
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Unit coverage for [CollectionLanguageVariantMetadataRelationshipRemovedExecutor.execute]. The
 * executor reads its job definition, early-returns when `relationship` is null, and otherwise marks
 * the collection's collaboration relationships dirty. Both arms of the elvis-return branch are
 * exercised here. The sibling `CollectionLanguageVariantMetadataRelationshipJobsTest` already covers
 * the [CollectionLanguageVariantMetadataRelationshipRemovedJob] data class, so this file only drives
 * the executor.
 */
@OptIn(InternalDI::class)
class CollectionLanguageVariantMetadataRelationshipRemovedExecutorCoverageTest {

    private val collectionService = mockk<CollectionService>(relaxed = true)
    private val jobQueue = mockk<JobQueue>(relaxed = true)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
            contextual(OffsetDateTimeSerializer())
        }
    }

    private val executor = CollectionLanguageVariantMetadataRelationshipRemovedExecutor(collectionService)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    @OptIn(Internal::class)
    private suspend fun execute(config: CollectionLanguageVariantMetadataRelationshipRemovedJob) {
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = CollectionLanguageVariantMetadataRelationshipRemovedExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }
    }

    @Test
    fun `marks collaboration relationships dirty for the job relationship`() = runTest {
        val collectionId = UUID.random()
        val relationship = CollectionLanguageVariantMetadataRelationship(
            collectionId = collectionId,
            metadataId = UUID.random(),
            languageTag = "en",
            relationship = "translates",
        )
        coEvery { collectionService.markCollaborationRelationshipsDirty(collectionId, "en") } returns Unit

        execute(CollectionLanguageVariantMetadataRelationshipRemovedJob(id = UUID.random(), relationship = relationship))

        coVerify(exactly = 1) { collectionService.markCollaborationRelationshipsDirty(collectionId, "en") }
    }

    @Test
    fun `returns early and does nothing when the relationship is null`() = runTest {
        execute(CollectionLanguageVariantMetadataRelationshipRemovedJob(id = UUID.random(), relationship = null))

        coVerify(exactly = 0) { collectionService.markCollaborationRelationshipsDirty(any(), any()) }
    }
}

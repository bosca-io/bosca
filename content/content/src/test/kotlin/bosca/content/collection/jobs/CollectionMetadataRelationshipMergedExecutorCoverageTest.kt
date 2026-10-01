package bosca.content.collection.jobs

import bosca.content.collection.model.CollectionMetadataRelationship
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
import kotlin.test.assertFailsWith

/**
 * Unit coverage for [CollectionMetadataRelationshipMergedExecutor.execute]. The executor reads its
 * job definition and marks the collection's collaboration relationships dirty using `job.id`,
 * erroring when the id is null. Both arms of the elvis-error branch are exercised here. The sibling
 * [CollectionMetadataRelationshipJobsTest] already covers the
 * [CollectionMetadataRelationshipMergedJob] data class, so this file only drives the executor.
 */
@OptIn(InternalDI::class)
class CollectionMetadataRelationshipMergedExecutorCoverageTest {

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

    private val executor = CollectionMetadataRelationshipMergedExecutor(collectionService)

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

    private fun createRelationship(): CollectionMetadataRelationship {
        return CollectionMetadataRelationship(
            collectionId = UUID.random(),
            metadataId = UUID.random(),
            relationship = "contains",
        )
    }

    @OptIn(Internal::class)
    private suspend fun execute(config: CollectionMetadataRelationshipMergedJob) {
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = CollectionMetadataRelationshipMergedExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `marks collaboration relationships dirty for the job id`() = runTest {
        val collectionId = UUID.random()
        coEvery { collectionService.markCollaborationRelationshipsDirty(collectionId, null) } returns Unit

        execute(CollectionMetadataRelationshipMergedJob(id = collectionId, relationship = createRelationship()))

        coVerify(exactly = 1) { collectionService.markCollaborationRelationshipsDirty(collectionId, null) }
    }

    @OptIn(Internal::class)
    @Test
    fun `marks dirty even when the relationship is null`() = runTest {
        val collectionId = UUID.random()
        coEvery { collectionService.markCollaborationRelationshipsDirty(collectionId, null) } returns Unit

        execute(CollectionMetadataRelationshipMergedJob(id = collectionId, relationship = null))

        coVerify(exactly = 1) { collectionService.markCollaborationRelationshipsDirty(collectionId, null) }
    }

    @OptIn(Internal::class)
    @Test
    fun `errors when the collection id is null`() = runTest {
        assertFailsWith<IllegalStateException> {
            execute(CollectionMetadataRelationshipMergedJob(id = null, relationship = createRelationship()))
        }

        coVerify(exactly = 0) { collectionService.markCollaborationRelationshipsDirty(any(), any()) }
    }
}

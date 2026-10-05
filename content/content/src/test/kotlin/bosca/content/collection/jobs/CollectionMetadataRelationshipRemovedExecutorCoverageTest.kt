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
 * Unit coverage for [CollectionMetadataRelationshipRemovedExecutor.execute]. The executor reads its
 * job definition and marks the collection's collaboration relationships dirty, using
 * `job.id ?: error("missing collection id")`. Both arms of that elvis-to-error branch are exercised:
 * a non-null id marks the collection dirty, and a null id throws. The sibling
 * `CollectionMetadataRelationshipJobsTest` already covers the
 * [CollectionMetadataRelationshipRemovedJob] data class, so this file only drives the executor.
 */
@OptIn(InternalDI::class)
class CollectionMetadataRelationshipRemovedExecutorCoverageTest {

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

    private val executor = CollectionMetadataRelationshipRemovedExecutor(collectionService)

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
    private suspend fun execute(config: CollectionMetadataRelationshipRemovedJob) {
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = CollectionMetadataRelationshipRemovedExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }
    }

    @Test
    fun `marks collaboration relationships dirty for the job collection id`() = runTest {
        val collectionId = UUID.random()
        coEvery { collectionService.markCollaborationRelationshipsDirty(collectionId, null) } returns Unit

        execute(CollectionMetadataRelationshipRemovedJob(id = collectionId, relationship = createRelationship()))

        coVerify(exactly = 1) { collectionService.markCollaborationRelationshipsDirty(collectionId, null) }
    }

    @Test
    fun `marks dirty even when relationship is null as only id is used`() = runTest {
        val collectionId = UUID.random()
        coEvery { collectionService.markCollaborationRelationshipsDirty(collectionId, null) } returns Unit

        execute(CollectionMetadataRelationshipRemovedJob(id = collectionId, relationship = null))

        coVerify(exactly = 1) { collectionService.markCollaborationRelationshipsDirty(collectionId, null) }
    }

    @Test
    fun `throws when the collection id is missing`() = runTest {
        assertFailsWith<IllegalStateException> {
            execute(CollectionMetadataRelationshipRemovedJob(id = null, relationship = createRelationship()))
        }

        coVerify(exactly = 0) { collectionService.markCollaborationRelationshipsDirty(any(), any()) }
    }
}

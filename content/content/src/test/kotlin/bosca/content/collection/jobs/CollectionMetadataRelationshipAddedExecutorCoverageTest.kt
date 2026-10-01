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
 * Unit coverage for [CollectionMetadataRelationshipAddedExecutor.execute]. The executor reads its
 * job definition and marks the collection's collaboration relationships dirty, using
 * `job.id ?: error("missing collection id")`. Both arms of that elvis branch are exercised here: a
 * present id marks the relationships dirty, and a null id throws [IllegalStateException]. The sibling
 * [CollectionMetadataRelationshipJobsTest] already covers the
 * [CollectionMetadataRelationshipAddedJob] data class, so this file only drives the executor.
 */
@OptIn(InternalDI::class)
class CollectionMetadataRelationshipAddedExecutorCoverageTest {

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

    private val executor = CollectionMetadataRelationshipAddedExecutor(collectionService)

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
    private suspend fun execute(config: CollectionMetadataRelationshipAddedJob) {
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = CollectionMetadataRelationshipAddedExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }
    }

    private fun createRelationship(collectionId: UUID): CollectionMetadataRelationship {
        return CollectionMetadataRelationship(
            collectionId = collectionId,
            metadataId = UUID.random(),
            relationship = "contains",
        )
    }

    @Test
    fun `marks collaboration relationships dirty for the job id`() = runTest {
        val collectionId = UUID.random()
        coEvery { collectionService.markCollaborationRelationshipsDirty(collectionId, null) } returns Unit

        execute(
            CollectionMetadataRelationshipAddedJob(
                id = collectionId,
                relationship = createRelationship(collectionId),
            )
        )

        coVerify(exactly = 1) { collectionService.markCollaborationRelationshipsDirty(collectionId, null) }
    }

    @Test
    fun `throws when the job id is null`() = runTest {
        assertFailsWith<IllegalStateException> {
            execute(
                CollectionMetadataRelationshipAddedJob(
                    id = null,
                    relationship = null,
                )
            )
        }

        coVerify(exactly = 0) { collectionService.markCollaborationRelationshipsDirty(any(), any()) }
    }
}

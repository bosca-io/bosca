package bosca.content.jobs

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(InternalDI::class)
class FinalizeDeletionExecutorTest {

    private val collectionService = mockk<CollectionService>()
    private val metadataService = mockk<MetadataService>()
    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val executor = FinalizeDeletionExecutor()

    @BeforeTest
    fun setup() {
        provides<MetadataService> { metadataService }
        provides<CollectionService> { collectionService }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @OptIn(Internal::class)
    @Test
    fun `finalizes deletion for metadata and collections`() = runTest {
        val metadataId = UUID.random()
        val collectionId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        val metadata = mockk<Metadata>()
        every { metadata.id } returns metadataId
        coEvery { metadataService.getDeleted(0, 100) } returnsMany listOf(listOf(metadata), emptyList())
        coEvery { metadataService.delete(metadata) } returns Unit

        val collection = mockk<Collection>()
        every { collection.id } returns collectionId
        coEvery { collectionService.getDeleted(0, 100) } returnsMany listOf(listOf(collection), emptyList())
        coEvery { collectionService.permanentlyDelete(collectionId) } returns Unit

        val config = FinalizeDeletionJob()
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = FinalizeDeletionExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 2) { metadataService.getDeleted(0, 100) }
        coVerify(exactly = 1) { metadataService.delete(metadata) }
        coVerify(exactly = 2) { collectionService.getDeleted(0, 100) }
        coVerify(exactly = 1) { collectionService.permanentlyDelete(collectionId) }
    }
}

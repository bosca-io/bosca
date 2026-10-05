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
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(InternalDI::class)
class FinalizeDeletionExecutorCoverageTest {

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
        clearAllMocks()
        unmockkAll()
        ProviderRegistry.clear()
    }

    @OptIn(Internal::class)
    private fun newJob(): Job {
        val config = FinalizeDeletionJob()
        return InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = FinalizeDeletionExecutor::class,
        )
    }

    /**
     * When every metadata delete in a batch throws, `successfulDeletes` stays 0 while the batch is
     * non-empty, so the executor logs a warning and breaks to avoid an infinite loop. This exercises
     * the metadata catch arm and both sides of the `successfulDeletes == 0 && metadata.isNotEmpty()`
     * guard. Collections return empty immediately so the collection loop breaks on line 48.
     */
    @OptIn(Internal::class)
    @Test
    fun `metadata delete failures trigger warn break to avoid infinite loop`() = runTest {
        val metadataId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        val metadata = mockk<Metadata>()
        every { metadata.id } returns metadataId
        // Non-empty on every call; delete always throws so successfulDeletes == 0 -> break.
        coEvery { metadataService.getDeleted(0, 100) } returns listOf(metadata)
        coEvery { metadataService.delete(metadata) } throws RuntimeException("boom")

        // Collections empty -> immediate break on the empty-list branch.
        coEvery { collectionService.getDeleted(0, 100) } returns emptyList()

        withContext(jobQueue.asCoroutineContext(newJob())) {
            executor.execute()
        }

        // getDeleted was only invoked once because the warn-break exits the loop after one batch.
        coVerify(exactly = 1) { metadataService.getDeleted(0, 100) }
        coVerify(exactly = 1) { metadataService.delete(metadata) }
        coVerify(exactly = 1) { collectionService.getDeleted(0, 100) }
        coVerify(exactly = 0) { collectionService.permanentlyDelete(any()) }
    }

    /**
     * When every collection permanent-delete in a batch throws, `successfulDeletes` stays 0 while the
     * batch is non-empty, so the executor logs a warning and breaks. This exercises the collection
     * catch arm and both sides of the `successfulDeletes == 0 && collections.isNotEmpty()` guard.
     * Metadata returns empty immediately so the metadata loop breaks on line 26.
     */
    @OptIn(Internal::class)
    @Test
    fun `collection delete failures trigger warn break to avoid infinite loop`() = runTest {
        val collectionId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        // Metadata empty -> immediate break on the empty-list branch.
        coEvery { metadataService.getDeleted(0, 100) } returns emptyList()

        val collection = mockk<Collection>()
        every { collection.id } returns collectionId
        coEvery { collectionService.getDeleted(0, 100) } returns listOf(collection)
        coEvery { collectionService.permanentlyDelete(collectionId) } throws RuntimeException("boom")

        withContext(jobQueue.asCoroutineContext(newJob())) {
            executor.execute()
        }

        coVerify(exactly = 1) { metadataService.getDeleted(0, 100) }
        coVerify(exactly = 0) { metadataService.delete(any()) }
        coVerify(exactly = 1) { collectionService.getDeleted(0, 100) }
        coVerify(exactly = 1) { collectionService.permanentlyDelete(collectionId) }
    }

    /**
     * A mixed batch where some deletes succeed keeps `successfulDeletes` > 0, so the guard's false
     * branch is taken and the loop continues to the next (empty) batch, breaking on the empty-list
     * arm. This covers the catch arm together with the guard evaluating to false.
     */
    @OptIn(Internal::class)
    @Test
    fun `partial metadata failure continues loop then breaks on empty batch`() = runTest {
        val goodId = UUID.random()
        val badId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        val good = mockk<Metadata>()
        every { good.id } returns goodId
        val bad = mockk<Metadata>()
        every { bad.id } returns badId

        coEvery { metadataService.getDeleted(0, 100) } returnsMany
            listOf(listOf(good, bad), emptyList())
        coEvery { metadataService.delete(good) } returns Unit
        coEvery { metadataService.delete(bad) } throws RuntimeException("boom")

        coEvery { collectionService.getDeleted(0, 100) } returns emptyList()

        withContext(jobQueue.asCoroutineContext(newJob())) {
            executor.execute()
        }

        // successfulDeletes == 1 (good) so guard is false; loop continues and second batch is empty.
        coVerify(exactly = 2) { metadataService.getDeleted(0, 100) }
        coVerify(exactly = 1) { metadataService.delete(good) }
        coVerify(exactly = 1) { metadataService.delete(bad) }
    }
}

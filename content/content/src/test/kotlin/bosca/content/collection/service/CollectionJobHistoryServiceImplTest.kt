package bosca.content.collection.service

import bosca.content.collection.events.COLLECTION_UPDATED_CHANNEL
import bosca.content.collection.events.CollectionUpdated
import bosca.content.collection.model.CollectionJobHistory
import bosca.content.collection.repository.CollectionJobHistoryRepository
import bosca.content.metadata.service.MetadataJobHistoryServiceImpl
import bosca.content.transition.model.JobHistoryId
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CollectionJobHistoryServiceImplTest {

    private val repository = mockk<CollectionJobHistoryRepository>(relaxed = true)
    private val pubsubService = mockk<PubSubService>(relaxed = true)

    private val service = CollectionJobHistoryServiceImpl(repository, pubsubService)

    private val collectionId = UUID.random()
    private val jobId = UUID.random()

    @Test
    fun `getLatestJobId delegates to repository`() = runTest {
        val expected = JobHistoryId("test-job", jobId)
        coEvery { repository.getLatestJobId(collectionId) } returns expected

        val result = service.getLatestJobId(collectionId)

        assertEquals(expected, result)
    }

    @Test
    fun `getLatestJobId returns null when no history exists`() = runTest {
        coEvery { repository.getLatestJobId(collectionId) } returns null

        val result = service.getLatestJobId(collectionId)

        assertNull(result)
    }

    @Test
    fun `addHistory persists and publishes event`() = runTest {
        val history = CollectionJobHistory(
            id = collectionId,
            jobName = "collection-job",
            jobId = jobId,
            status = "pending",
            principal = null
        )
        coEvery { repository.addHistory(history) } returns history

        val result = service.addHistory(history)

        assertNotNull(result)
        assertEquals("collection-job", result.jobName)
        coVerify { repository.addHistory(history) }
        coVerify { pubsubService.publish(MetadataJobHistoryServiceImpl.CHANNEL, CollectionJobHistory.serializer(), history) }
    }

    @Test
    fun `getHistory delegates to repository`() = runTest {
        val historyList = listOf(
            CollectionJobHistory(
                id = collectionId,
                jobName = "collection-job",
                jobId = jobId,
                status = "complete",
                principal = null
            )
        )
        coEvery { repository.getHistory(collectionId) } returns historyList

        val result = service.getHistory(collectionId)

        assertEquals(1, result.size)
    }

    @Test
    fun `setComplete updates and publishes event`() = runTest {
        val languageTag = "en"
        val completedHistory = CollectionJobHistory(
            id = collectionId,
            jobName = "collection-job",
            jobId = jobId,
            status = "done",
            principal = null,
            languageTag = languageTag,
            success = true
        )
        coEvery { repository.setComplete(collectionId, jobId, "done", true) } returns completedHistory

        service.setComplete(collectionId, jobId, "done", true)

        coVerify { repository.setComplete(collectionId, jobId, "done", true) }
        coVerify { pubsubService.publish(MetadataJobHistoryServiceImpl.CHANNEL, CollectionJobHistory.serializer(), completedHistory) }
        // The admin UI's `collection` subscription listens here; if we stop
        // publishing it, the activeJobs list gets stuck rendering a
        // completed multi-job parent as still in flight. The language tag
        // flows through so variant-scoped transitions refresh the right
        // workflow view.
        coVerify {
            pubsubService.publish(
                eq(COLLECTION_UPDATED_CHANNEL),
                any<kotlinx.serialization.SerializationStrategy<CollectionUpdated>>(),
                match<CollectionUpdated> { it.id == collectionId && it.languageTag == languageTag },
            )
        }
    }
}

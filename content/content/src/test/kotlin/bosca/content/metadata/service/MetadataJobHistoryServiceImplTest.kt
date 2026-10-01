package bosca.content.metadata.service

import bosca.content.metadata.events.METADATA_UPDATED_CHANNEL
import bosca.content.metadata.events.MetadataUpdated
import bosca.content.metadata.model.MetadataJobHistory
import bosca.content.metadata.repository.MetadataJobHistoryRepository
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

class MetadataJobHistoryServiceImplTest {

    private val repository = mockk<MetadataJobHistoryRepository>(relaxed = true)
    private val pubsubService = mockk<PubSubService>(relaxed = true)

    private val service = MetadataJobHistoryServiceImpl(repository, pubsubService)

    private val metadataId = UUID.random()
    private val version = 1
    private val jobId = UUID.random()

    @Test
    fun `getLatestJobId delegates to repository`() = runTest {
        val expected = JobHistoryId("test-job", jobId)
        coEvery { repository.getLatestJobId(metadataId, version) } returns expected

        val result = service.getLatestJobId(metadataId, version)

        assertEquals(expected, result)
    }

    @Test
    fun `getLatestJobId returns null when no history`() = runTest {
        coEvery { repository.getLatestJobId(metadataId, version) } returns null

        val result = service.getLatestJobId(metadataId, version)

        assertNull(result)
    }

    @Test
    fun `addHistory persists and publishes event`() = runTest {
        val history = MetadataJobHistory(
            id = metadataId,
            version = version,
            jobName = "test-job",
            jobId = jobId,
            status = "pending",
            principal = null
        )
        coEvery { repository.addHistory(history) } returns history

        val result = service.addHistory(history)

        assertNotNull(result)
        assertEquals("test-job", result.jobName)
        coVerify { repository.addHistory(history) }
        coVerify { pubsubService.publish(MetadataJobHistoryServiceImpl.CHANNEL, MetadataJobHistory.serializer(), history) }
    }

    @Test
    fun `getHistory delegates to repository`() = runTest {
        val historyList = listOf(
            MetadataJobHistory(
                id = metadataId,
                version = version,
                jobName = "test-job",
                jobId = jobId,
                status = "complete",
                principal = null
            )
        )
        coEvery { repository.getHistory(metadataId, version) } returns historyList

        val result = service.getHistory(metadataId, version)

        assertEquals(1, result.size)
        assertEquals("test-job", result[0].jobName)
    }

    @Test
    fun `setComplete updates status and publishes event`() = runTest {
        val completedHistory = MetadataJobHistory(
            id = metadataId,
            version = version,
            jobName = "test-job",
            jobId = jobId,
            status = "done",
            principal = null,
            success = true
        )
        coEvery { repository.setComplete(metadataId, version, jobId, "done", true) } returns completedHistory

        service.setComplete(metadataId, version, jobId, "done", true)

        coVerify { repository.setComplete(metadataId, version, jobId, "done", true) }
        coVerify { pubsubService.publish(MetadataJobHistoryServiceImpl.CHANNEL, MetadataJobHistory.serializer(), completedHistory) }
        // The admin UI's `metadata` subscription listens here; if we stop
        // publishing it, the activeJobs list gets stuck rendering a
        // completed multi-job parent as still in flight.
        coVerify {
            pubsubService.publish(
                eq(METADATA_UPDATED_CHANNEL),
                any<kotlinx.serialization.SerializationStrategy<MetadataUpdated>>(),
                match<MetadataUpdated> { it.id == metadataId && it.version == version },
            )
        }
    }

    @Test
    fun `setComplete retries until the history row is found, then publishes`() = runTest {
        val completedHistory = MetadataJobHistory(
            id = metadataId,
            version = version,
            jobName = "test-job",
            jobId = jobId,
            status = "done",
            principal = null,
            success = true
        )
        // First attempt loses the race (INSERT not yet committed) and returns null;
        // the second attempt finds the row. The backoff delays are skipped by runTest.
        coEvery {
            repository.setComplete(metadataId, version, jobId, "done", true)
        } returnsMany listOf(null, completedHistory)

        service.setComplete(metadataId, version, jobId, "done", true)

        coVerify(exactly = 2) { repository.setComplete(metadataId, version, jobId, "done", true) }
        coVerify { pubsubService.publish(MetadataJobHistoryServiceImpl.CHANNEL, MetadataJobHistory.serializer(), completedHistory) }
    }

    @Test
    fun `setComplete gives up after exhausting retries without publishing`() = runTest {
        // The repository never finds a matching row (e.g. id/version/jobId mismatch).
        // The relaxed mock returns null for every attempt. The service must NOT publish
        // anything, and the row is left open — exactly the silent failure that leaves a
        // job_history entry stuck "pending" even though the job itself finished.
        coEvery { repository.setComplete(metadataId, version, jobId, "done", true) } returns null

        service.setComplete(metadataId, version, jobId, "done", true)

        // 1 initial attempt + 5 retries.
        coVerify(exactly = 6) { repository.setComplete(metadataId, version, jobId, "done", true) }
        coVerify(exactly = 0) {
            pubsubService.publish(
                eq(MetadataJobHistoryServiceImpl.CHANNEL),
                any<kotlinx.serialization.SerializationStrategy<MetadataJobHistory>>(),
                any<MetadataJobHistory>(),
            )
        }
    }

    @Test
    fun `CHANNEL constant has expected value`() {
        assertEquals("metadata_job_history", MetadataJobHistoryServiceImpl.CHANNEL)
    }
}

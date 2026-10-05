package bosca.content.metadata.service

import bosca.content.metadata.events.METADATA_UPDATED_CHANNEL
import bosca.content.metadata.events.MetadataUpdated
import bosca.content.metadata.model.MetadataJobHistory
import bosca.content.metadata.repository.MetadataJobHistoryRepository
import bosca.pubsub.Message
import bosca.pubsub.PubSubService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Coverage-focused companion to [MetadataJobHistoryServiceImplTest].
 *
 * The sibling test already covers `getLatestJobId`, `addHistory`, `getHistory`, and the
 * three `setComplete` arms (found first try, retry-then-found, gives-up). This file fills the
 * remaining uncovered surface: `setStatus` (both the found→publish and null→early-return arms),
 * `waitForComplete` (already-complete fast path plus the subscribe/collect filter branches), and
 * `getActiveJobs`.
 */
class MetadataJobHistoryServiceImplCoverageTest {

    private val repository = mockk<MetadataJobHistoryRepository>(relaxed = true)
    private val pubsubService = mockk<PubSubService>(relaxed = true)

    private val service = MetadataJobHistoryServiceImpl(repository, pubsubService)

    private val metadataId = UUID.random()
    private val version = 1
    private val jobId = UUID.random()

    @AfterTest
    fun teardown() {
        clearAllMocks()
        unmockkAll()
    }

    private fun history(
        id: UUID = metadataId,
        v: Int = version,
        job: UUID = jobId,
        status: String = "pending",
        complete: OffsetDateTime? = null,
        success: Boolean = false,
    ) = MetadataJobHistory(
        id = id,
        version = v,
        jobName = "test-job",
        jobId = job,
        status = status,
        principal = null,
        complete = complete,
        success = success,
    )

    // ── setStatus ────────────────────────────────────────────────────────────

    @Test
    fun `setStatus publishes when the repository returns the updated row`() = runTest {
        val updated = history(status = "running")
        coEvery { repository.setStatus(metadataId, version, jobId, "running") } returns updated

        service.setStatus(metadataId, version, jobId, "running")

        coVerify { repository.setStatus(metadataId, version, jobId, "running") }
        coVerify {
            pubsubService.publish(
                MetadataJobHistoryServiceImpl.CHANNEL,
                MetadataJobHistory.serializer(),
                updated,
            )
        }
    }

    @Test
    fun `setStatus returns early and does not publish when no row matches`() = runTest {
        // No matching row (e.g. id/version/jobId mismatch) — the elvis `?: return` short-circuits
        // and nothing is published.
        coEvery { repository.setStatus(metadataId, version, jobId, "running") } returns null

        service.setStatus(metadataId, version, jobId, "running")

        coVerify { repository.setStatus(metadataId, version, jobId, "running") }
        coVerify(exactly = 0) {
            pubsubService.publish(
                eq(MetadataJobHistoryServiceImpl.CHANNEL),
                any<kotlinx.serialization.SerializationStrategy<MetadataJobHistory>>(),
                any<MetadataJobHistory>(),
            )
        }
    }

    // ── waitForComplete ───────────────────────────────────────────────────────

    @Test
    fun `waitForComplete returns immediately when the job is already complete`() = runTest {
        val done = history(status = "done", complete = OffsetDateTime.now(), success = true)
        coEvery { repository.getJob(metadataId, version, jobId) } returns done

        val result = service.waitForComplete(metadataId, version, jobId)

        assertNotNull(result)
        assertEquals("done", result.status)
        assertNotNull(result.complete)
        // The fast path must not open a subscription.
        coVerify(exactly = 0) {
            pubsubService.subscribe(
                any<String>(),
                any<kotlinx.serialization.DeserializationStrategy<MetadataJobHistory>>(),
            )
        }
    }

    @Test
    fun `waitForComplete falls through to subscribe when getJob is null`() = runTest {
        coEvery { repository.getJob(metadataId, version, jobId) } returns null
        val completed = history(status = "done", complete = OffsetDateTime.now(), success = true)
        every {
            pubsubService.subscribe(
                MetadataJobHistoryServiceImpl.CHANNEL,
                MetadataJobHistory.serializer(),
            )
        } returns messagesFlow(completed)

        val result = service.waitForComplete(metadataId, version, jobId)

        assertEquals("done", result.status)
        assertNotNull(result.complete)
    }

    @Test
    fun `waitForComplete falls through to subscribe when getJob exists but is not complete`() = runTest {
        // getJob returns a row that is not yet complete — the `job.complete != null` guard fails
        // and control proceeds to the subscription.
        coEvery { repository.getJob(metadataId, version, jobId) } returns history(status = "running", complete = null)
        val completed = history(status = "done", complete = OffsetDateTime.now(), success = true)
        every {
            pubsubService.subscribe(
                MetadataJobHistoryServiceImpl.CHANNEL,
                MetadataJobHistory.serializer(),
            )
        } returns messagesFlow(completed)

        val result = service.waitForComplete(metadataId, version, jobId)

        assertEquals("done", result.status)
    }

    @Test
    fun `waitForComplete ignores non-matching messages and emits only the matching complete one`() = runTest {
        coEvery { repository.getJob(metadataId, version, jobId) } returns null
        val otherId = history(id = UUID.random(), status = "done", complete = OffsetDateTime.now())
        val otherVersion = history(v = 99, status = "done", complete = OffsetDateTime.now())
        val otherJob = history(job = UUID.random(), status = "done", complete = OffsetDateTime.now())
        val matchButIncomplete = history(status = "running", complete = null)
        val match = history(status = "done", complete = OffsetDateTime.now(), success = true)
        every {
            pubsubService.subscribe(
                MetadataJobHistoryServiceImpl.CHANNEL,
                MetadataJobHistory.serializer(),
            )
        } returns messagesFlow(otherId, otherVersion, otherJob, matchButIncomplete, match)

        val result = service.waitForComplete(metadataId, version, jobId)

        // Only the fully-matching complete row is emitted; everything before it is filtered out.
        assertEquals("done", result.status)
        assertNotNull(result.complete)
        assertEquals(true, result.success)
    }

    // ── getActiveJobs ───────────────────────────────────────────────────────

    @Test
    fun `getActiveJobs delegates to the repository`() = runTest {
        val active = listOf(history(status = "running"), history(job = UUID.random(), status = "pending"))
        coEvery { repository.getActiveJobs(metadataId, version) } returns active

        val result = service.getActiveJobs(metadataId, version)

        assertEquals(2, result.size)
        assertEquals("running", result[0].status)
        coVerify { repository.getActiveJobs(metadataId, version) }
    }

    @Test
    fun `getActiveJobs returns empty list when none are active`() = runTest {
        coEvery { repository.getActiveJobs(metadataId, version) } returns emptyList()

        val result = service.getActiveJobs(metadataId, version)

        assertEquals(0, result.size)
    }

    private fun messagesFlow(vararg histories: MetadataJobHistory): Flow<Message<MetadataJobHistory>> = flow {
        for (h in histories) {
            emit(Message(MetadataJobHistoryServiceImpl.CHANNEL, h))
        }
    }
}

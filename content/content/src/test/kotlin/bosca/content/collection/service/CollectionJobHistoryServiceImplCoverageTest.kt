package bosca.content.collection.service

import bosca.content.collection.events.COLLECTION_UPDATED_CHANNEL
import bosca.content.collection.events.CollectionUpdated
import bosca.content.collection.model.CollectionJobHistory
import bosca.content.collection.repository.CollectionJobHistoryRepository
import bosca.content.metadata.service.MetadataJobHistoryServiceImpl
import bosca.pubsub.Message
import bosca.pubsub.PubSubService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Complements [CollectionJobHistoryServiceImplTest], covering the branches that test
 * does not touch: [CollectionJobHistoryServiceImpl.setStatus] (row found and row
 * missing), the [CollectionJobHistoryServiceImpl.setComplete] retry and give-up arms,
 * both arms of [CollectionJobHistoryServiceImpl.waitForComplete], and
 * [CollectionJobHistoryServiceImpl.getActiveJobs].
 */
class CollectionJobHistoryServiceImplCoverageTest {

    private val repository = mockk<CollectionJobHistoryRepository>(relaxed = true)
    private val pubsubService = mockk<PubSubService>(relaxed = true)

    private val service = CollectionJobHistoryServiceImpl(repository, pubsubService)

    private val collectionId = UUID.random()
    private val jobId = UUID.random()

    @AfterTest
    fun teardown() {
        io.mockk.clearAllMocks()
        io.mockk.unmockkAll()
    }

    private fun history(
        id: UUID = collectionId,
        job: UUID = jobId,
        status: String = "done",
        languageTag: String? = "en",
        complete: OffsetDateTime? = null,
        success: Boolean = false,
    ) = CollectionJobHistory(
        id = id,
        jobName = "collection-job",
        jobId = job,
        status = status,
        principal = null,
        languageTag = languageTag,
        complete = complete,
        success = success,
    )

    // ── setStatus ─────────────────────────────────────────────────────────────

    @Test
    fun `setStatus publishes when the row is updated`() = runTest {
        val updated = history(status = "running", languageTag = "es")
        coEvery { repository.setStatus(collectionId, jobId, "running") } returns updated

        service.setStatus(collectionId, jobId, "running")

        coVerify { repository.setStatus(collectionId, jobId, "running") }
        coVerify {
            pubsubService.publish(
                MetadataJobHistoryServiceImpl.CHANNEL,
                CollectionJobHistory.serializer(),
                updated,
            )
        }
    }

    @Test
    fun `setStatus is a no-op when no row matches`() = runTest {
        coEvery { repository.setStatus(collectionId, jobId, "running") } returns null

        service.setStatus(collectionId, jobId, "running")

        coVerify { repository.setStatus(collectionId, jobId, "running") }
        coVerify(exactly = 0) {
            pubsubService.publish(
                any<String>(),
                any<kotlinx.serialization.SerializationStrategy<CollectionJobHistory>>(),
                any<CollectionJobHistory>(),
            )
        }
    }

    // ── setComplete retry / give-up ─────────────────────────────────────────────

    @Test
    fun `setComplete retries until the row appears, then publishes both channels`() = runTest {
        val completed = history(status = "done", languageTag = "fr", success = true)
        // First attempt loses the race (null), second finds it. runTest skips the backoff delays.
        coEvery {
            repository.setComplete(collectionId, jobId, "done", true)
        } returnsMany listOf(null, completed)

        service.setComplete(collectionId, jobId, "done", true)

        coVerify(exactly = 2) { repository.setComplete(collectionId, jobId, "done", true) }
        coVerify {
            pubsubService.publish(
                MetadataJobHistoryServiceImpl.CHANNEL,
                CollectionJobHistory.serializer(),
                completed,
            )
        }
        coVerify {
            pubsubService.publish(
                eq(COLLECTION_UPDATED_CHANNEL),
                any<kotlinx.serialization.SerializationStrategy<CollectionUpdated>>(),
                match<CollectionUpdated> { it.id == collectionId && it.languageTag == "fr" },
            )
        }
    }

    @Test
    fun `setComplete gives up after exhausting retries without publishing`() = runTest {
        coEvery { repository.setComplete(collectionId, jobId, "done", true) } returns null

        service.setComplete(collectionId, jobId, "done", true)

        // 1 initial attempt + 5 retries.
        coVerify(exactly = 6) { repository.setComplete(collectionId, jobId, "done", true) }
        coVerify(exactly = 0) {
            pubsubService.publish(
                any<String>(),
                any<kotlinx.serialization.SerializationStrategy<CollectionJobHistory>>(),
                any<CollectionJobHistory>(),
            )
        }
        coVerify(exactly = 0) {
            pubsubService.publish(
                any<String>(),
                any<kotlinx.serialization.SerializationStrategy<CollectionUpdated>>(),
                any<CollectionUpdated>(),
            )
        }
    }

    // ── waitForComplete ────────────────────────────────────────────────────────

    @Test
    fun `waitForComplete returns immediately when the job is already complete`() = runTest {
        val done = history(complete = OffsetDateTime.now(), success = true)
        coEvery { repository.getJob(collectionId, jobId) } returns done

        val result = service.waitForComplete(collectionId, jobId)

        assertEquals(done, result)
        // The subscribe branch must not be reached.
        coVerify(exactly = 0) {
            pubsubService.subscribe(
                any<String>(),
                any<DeserializationStrategy<CollectionJobHistory>>(),
            )
        }
    }

    @Test
    fun `waitForComplete subscribes and returns the first matching completed message`() = runTest {
        // getJob returns an incomplete row, forcing the subscribe path.
        coEvery { repository.getJob(collectionId, jobId) } returns history(complete = null)

        val matching = history(complete = OffsetDateTime.now(), success = true)
        // Non-matching messages exercise the filter's false arms before the match arrives.
        val wrongId = history(id = UUID.random(), complete = OffsetDateTime.now())
        val wrongJob = history(job = UUID.random(), complete = OffsetDateTime.now())
        val notComplete = history(complete = null)

        every {
            pubsubService.subscribe(
                MetadataJobHistoryServiceImpl.CHANNEL,
                CollectionJobHistory.serializer(),
            )
        } returns flowOf(
            Message(MetadataJobHistoryServiceImpl.CHANNEL, wrongId),
            Message(MetadataJobHistoryServiceImpl.CHANNEL, wrongJob),
            Message(MetadataJobHistoryServiceImpl.CHANNEL, notComplete),
            Message(MetadataJobHistoryServiceImpl.CHANNEL, matching),
        )

        val result = service.waitForComplete(collectionId, jobId)

        assertEquals(matching, result)
    }

    @Test
    fun `waitForComplete subscribes when getJob returns null`() = runTest {
        // getJob null (row absent) also routes through the subscribe branch.
        coEvery { repository.getJob(collectionId, jobId) } returns null

        val matching = history(complete = OffsetDateTime.now(), success = true)
        every {
            pubsubService.subscribe(
                MetadataJobHistoryServiceImpl.CHANNEL,
                CollectionJobHistory.serializer(),
            )
        } returns flowOf(Message(MetadataJobHistoryServiceImpl.CHANNEL, matching))

        val result = service.waitForComplete(collectionId, jobId)

        assertEquals(matching, result)
    }

    // ── getActiveJobs ────────────────────────────────────────────────────────────

    @Test
    fun `getActiveJobs delegates to repository`() = runTest {
        val active = listOf(history(status = "running", complete = null))
        coEvery { repository.getActiveJobs(collectionId) } returns active

        val result = service.getActiveJobs(collectionId)

        assertEquals(active, result)
        coVerify { repository.getActiveJobs(collectionId) }
    }

    @Test
    fun `getActiveJobs returns empty when none active`() = runTest {
        coEvery { repository.getActiveJobs(collectionId) } returns emptyList()

        val result = service.getActiveJobs(collectionId)

        assertEquals(emptyList(), result)
    }
}

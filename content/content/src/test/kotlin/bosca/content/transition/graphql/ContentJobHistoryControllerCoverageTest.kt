package bosca.content.transition.graphql

import bosca.content.metadata.model.MetadataJobHistory
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ContentJobHistoryControllerCoverageTest {

    private val controller = ContentJobHistoryController()

    @OptIn(InternalDI::class)
    @AfterTest
    fun tearDown() {
        clearAllMocks()
        bosca.di.ProviderRegistry.clear()
    }

    private fun history(
        jobName: String = "transition-content",
        id: UUID = UUID.random(),
        jobId: UUID = UUID.random(),
        status: String = "running",
        created: OffsetDateTime = OffsetDateTime.now(),
        complete: OffsetDateTime? = null,
        success: Boolean = false,
        delayedUntil: OffsetDateTime? = null,
    ): ContentJobHistory = ContentJobHistory(
        MetadataJobHistory(
            id = id,
            version = 1,
            jobName = jobName,
            jobId = jobId,
            status = status,
            principal = null,
            created = created,
            complete = complete,
            success = success,
            delayedUntil = delayedUntil,
        )
    )

    @Test
    fun `ContentJobHistory wraps the underlying job`() {
        val job = MetadataJobHistory(
            id = UUID.random(),
            version = 1,
            jobName = "job",
            jobId = UUID.random(),
            status = "running",
            principal = null,
        )
        val wrapper = ContentJobHistory(job)

        assertSame(job, wrapper.job)
    }

    @Test
    fun `jobName returns the underlying job name`() {
        val h = history(jobName = "process-content")

        assertEquals("process-content", controller.jobName(h))
    }

    @Test
    fun `jobId returns the underlying job id`() {
        val id = UUID.random()
        val h = history(jobId = id)

        assertEquals(id, controller.jobId(h))
    }

    @Test
    fun `status returns the underlying status`() {
        val h = history(status = "initial queue")

        assertEquals("initial queue", controller.status(h))
    }

    @Test
    fun `created returns the underlying created timestamp`() {
        val created = OffsetDateTime.now()
        val h = history(created = created)

        assertEquals(created, controller.created(h))
    }

    @Test
    fun `complete returns null when job is still running`() {
        val h = history(complete = null)

        assertNull(controller.complete(h))
    }

    @Test
    fun `complete returns the underlying completion timestamp`() {
        val complete = OffsetDateTime.now()
        val h = history(complete = complete)

        assertEquals(complete, controller.complete(h))
    }

    @Test
    fun `success returns the underlying success flag`() {
        assertTrue(controller.success(history(success = true)))
        assertFalse(controller.success(history(success = false)))
    }

    @Test
    fun `delayedUntil returns null when not delayed`() {
        val h = history(delayedUntil = null)

        assertNull(controller.delayedUntil(h))
    }

    @Test
    fun `delayedUntil returns the underlying delayed timestamp`() {
        val delayed = OffsetDateTime.now()
        val h = history(delayedUntil = delayed)

        assertEquals(delayed, controller.delayedUntil(h))
    }

    @Test
    fun `displayName returns enqueuer displayName when present and different from job name`() = runTest {
        val name = "transition-content"
        val enqueuer = mockk<JobConfigurationEnqueuer>()
        every { enqueuer.displayName } returns "Transition Content Job"
        provides<JobConfigurationEnqueuer>(name) { enqueuer }

        assertEquals("Transition Content Job", controller.displayName(history(jobName = name)))
    }

    @Test
    fun `displayName falls back to formatted name when enqueuer displayName is blank`() = runTest {
        val name = "process-content"
        val enqueuer = mockk<JobConfigurationEnqueuer>()
        every { enqueuer.displayName } returns ""
        provides<JobConfigurationEnqueuer>(name) { enqueuer }

        assertEquals("Process Content", controller.displayName(history(jobName = name)))
    }

    @Test
    fun `displayName falls back to formatted name when enqueuer displayName equals job name`() = runTest {
        val name = "process-content"
        val enqueuer = mockk<JobConfigurationEnqueuer>()
        every { enqueuer.displayName } returns name
        provides<JobConfigurationEnqueuer>(name) { enqueuer }

        assertEquals("Process Content", controller.displayName(history(jobName = name)))
    }

    @Test
    fun `displayName falls back to formatted name when no enqueuer provider is registered`() = runTest {
        val h = history(jobName = "transition-collection")

        assertEquals("Transition Collection", controller.displayName(h))
    }

    @Test
    fun `displayName formats single word job name`() = runTest {
        val h = history(jobName = "publish")

        assertEquals("Publish", controller.displayName(h))
    }
}

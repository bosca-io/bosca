package bosca.scheduler.listeners

import bosca.scheduler.model.JobHistory
import bosca.scheduler.model.JobHistorySource
import bosca.scheduler.model.ScheduleExecutionStatus
import bosca.scheduler.service.SchedulerService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobListener
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEvent
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEventChannel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ScheduledJobExecutionListenerTest {

    private val channel = mockk<JobEnqueueEventChannel>(relaxed = true)
    private val listener = ScheduledJobExecutionListener(channel)

    @Test
    fun `implements JobListener interface`() {
        assertIs<JobListener>(listener)
    }
}

class JobEnqueueEventForwarderTest {

    @Test
    fun `stop is safe to call without start`() {
        val channel = mockk<JobEnqueueEventChannel>(relaxed = true)
        val service = mockk<SchedulerService>(relaxed = true)
        val forwarder = JobEnqueueEventForwarder(channel, service)

        // Calling stop without start should not throw
        forwarder.stop()
    }

    @Test
    fun `double stop does not throw`() {
        val channel = mockk<JobEnqueueEventChannel>(relaxed = true)
        val service = mockk<SchedulerService>(relaxed = true)
        val forwarder = JobEnqueueEventForwarder(channel, service)

        forwarder.stop()
        forwarder.stop()
    }

    /**
     * Regression for the event-reorder duplicate: a fast job's COMPLETE event is processed
     * before its `status == null` enqueue event. The completion must create-and-complete the
     * single row, and the late enqueue must NOT insert a second, forever-pending duplicate.
     */
    @Test
    fun `complete before enqueue yields one completed row, no orphan`() = runBlocking {
        val rows = mutableListOf<JobHistory>()
        val forwarder = JobEnqueueEventForwarder(mockk(relaxed = true), inMemoryService(rows))
        val jobId = UUID.random()

        // Completion overtakes the enqueue event (the production reorder).
        forwarder.handle(event(jobId, JobStatus.COMPLETE))
        forwarder.handle(event(jobId, status = null))

        assertEquals(1, rows.size, "the late enqueue event must not insert a duplicate row")
        assertNotNull(rows.single().completedAt)
        assertEquals(ScheduleExecutionStatus.COMPLETED, rows.single().status)
    }

    /** Normal ordering still converges on a single completed row. */
    @Test
    fun `enqueue before complete yields one completed row`() = runBlocking {
        val rows = mutableListOf<JobHistory>()
        val forwarder = JobEnqueueEventForwarder(mockk(relaxed = true), inMemoryService(rows))
        val jobId = UUID.random()

        forwarder.handle(event(jobId, status = null))
        forwarder.handle(event(jobId, JobStatus.COMPLETE))

        assertEquals(1, rows.size)
        assertEquals(ScheduleExecutionStatus.COMPLETED, rows.single().status)
    }

    /** Duplicate enqueues (e.g. multiple push subscribers) collapse onto one pending row. */
    @Test
    fun `duplicate enqueue collapses onto one pending row`() = runBlocking {
        val rows = mutableListOf<JobHistory>()
        val forwarder = JobEnqueueEventForwarder(mockk(relaxed = true), inMemoryService(rows))
        val jobId = UUID.random()

        forwarder.handle(event(jobId, status = null))
        forwarder.handle(event(jobId, status = null))

        assertEquals(1, rows.size)
        assertEquals(ScheduleExecutionStatus.PENDING, rows.single().status)
    }

    private fun event(jobId: UUID, status: JobStatus?): JobEnqueueEvent =
        JobEnqueueEvent(
            jobId = jobId,
            status = status,
            queue = "scripting",
            displayName = "script-source-sync",
        )

    /**
     * A relaxed [SchedulerService] mock backed by an in-memory [rows] list that emulates the
     * `scheduler.job_history` table for the four methods [JobEnqueueEventForwarder.handle] touches,
     * with the same matching semantics as the SQL:
     *  - `refreshPendingHistory` only matches `pending`/`running`
     *  - `updateJobStatus` matches by `job_id` (skipping `cancelled`) and stamps `completed_at` on terminal states
     *  - `getHistoryByJobId` matches any row for the `job_id`
     */
    private fun inMemoryService(rows: MutableList<JobHistory>): SchedulerService {
        val terminal = setOf(
            ScheduleExecutionStatus.COMPLETED,
            ScheduleExecutionStatus.FAILED,
            ScheduleExecutionStatus.SKIPPED,
            ScheduleExecutionStatus.CANCELLED,
            ScheduleExecutionStatus.STALE,
        )
        val service = mockk<SchedulerService>(relaxed = true)

        coEvery { service.getHistoryByJobId(any()) } answers {
            rows.firstOrNull { it.jobId == firstArg<UUID>() }
        }

        coEvery {
            service.createHistory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            val row = JobHistory(
                id = UUID.random(),
                scheduledJobId = arg<UUID?>(0),
                jobId = arg<UUID>(1),
                name = arg<String>(2),
                scheduledFor = arg<OffsetDateTime>(3),
                triggeredAt = OffsetDateTime.now(),
                source = arg<JobHistorySource>(4),
                status = ScheduleExecutionStatus.PENDING,
                wasCatchUp = arg<Boolean>(5),
                delayedUntil = arg<OffsetDateTime?>(6),
                definition = arg(7),
                context = arg(8),
                parentJobId = arg<UUID?>(9),
            )
            rows.add(row)
            row
        }

        coEvery { service.refreshPendingHistory(any(), any(), any()) } answers {
            val jobId = firstArg<UUID>()
            val idx = rows.indexOfFirst {
                it.jobId == jobId &&
                    (it.status == ScheduleExecutionStatus.PENDING || it.status == ScheduleExecutionStatus.RUNNING)
            }
            if (idx < 0) {
                null
            } else {
                rows[idx] = rows[idx].copy(
                    status = ScheduleExecutionStatus.PENDING,
                    scheduledFor = secondArg(),
                    delayedUntil = thirdArg(),
                    completedAt = null,
                    errorMessage = null,
                )
                rows[idx]
            }
        }

        coEvery { service.updateJobStatus(any(), any(), any(), any()) } answers {
            val jobId = firstArg<UUID>()
            val status = secondArg<ScheduleExecutionStatus>()
            val completedAt = if (status in terminal) OffsetDateTime.now() else null
            var updated: JobHistory? = null
            rows.forEachIndexed { i, row ->
                if (row.jobId == jobId && row.status != ScheduleExecutionStatus.CANCELLED) {
                    rows[i] = row.copy(status = status, completedAt = completedAt, errorMessage = arg(2))
                    updated = rows[i]
                }
            }
            updated
        }

        return service
    }
}

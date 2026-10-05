@file:OptIn(bosca.core.annotations.Internal::class, bosca.di.annotation.InternalDI::class)

package bosca.scheduler.service

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.scheduler.model.JobHistory
import bosca.scheduler.model.JobHistorySource
import bosca.scheduler.model.ScheduleExecutionStatus
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.model.ScheduledJobExecutionContext
import bosca.scheduler.model.ScheduledJobInput
import bosca.scheduler.model.ScheduledJobPrincipalState
import bosca.scheduler.repository.JobHistoryRepository
import bosca.scheduler.repository.ScheduledJobRepository
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobExecutor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SchedulerServiceImplTest {

    private val historyRepository = mockk<JobHistoryRepository>(relaxed = true)
    private val jobRepository = mockk<ScheduledJobRepository>(relaxed = true)
    private val distributedLockFactory = mockk<DistributedLockFactory>(relaxed = true)
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val enqueuer = mockk<JobConfigurationEnqueuer>(relaxed = true)
    private val service = SchedulerServiceImpl(jobRepository, historyRepository, distributedLockFactory, securityService)

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<JobConfigurationEnqueuer>(name = JOB_NAME) { enqueuer }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    private class TestExecutor : JobExecutor {
        override suspend fun execute() = Unit
    }

    private fun fakeHistory(id: UUID): JobHistory =
        JobHistory(
            id = id,
            scheduledJobId = UUID.random(),
            jobId = UUID.NIL,
            name = "test",
            scheduledFor = OffsetDateTime.now(),
            triggeredAt = OffsetDateTime.now(),
            source = JobHistorySource.SCHEDULER,
            status = ScheduleExecutionStatus.SKIPPED,
            wasCatchUp = false,
        )

    private fun input(requiresPrincipal: Boolean? = null, enabled: Boolean? = true) = ScheduledJobInput(
        name = "test",
        jobName = JOB_NAME,
        cronExpression = "0 * * * *",
        enabled = enabled,
        requiresPrincipal = requiresPrincipal,
    )

    private fun scheduledJob(
        state: ScheduledJobPrincipalState = ScheduledJobPrincipalState.NOT_REQUIRED,
        principalId: UUID? = null,
        enabled: Boolean = state == ScheduledJobPrincipalState.NOT_REQUIRED || state == ScheduledJobPrincipalState.ACTIVE,
    ) = ScheduledJob(
        id = UUID.random(),
        name = "test",
        jobName = JOB_NAME,
        cronExpression = "0 * * * *",
        enabled = enabled,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
        createdBy = UUID.random(),
        executionPrincipalId = principalId,
        principalState = state,
        principalAssignedBy = principalId,
        principalConfirmedBy = principalId?.takeIf { state == ScheduledJobPrincipalState.ACTIVE },
    )

    @Test
    fun `getJobsByName delegates indexed lookup`() = runBlocking {
        val expected = listOf(scheduledJob())
        coEvery { jobRepository.getByJobName(JOB_NAME, 25, 50) } returns expected

        assertSame(expected, service.getJobsByName(JOB_NAME, 25, 50))
    }

    @Test
    fun `create principal required job parks until assignment`() = runBlocking {
        val captured = slot<ScheduledJob>()
        coEvery { jobRepository.add(capture(captured)) } answers { captured.captured }

        val created = service.createJob(input(requiresPrincipal = true), UUID.random())

        assertEquals(ScheduledJobPrincipalState.NEEDS_PRINCIPAL, created.principalState)
        assertFalse(created.enabled)
        assertNull(created.executionPrincipalId)
        assertNotNull(created.nextRunAt)
        Unit
    }

    @Test
    fun `create infrastructure job remains enabled without a principal`() = runBlocking {
        val captured = slot<ScheduledJob>()
        coEvery { jobRepository.add(capture(captured)) } answers { captured.captured }

        val created = service.createJob(input(requiresPrincipal = false), UUID.random())

        assertEquals(ScheduledJobPrincipalState.NOT_REQUIRED, created.principalState)
        assertTrue(created.enabled)
    }

    @Test
    fun `create applies scheduler defaults when optional input values are null`() = runBlocking {
        val captured = slot<ScheduledJob>()
        coEvery { jobRepository.add(capture(captured)) } answers { captured.captured }

        val created = service.createJob(
            input(requiresPrincipal = null, enabled = null).copy(
                allowConcurrent = null,
                catchUp = null,
                maxCatchUp = null,
            ),
            UUID.random(),
        )

        assertTrue(created.enabled)
        assertFalse(created.allowConcurrent)
        assertFalse(created.catchUp)
        assertEquals(1, created.maxCatchUp)
    }

    @Test
    fun `create rejects unknown job definitions and invalid cron`() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            service.createJob(input().copy(jobName = "missing"), UUID.random())
        }
        assertFailsWith<IllegalArgumentException> {
            service.createJob(input().copy(cronExpression = "invalid"), UUID.random())
        }
        Unit
    }

    @Test
    fun `update can add and remove principal requirement without retaining identity`() = runBlocking {
        val infrastructure = scheduledJob()
        val captured = slot<ScheduledJob>()
        coEvery { jobRepository.getById(infrastructure.id) } returns infrastructure
        coEvery { jobRepository.update(capture(captured)) } answers { captured.captured }

        val parked = service.updateJob(infrastructure.id, input(requiresPrincipal = true))
        assertEquals(ScheduledJobPrincipalState.NEEDS_PRINCIPAL, parked?.principalState)
        assertEquals(false, parked?.enabled)

        val principalId = UUID.random()
        val active = scheduledJob(ScheduledJobPrincipalState.ACTIVE, principalId)
        coEvery { jobRepository.getById(active.id) } returns active
        val unscoped = service.updateJob(active.id, input(requiresPrincipal = false))
        assertEquals(ScheduledJobPrincipalState.NOT_REQUIRED, unscoped?.principalState)
        assertNull(unscoped?.executionPrincipalId)
        assertNull(unscoped?.principalAssignedBy)
        assertNull(unscoped?.principalConfirmedBy)
    }

    @Test
    fun `update preserves confirmed principal state and optional values`() = runBlocking {
        val principalId = UUID.random()
        val active = scheduledJob(ScheduledJobPrincipalState.ACTIVE, principalId).copy(
            allowConcurrent = true,
            catchUp = true,
            maxCatchUp = 4,
        )
        val captured = slot<ScheduledJob>()
        coEvery { jobRepository.getById(active.id) } returns active
        coEvery { jobRepository.update(capture(captured)) } answers { captured.captured }

        val updated = service.updateJob(
            active.id,
            input(requiresPrincipal = null, enabled = null).copy(
                allowConcurrent = null,
                catchUp = null,
                maxCatchUp = null,
            ),
        )
        assertNotNull(updated)

        assertEquals(ScheduledJobPrincipalState.ACTIVE, updated.principalState)
        assertEquals(principalId, updated.executionPrincipalId)
        assertTrue(updated.enabled)
        assertTrue(updated.allowConcurrent)
        assertTrue(updated.catchUp)
        assertEquals(4, updated.maxCatchUp)
    }

    @Test
    fun `update handles not found unknown definition and invalid cron`() = runBlocking {
        val id = UUID.random()
        coEvery { jobRepository.getById(id) } returns null
        assertNull(service.updateJob(id, input()))

        val job = scheduledJob()
        coEvery { jobRepository.getById(job.id) } returns job
        assertFailsWith<IllegalArgumentException> { service.updateJob(job.id, input().copy(jobName = "missing")) }
        assertFailsWith<IllegalArgumentException> { service.updateJob(job.id, input().copy(cronExpression = "invalid")) }
        Unit
    }

    @Test
    fun `enable requires an eligible principal state`() = runBlocking {
        val missingId = UUID.random()
        coEvery { jobRepository.getById(missingId) } returns null
        assertNull(service.enableJob(missingId))

        val parked = scheduledJob(ScheduledJobPrincipalState.NEEDS_PRINCIPAL)
        coEvery { jobRepository.getById(parked.id) } returns parked
        assertFailsWith<IllegalStateException> { service.enableJob(parked.id) }

        val active = scheduledJob(ScheduledJobPrincipalState.ACTIVE, UUID.random())
        coEvery { jobRepository.getById(active.id) } returns active
        coEvery { jobRepository.enable(active.id) } returns active
        assertSame(active, service.enableJob(active.id))
        coVerify { jobRepository.updateRunTimes(active.id, any(), any()) }
    }

    @Test
    fun `assignment requires a live principal and records consent state`() = runBlocking {
        val id = UUID.random()
        coEvery { jobRepository.getById(id) } returns null
        assertNull(service.assignExecutionPrincipal(id, UUID.random(), UUID.random()))

        val infrastructure = scheduledJob()
        coEvery { jobRepository.getById(infrastructure.id) } returns infrastructure
        assertFailsWith<IllegalStateException> {
            service.assignExecutionPrincipal(infrastructure.id, UUID.random(), UUID.random())
        }

        val parked = scheduledJob(ScheduledJobPrincipalState.NEEDS_PRINCIPAL)
        val principalId = UUID.random()
        val actorId = UUID.random()
        coEvery { jobRepository.getById(parked.id) } returns parked
        coEvery { securityService.getPrincipalById(principalId) } returns null
        assertFailsWith<NoSuchElementException> {
            service.assignExecutionPrincipal(parked.id, principalId, actorId)
        }

        coEvery { securityService.getPrincipalById(principalId) } returns Principal(id = principalId)
        service.assignExecutionPrincipal(parked.id, principalId, actorId)
        coVerify {
            jobRepository.assignPrincipal(
                parked.id,
                principalId,
                actorId,
                null,
                ScheduledJobPrincipalState.PENDING_CONFIRMATION,
                false,
            )
        }

        service.assignExecutionPrincipal(parked.id, principalId, actorId, actorId)
        coVerify {
            jobRepository.assignPrincipal(
                parked.id,
                principalId,
                actorId,
                actorId,
                ScheduledJobPrincipalState.ACTIVE,
                true,
            )
        }
    }

    @Test
    fun `disabled principal cannot be assigned`() = runBlocking {
        val parked = scheduledJob(ScheduledJobPrincipalState.NEEDS_PRINCIPAL)
        val principalId = UUID.random()
        coEvery { jobRepository.getById(parked.id) } returns parked
        coEvery { securityService.getPrincipalById(principalId) } returns Principal(
            id = principalId,
            deletedAt = java.time.OffsetDateTime.now(),
        )

        assertFailsWith<IllegalStateException> {
            service.assignExecutionPrincipal(parked.id, principalId, UUID.random())
        }
        Unit
    }

    @Test
    fun `confirmation requires a pending live assignment`() = runBlocking {
        val missingId = UUID.random()
        coEvery { jobRepository.getById(missingId) } returns null
        assertNull(service.confirmExecutionPrincipal(missingId, UUID.random()))

        val parked = scheduledJob(ScheduledJobPrincipalState.NEEDS_PRINCIPAL)
        coEvery { jobRepository.getById(parked.id) } returns parked
        assertFailsWith<IllegalStateException> { service.confirmExecutionPrincipal(parked.id, UUID.random()) }

        val pendingWithoutId = parked.copy(principalState = ScheduledJobPrincipalState.PENDING_CONFIRMATION)
        coEvery { jobRepository.getById(pendingWithoutId.id) } returns pendingWithoutId
        assertFailsWith<IllegalStateException> { service.confirmExecutionPrincipal(pendingWithoutId.id, UUID.random()) }

        val principalId = UUID.random()
        val pending = pendingWithoutId.copy(executionPrincipalId = principalId)
        coEvery { jobRepository.getById(pending.id) } returns pending
        coEvery { securityService.getPrincipalById(principalId) } returns Principal(id = principalId)
        service.confirmExecutionPrincipal(pending.id, principalId)
        coVerify { jobRepository.confirmPrincipal(pending.id, principalId) }
    }

    @Test
    fun `clear and park reject infrastructure jobs and delegate principal jobs`() = runBlocking {
        val missingId = UUID.random()
        coEvery { jobRepository.getById(missingId) } returns null
        assertNull(service.clearExecutionPrincipal(missingId))
        assertNull(service.parkNeedsPrincipal(missingId))

        val infrastructure = scheduledJob()
        coEvery { jobRepository.getById(infrastructure.id) } returns infrastructure
        assertFailsWith<IllegalStateException> { service.clearExecutionPrincipal(infrastructure.id) }
        assertFailsWith<IllegalStateException> { service.parkNeedsPrincipal(infrastructure.id) }

        val active = scheduledJob(ScheduledJobPrincipalState.ACTIVE, UUID.random())
        coEvery { jobRepository.getById(active.id) } returns active
        service.clearExecutionPrincipal(active.id)
        service.parkNeedsPrincipal(active.id)
        coVerify { jobRepository.clearPrincipal(active.id) }
        coVerify { jobRepository.parkNeedsPrincipal(active.id) }
    }

    @Test
    fun `manual trigger parks an unavailable active principal`() = runBlocking {
        val principalId = UUID.random()
        val active = scheduledJob(ScheduledJobPrincipalState.ACTIVE, principalId)
        coEvery { jobRepository.getById(active.id) } returns active
        coEvery { securityService.getPrincipalById(principalId) } returns null

        assertNull(service.triggerJob(active.id))
        coVerify { jobRepository.parkNeedsPrincipal(active.id) }
        coVerify(exactly = 0) { distributedLockFactory.create(any()) }
    }

    @Test
    fun `manual trigger ignores missing unassigned and unconfirmed jobs`() = runBlocking {
        val missingId = UUID.random()
        coEvery { jobRepository.getById(missingId) } returns null
        assertNull(service.triggerJob(missingId))

        val needsPrincipal = scheduledJob(ScheduledJobPrincipalState.NEEDS_PRINCIPAL)
        coEvery { jobRepository.getById(needsPrincipal.id) } returns needsPrincipal
        assertNull(service.triggerJob(needsPrincipal.id))

        val pending = scheduledJob(ScheduledJobPrincipalState.PENDING_CONFIRMATION, UUID.random())
        coEvery { jobRepository.getById(pending.id) } returns pending
        assertNull(service.triggerJob(pending.id))
        coVerify(exactly = 0) { distributedLockFactory.create(any()) }
    }

    @Test
    fun `manual trigger carries scheduler principal context into queue history`() = runBlocking {
        val principalId = UUID.random()
        val active = scheduledJob(ScheduledJobPrincipalState.ACTIVE, principalId)
        val lock = mockk<DistributedLock>(relaxed = true)
        val enqueued = InternalJobConstructor(JsonObject(emptyMap()), TestExecutor::class)
        val history = fakeHistory(UUID.random())
        coEvery { jobRepository.getById(active.id) } returns active
        coEvery { securityService.getPrincipalById(principalId) } returns Principal(id = principalId)
        coEvery { distributedLockFactory.create("scheduler:${active.id}") } returns lock
        coEvery { lock.tryAcquire(60_000) } returns true
        coEvery { historyRepository.hasActiveExecutions(active.id) } returns false
        coEvery { enqueuer.enqueue(any(), any()) } coAnswers {
            val initializer = secondArg<suspend Job.() -> Unit>()
            enqueued.initializer()
            enqueued
        }
        coEvery { historyRepository.add(any()) } returns history

        assertSame(history, service.triggerJob(active.id))

        val context = Json.decodeFromJsonElement(ScheduledJobExecutionContext.serializer(), enqueued.getContext())
        assertEquals(active.id, context.scheduledJobId)
        assertEquals(principalId, context.executionPrincipalId)
        coVerify { lock.release() }
        coVerify { historyRepository.add(match { it.context == enqueued.getContext() }) }
    }

    @Test
    fun `manual trigger rejects lock contention and nonconcurrent overlap`() = runBlocking {
        val job = scheduledJob()
        val lock = mockk<DistributedLock>(relaxed = true)
        coEvery { jobRepository.getById(job.id) } returns job
        coEvery { distributedLockFactory.create("scheduler:${job.id}") } returns lock
        coEvery { lock.tryAcquire(60_000) } returns false
        assertFailsWith<IllegalStateException> { service.triggerJob(job.id) }

        coEvery { lock.tryAcquire(60_000) } returns true
        coEvery { historyRepository.hasActiveExecutions(job.id) } returns true
        assertFailsWith<IllegalStateException> { service.triggerJob(job.id) }
        coVerify { lock.release() }
    }

    /**
     * Regression for the SKIPPED-row bug: `SchedulerRunner.processJob` creates a SKIPPED
     * history row with `job_id = UUID.NIL` and needs to mark it via the primary-key path,
     * not the by-`job_id` path. This test pins down that `updateJobStatusByHistoryId`
     * routes to [JobHistoryRepository.updateStatusById] (PK match) — never
     * [JobHistoryRepository.updateStatus] (which matches on `job_id` and would silently
     * miss a `UUID.NIL` row).
     */
    @Test
    fun `purgeHistoryBefore deletes finished rows before the cutoff and returns the count`() = runBlocking {
        val cutoff = OffsetDateTime.now().minusDays(30)
        coEvery { historyRepository.deleteCompletedBefore(cutoff) } returns 7

        val deleted = service.purgeHistoryBefore(cutoff)

        assertEquals(7L, deleted)
        coVerify(exactly = 1) { historyRepository.deleteCompletedBefore(cutoff) }
    }

    @Test
    fun `updateJobStatusByHistoryId routes to updateStatusById, not updateStatus`() = runBlocking {
        val historyId = UUID.random()
        val expected = fakeHistory(historyId)
        coEvery { historyRepository.updateStatusById(historyId, any(), any(), any()) } returns expected

        val result = service.updateJobStatusByHistoryId(
            historyId,
            ScheduleExecutionStatus.SKIPPED,
            "Skipped due to active concurrent execution"
        )

        assertSame(expected, result)
        coVerify(exactly = 1) {
            historyRepository.updateStatusById(
                historyId,
                ScheduleExecutionStatus.SKIPPED,
                any(),
                "Skipped due to active concurrent execution"
            )
        }
        coVerify(exactly = 0) { historyRepository.updateStatus(any(), any(), any(), any(), any()) }
    }

    /**
     * Terminal statuses must stamp `completed_at` so the row isn't picked up by
     * `cleanupStaleExecutions` (which only considers rows still in `pending` / `running`).
     */
    @Test
    fun `updateJobStatusByHistoryId stamps completed_at for terminal statuses`() = runBlocking {
        val completedAtSlot = slot<OffsetDateTime?>()
        coEvery {
            historyRepository.updateStatusById(any(), any(), captureNullable(completedAtSlot), any())
        } returns null

        val terminal = listOf(
            ScheduleExecutionStatus.COMPLETED,
            ScheduleExecutionStatus.FAILED,
            ScheduleExecutionStatus.SKIPPED,
            ScheduleExecutionStatus.CANCELLED,
            ScheduleExecutionStatus.STALE,
        )
        for (status in terminal) {
            service.updateJobStatusByHistoryId(UUID.random(), status, null)
            assertNotNull(completedAtSlot.captured, "completed_at must be set for $status")
        }
    }

    /**
     * In-flight statuses must leave `completed_at` null so a later terminal transition can
     * set it cleanly.
     */
    @Test
    fun `updateJobStatusByHistoryId leaves completed_at null for in-flight statuses`() = runBlocking {
        val completedAtSlot = slot<OffsetDateTime?>()
        coEvery {
            historyRepository.updateStatusById(any(), any(), captureNullable(completedAtSlot), any())
        } returns null

        for (status in listOf(ScheduleExecutionStatus.PENDING, ScheduleExecutionStatus.RUNNING)) {
            service.updateJobStatusByHistoryId(UUID.random(), status, null)
            assertNull(completedAtSlot.captured, "completed_at must be null for $status")
        }
    }

    /**
     * The original `updateJobStatus` (by-`job_id`) path must keep its existing contract —
     * the SKIPPED fix added a sibling method, it did not change behaviour here.
     */
    @Test
    fun `updateJobStatus still routes to updateStatus by job_id`() = runBlocking {
        val jobId = UUID.random()
        val expected = fakeHistory(UUID.random())
        coEvery { historyRepository.updateStatus(jobId, any(), any(), any(), any()) } returns expected

        val result = service.updateJobStatus(jobId, ScheduleExecutionStatus.RUNNING, null, null)

        assertSame(expected, result)
        coVerify(exactly = 1) {
            historyRepository.updateStatus(jobId, ScheduleExecutionStatus.RUNNING, null, null, null)
        }
        coVerify(exactly = 0) { historyRepository.updateStatusById(any(), any(), any(), any()) }
    }

    companion object {
        private const val JOB_NAME = "test-job"
    }
}

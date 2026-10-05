@file:OptIn(bosca.core.annotations.Internal::class, bosca.di.annotation.InternalDI::class)

package bosca.scheduler.runner

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.lock.DistributedLockFactory
import bosca.scheduler.model.JobHistory
import bosca.scheduler.model.JobHistorySource
import bosca.scheduler.model.ScheduleExecutionStatus
import bosca.scheduler.service.SchedulerService
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.model.ScheduledJobExecutionContext
import bosca.scheduler.model.ScheduledJobPrincipalState
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchedulerRunnerTest {
    private class TestExecutor : JobExecutor {
        override suspend fun execute() = Unit
    }

    @Test
    fun `test runner starts and stops`() = runBlocking {
        val cacheManager = mockk<CacheManager>(relaxed = true)
        provides<CacheManager> { cacheManager }
        val requestCacheSerializer = mockk<RequestCacheSerializer>(relaxed = true)
        provides<RequestCacheSerializer> { requestCacheSerializer }

        val schedulerService = mockk<SchedulerService>(relaxed = true)
        val distributedLockFactory = mockk<DistributedLockFactory>(relaxed = true)
        val securityService = mockk<SecurityService>(relaxed = true)

        val runner = SchedulerRunner(schedulerService, distributedLockFactory, securityService)
        
        runner.start()
        assertTrue(runner.isRunning(), "Runner should be running after start")
        runner.start()
        
        runner.stop()
        
        // Give it a moment? No, cancel should reflect in isRunning immediately as it checks runnerJob?.isActive
        assertFalse(runner.isRunning(), "Runner should not be running after stop")
    }

    @Test
    fun `cron enqueue attaches principal context only to principal-aware jobs`() = runBlocking {
        ProviderRegistry.clear()
        val schedulerService = mockk<SchedulerService>(relaxed = true)
        val securityService = mockk<SecurityService>(relaxed = true)
        val enqueuer = mockk<JobConfigurationEnqueuer>()
        val queue = mockk<JobQueue>()
        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val queuedId = UUID.random()
        val preparedJobs = mutableListOf<Job>()
        provides<JobConfigurationEnqueuer>(name = "test") { enqueuer }
        coEvery { enqueuer.prepare(any(), any()) } coAnswers {
            val initializer = secondArg<suspend Job.() -> Unit>()
            val prepared = InternalJobConstructor(JsonObject(emptyMap()), TestExecutor::class)
            prepared.initializer()
            preparedJobs += prepared
            prepared
        }
        coEvery { enqueuer.queue() } returns queue
        coEvery { queue.enqueue(any()) } returns queuedId
        coEvery {
            schedulerService.createHistory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns history()
        val principalId = UUID.random()
        coEvery { securityService.getPrincipalById(principalId) } returns Principal(id = principalId)
        val runner = SchedulerRunner(schedulerService, mockk(relaxed = true), securityService)
        val scheduledFor = OffsetDateTime.now()

        withContext(connectionManager.asCoroutineContext()) {
            assertEquals(
                queuedId,
                runner.enqueueJob(job(ScheduledJobPrincipalState.ACTIVE, principalId), scheduledFor, false),
            )
            assertEquals(
                queuedId,
                runner.enqueueJob(job(ScheduledJobPrincipalState.NOT_REQUIRED), scheduledFor, true),
            )
        }

        val context = Json.decodeFromJsonElement(
            ScheduledJobExecutionContext.serializer(),
            preparedJobs[0].getContext(),
        )
        assertEquals(principalId, context.executionPrincipalId)
        assertEquals(JsonNull, preparedJobs[1].getContext())
        coVerify(exactly = 2) { schedulerService.setJobId(any(), queuedId) }
        ProviderRegistry.clear()
    }

    @Test
    fun `principal-free and confirmed live jobs are eligible`() = runBlocking {
        val schedulerService = mockk<SchedulerService>(relaxed = true)
        val securityService = mockk<SecurityService>(relaxed = true)
        val runner = SchedulerRunner(schedulerService, mockk(relaxed = true), securityService)
        val principalId = UUID.random()
        coEvery { securityService.getPrincipalById(principalId) } returns Principal(id = principalId)

        assertTrue(runner.hasEligiblePrincipal(job(ScheduledJobPrincipalState.NOT_REQUIRED)))
        assertTrue(runner.hasEligiblePrincipal(job(ScheduledJobPrincipalState.ACTIVE, principalId)))
    }

    @Test
    fun `unassigned and unconfirmed jobs are ineligible without being re-parked`() = runBlocking {
        val schedulerService = mockk<SchedulerService>(relaxed = true)
        val runner = SchedulerRunner(
            schedulerService,
            mockk(relaxed = true),
            mockk(relaxed = true),
        )

        assertFalse(runner.hasEligiblePrincipal(job(ScheduledJobPrincipalState.NEEDS_PRINCIPAL)))
        assertFalse(runner.hasEligiblePrincipal(job(ScheduledJobPrincipalState.PENDING_CONFIRMATION, UUID.random())))
        coVerify(exactly = 0) { schedulerService.parkNeedsPrincipal(any()) }
    }

    @Test
    fun `missing or disabled active principal parks the scheduled job`() = runBlocking {
        val schedulerService = mockk<SchedulerService>(relaxed = true)
        val securityService = mockk<SecurityService>(relaxed = true)
        val runner = SchedulerRunner(schedulerService, mockk(relaxed = true), securityService)
        val principalId = UUID.random()
        val job = job(ScheduledJobPrincipalState.ACTIVE, principalId)

        coEvery { securityService.getPrincipalById(principalId) } returns null
        assertFalse(runner.hasEligiblePrincipal(job))

        coEvery { securityService.getPrincipalById(principalId) } returns Principal(
            id = principalId,
            deletedAt = java.time.OffsetDateTime.now(),
        )
        assertFalse(runner.hasEligiblePrincipal(job))

        coVerify(exactly = 2) { schedulerService.parkNeedsPrincipal(job.id) }
    }

    private fun job(state: ScheduledJobPrincipalState, principalId: UUID? = null) = ScheduledJob(
        id = UUID.random(),
        name = "job",
        jobName = "test",
        cronExpression = "0 * * * *",
        enabled = state == ScheduledJobPrincipalState.NOT_REQUIRED || state == ScheduledJobPrincipalState.ACTIVE,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
        createdBy = UUID.random(),
        executionPrincipalId = principalId,
        principalState = state,
    )

    private fun history() = JobHistory(
        id = UUID.random(),
        scheduledJobId = UUID.random(),
        jobId = UUID.NIL,
        name = "job",
        scheduledFor = OffsetDateTime.now(),
        triggeredAt = OffsetDateTime.now(),
        source = JobHistorySource.SCHEDULER,
        status = ScheduleExecutionStatus.PENDING,
        wasCatchUp = false,
    )
}

@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.scheduler.jobs

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.withConnectionManager
import bosca.di.ProviderRegistry
import bosca.di.SchedulerProviderRegistrar
import bosca.di.provide
import bosca.di.provideProvider
import bosca.di.provides
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.scheduler.configuration.SchedulerMigration
import bosca.scheduler.listeners.JobEnqueueEventForwarder
import bosca.scheduler.listeners.ScheduledJobExecutionListener
import bosca.scheduler.model.ScheduleExecutionStatus
import bosca.scheduler.model.ScheduledJobInput
import bosca.scheduler.service.SchedulerService
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.sharedqueue.jobs.configuration.JobQueueNames
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEvent
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEventChannel
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Exercises the scheduled cleanup through generated job wiring and the real PostgreSQL repository. */
class PurgeJobHistoryEndToEndTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val queuedJob = slot<Job>()
    private val queueJobId = UUID.random()
    private val statusChannel = RecordingJobEnqueueEventChannel()
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var queue: JobQueue

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        postgres = SharedPostgreSQLContainer().withDatabaseName("scheduler-purge-history")
        postgres.start()
        pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 2,
                ),
                key = "scheduler-purge-history-test",
            ),
        )
        runBlocking { FlywayMigration(pool).migrate(listOf(SchedulerMigration())) }

        queue = mockk(relaxed = true) {
            every { name } returns "scheduler-purge-history-test"
            coEvery { enqueue(capture(queuedJob)) } answers {
                firstArg<Job>().setPersistentId(queueJobId)
                queueJobId
            }
        }
        val lock = mockk<DistributedLock>(relaxed = true) {
            coEvery { tryAcquire(any()) } returns true
            coEvery { release() } returns true
        }
        val lockFactory = mockk<DistributedLockFactory> {
            coEvery { create(any()) } returns lock
        }

        provides<Json>(singleton = true) { json }
        provides<ConnectionPool>(singleton = true) { pool }
        provides<JobQueue>(name = JobQueueNames.commonJobQueue, singleton = true) { queue }
        provides<JobEnqueueEventChannel>(singleton = true) { statusChannel }
        provides<DistributedLockFactory>(singleton = true) { lockFactory }
        provides<SecurityService>(singleton = true) { mockk(relaxed = true) }
        SchedulerProviderRegistrar().register()
    }

    @AfterTest
    fun teardown() = runBlocking {
        ProviderRegistry.clear()
        if (this@PurgeJobHistoryEndToEndTest::pool.isInitialized) pool.close()
        if (this@PurgeJobHistoryEndToEndTest::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `scheduled purge deletes only expired completed history and completes its own run`() = runBlocking {
        withConnectionManager {
            val expiredCompleted = UUID.random()
            val expiredFailed = UUID.random()
            val recentCompleted = UUID.random()
            val oldPending = UUID.random()
            val oldRunning = UUID.random()
            insertHistory(expiredCompleted, "completed", "now() - interval '31 days'")
            insertHistory(expiredFailed, "failed", "now() - interval '45 days'")
            insertHistory(recentCompleted, "completed", "now() - interval '29 days'")
            insertHistory(oldPending, "pending", null)
            insertHistory(oldRunning, "running", null)

            val enqueuerProvider = provideProvider<JobConfigurationEnqueuer>(PURGE_JOB_NAME)
            assertTrue(
                enqueuerProvider.exists,
                "Registered job enqueuers: ${ProviderRegistry.findAllWithNames(JobConfigurationEnqueuer::class).keys}",
            )
            assertEquals(JobQueueNames.commonJobQueue, enqueuerProvider.get().queueName)
            assertSame(queue, enqueuerProvider.get().queue())

            val schedulerService = provide<SchedulerService>()
            val scheduledJob = schedulerService.createJob(
                ScheduledJobInput(
                    name = "Purge Job History",
                    jobName = PURGE_JOB_NAME,
                    jobParameters = json.encodeToJsonElement(PurgeJobHistoryJob(retentionDays = 30)),
                    cronExpression = "0 0 * * *",
                    enabled = true,
                    allowConcurrent = false,
                    catchUp = false,
                    maxCatchUp = 1,
                ),
                createdBy = UUID.NIL,
            )
            val cleanupHistory = assertNotNull(schedulerService.triggerJob(scheduledJob.id))
            val job = queuedJob.captured

            coVerify(exactly = 1) { queue.enqueue(job) }
            assertEquals(queueJobId, job.getId())
            assertEquals(queueJobId, cleanupHistory.jobId)
            assertEquals(ScheduleExecutionStatus.PENDING, cleanupHistory.status)
            assertEquals(
                PurgeJobHistoryJob(retentionDays = 30),
                json.decodeFromJsonElement<PurgeJobHistoryJob>(job.getDefinition()),
            )

            val listener = provide<ScheduledJobExecutionListener>()
            val forwarder = provide<JobEnqueueEventForwarder>()
            listener.onStatusChanged(job, JobStatus.RUNNING)
            forwarder.handle(statusChannel.takeLast())

            val executor = job.newExecutor()
            assertIs<PurgeJobHistoryExecutor>(executor)
            withContext(queue.asCoroutineContext(job)) { executor.execute() }

            assertEquals(
                setOf(
                    recentCompleted.toString(),
                    oldPending.toString(),
                    oldRunning.toString(),
                    queueJobId.toString(),
                ),
                remainingJobIds(),
            )

            listener.onStatusChanged(job, JobStatus.COMPLETE)
            forwarder.handle(statusChannel.takeLast())
            val completedCleanup = assertNotNull(schedulerService.getHistoryByJobId(queueJobId))
            assertEquals(ScheduleExecutionStatus.COMPLETED, completedCleanup.status)
            assertNotNull(completedCleanup.completedAt)
            Unit
        }
    }

    private suspend fun insertHistory(jobId: UUID, status: String, completedAt: String?) {
        val completedAtExpression = completedAt ?: "null"
        runSql(
            """
            insert into scheduler.job_history (
                job_id, name, scheduled_for, triggered_at, source, status, completed_at, was_catch_up
            ) values (
                '$jobId', 'fixture', now() - interval '60 days', now() - interval '60 days',
                'event', '$status', $completedAtExpression, false
            )
            """.trimIndent(),
        )
    }

    private suspend fun remainingJobIds(): Set<String> =
        connection().useStatement("select job_id from scheduler.job_history") { statement ->
            statement.executeQuery().use { results ->
                buildSet {
                    while (results.next()) add(results.getString(1))
                }
            }
        }

    private suspend fun runSql(sql: String) = connection().useStatement(sql) { it.execute() }

    private class RecordingJobEnqueueEventChannel : JobEnqueueEventChannel {
        private val emitted = mutableListOf<JobEnqueueEvent>()

        override suspend fun emit(event: JobEnqueueEvent) {
            emitted += event
        }

        override fun events(): Flow<JobEnqueueEvent> = emptyFlow()

        fun takeLast(): JobEnqueueEvent = emitted.removeLast()
    }

    private companion object {
        const val PURGE_JOB_NAME = "purge-job-history"
    }
}

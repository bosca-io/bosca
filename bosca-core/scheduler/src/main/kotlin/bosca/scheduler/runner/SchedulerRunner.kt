package bosca.scheduler.runner

import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import bosca.cache.withRequestCache
import bosca.db.connection
import bosca.db.transaction
import bosca.db.withConnectionManager
import bosca.di.MissingProviderException
import bosca.di.provide
import bosca.lock.DistributedLockFactory
import bosca.scheduler.cron.CronExpression
import bosca.scheduler.model.JobHistorySource
import bosca.scheduler.model.ScheduleExecutionStatus
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.model.ScheduledJobExecutionContext
import bosca.scheduler.model.ScheduledJobPrincipalState
import bosca.scheduler.service.SchedulerService
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobCallback
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.scheduler.listeners.ScheduledJobExecutionListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * Background coroutine that evaluates schedules and enqueues jobs when due.
 */
class SchedulerRunner(
    private val schedulerService: SchedulerService,
    private val distributedLockFactory: DistributedLockFactory,
    private val securityService: SecurityService,
) {
    private val logger = org.slf4j.LoggerFactory.getLogger("SchedulerRunner")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("SchedulerRunner"))
    private var runnerJob: Job? = null
    private var catchUpJob: Job? = null

    private val evaluationInterval = 10.seconds
    private val lockTtl = 60_000L // 60 seconds

    /**
     * Start the scheduler runner.
     */
    fun start() {
        if (runnerJob?.isActive == true) {
            logger.warn("Scheduler runner is already running")
            return
        }

        catchUpJob = scope.launch {
            try {
                handleCatchUp()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Error handling catch-up", e)
            }
        }

        runnerJob = scope.launch {
            logger.info("Scheduler runner started")

            // Main evaluation loop
            while (isActive) {
                try {
                    evaluateSchedules()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Error in scheduler evaluation loop", e)
                }

                delay(evaluationInterval)
            }
        }

        runnerJob?.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) {
                logger.error("Scheduler runner stopped unexpectedly", cause)
                // Restart the runner
                start()
            } else {
                logger.info("Scheduler runner stopped")
            }
        }
    }

    /**
     * Stop the scheduler runner.
     */
    fun stop() {
        catchUpJob?.cancel()
        catchUpJob = null
        runnerJob?.cancel()
        runnerJob = null
        logger.info("Scheduler runner stop requested")
    }

    /**
     * Check if the runner is active.
     */
    fun isRunning(): Boolean = runnerJob?.isActive == true

    private suspend fun evaluateSchedules() = withRequestCache {
        withConnectionManager {
            try {
                val staleCount = schedulerService.cleanupStaleExecutions()
                if (staleCount > 0) {
                    logger.warn("Cleaned up {} stale job history records", staleCount)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Error cleaning up stale executions", e)
            }

            val dueJobs = schedulerService.getDueJobs()
            logger.debug("Found ${dueJobs.size} jobs due for execution")

            for (job in dueJobs) {
                try {
                    processJob(job)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Error processing scheduled job ${job.id}: ${job.name}", e)
                }
            }
        }
    }

    private suspend fun processJob(job: ScheduledJob) = transaction {
        val lock = distributedLockFactory.create("scheduler:${job.id}")

        // Try to acquire lock
        if (!lock.tryAcquire(lockTtl)) {
            logger.debug("Could not acquire lock for job {}, another instance is processing it", job.id)
            return@transaction
        }

        try {
            // Check concurrent execution policy
            if (!job.allowConcurrent && schedulerService.hasActiveExecutions(job.id)) {
                logger.warn("Skipping job ${job.id}: ${job.name} - has active executions and does not allow concurrent execution")

                schedulerService.createHistory(
                    scheduledJobId = job.id,
                    jobId = UUID.NIL,
                    name = job.name,
                    scheduledFor = job.nextRunAt ?: OffsetDateTime.now(),
                    source = JobHistorySource.SCHEDULER,
                    wasCatchUp = false
                ).let { entry ->
                    // Match the row by its primary key; the queue `job_id` is `UUID.NIL`
                    // here because no job was actually enqueued, so the by-`job_id`
                    // update path would not match this row.
                    schedulerService.updateJobStatusByHistoryId(
                        entry.id,
                        ScheduleExecutionStatus.SKIPPED,
                        "Skipped due to active concurrent execution"
                    )
                }

                // Update next run time
                updateNextRunTime(job)
                return@transaction
            }

            val scheduledFor = job.nextRunAt ?: OffsetDateTime.now()

            // Enqueue the job with retries
            val enqueuedJobId = enqueueJob(job, scheduledFor, false)
            if (enqueuedJobId == null) {
                logger.error("Failed to enqueue job ${job.id}: ${job.name}")
                updateNextRunTime(job)
                return@transaction
            }

            logger.info("Triggered scheduled job ${job.id}: ${job.name}, execution: $enqueuedJobId")

            // Update run times
            updateNextRunTime(job)
        } finally {
            // Released even when this coroutine was cancelled, instead of leaving the lock to its TTL.
            withContext(NonCancellable) { lock.release() }
        }
    }

    internal suspend fun enqueueJob(job: ScheduledJob, scheduledFor: OffsetDateTime, wasCatchUp: Boolean): UUID? = transaction {
        if (!hasEligiblePrincipal(job)) return@transaction null
        val enqueuer: JobConfigurationEnqueuer = try {
            provide(name = job.jobName)
        } catch (_: MissingProviderException) {
            logger.error("Failed to get enqueuer for job ${job.jobName}, Provider not found. Make sure the job is registered with DI.")
            return@transaction null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to get enqueuer for job ${job.jobName}", e)
            return@transaction null
        }
        val preparedJob = enqueuer.prepare(job.jobParameters) {
            addCallback(JobCallback(ScheduledJobExecutionListener::class))
            disableEmitEvent = true
            if (job.principalState != ScheduledJobPrincipalState.NOT_REQUIRED) {
                setContext(
                    Json.encodeToJsonElement(
                        ScheduledJobExecutionContext.serializer(),
                        ScheduledJobExecutionContext(job.id, job.executionPrincipalId),
                    )
                )
            }
        }
        val history = schedulerService.createHistory(
            scheduledJobId = job.id,
            jobId = preparedJob.getId(),
            name = job.name,
            scheduledFor = scheduledFor,
            source = JobHistorySource.SCHEDULER,
            wasCatchUp = wasCatchUp
        )
        connection().commitTransaction()
        val id = enqueuer.queue().enqueue(preparedJob)
        schedulerService.setJobId(history.id, id)
        return@transaction id
    }

    private suspend fun updateNextRunTime(job: ScheduledJob) {
        val now = OffsetDateTime.now()
        val nextRunAt = calculateNextRunTime(job, now)
        schedulerService.updateJobRunTimes(job.id, now, nextRunAt)
    }

    private fun calculateNextRunTime(job: ScheduledJob, after: OffsetDateTime): OffsetDateTime? {
        try {
            val cron = CronExpression.parse(job.cronExpression)
            return cron.nextExecution(after)
        } catch (e: Exception) {
            logger.error("Invalid cron expression for job ${job.id}: ${job.cronExpression}", e)
            return null
        }
    }

    private suspend fun handleCatchUp() = withRequestCache {
        withConnectionManager {
            logger.info("Checking for missed runs (catch-up)")
            var offset = 0L
            while (true) {
                val enabledJobs = schedulerService.getJobs(enabled = true, limit = 100, offset = offset)
                if (enabledJobs.isEmpty()) break
                offset += 100
                for (job in enabledJobs) {
                    if (!job.catchUp) continue
                    try {
                        handleCatchUpForJob(job)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Error handling catch-up for job ${job.id}: ${job.name}", e)
                    }
                }
            }
        }
    }

    private suspend fun handleCatchUpForJob(job: ScheduledJob) {
        if (job.lastRunAt == null) return // Never ran before

        val cron = try {
            CronExpression.parse(job.cronExpression)
        } catch (e: Exception) {
            logger.warn("Invalid cron expression for job ${job.id}, skipping catch-up", e)
            return
        }

        val now = OffsetDateTime.now()

        // Find missed runs since last run
        val missedRuns = mutableListOf<OffsetDateTime>()
        var nextRun = cron.nextExecution(job.lastRunAt ?: OffsetDateTime.now())

        while (nextRun != null && nextRun < now && missedRuns.size < job.maxCatchUp) {
            missedRuns.add(nextRun)
            nextRun = cron.nextExecution(nextRun)
        }

        if (missedRuns.isEmpty()) {
            logger.debug("No missed runs for job {}: {}", job.id, job.name)
            return
        }

        logger.info("Found ${missedRuns.size} missed runs for job ${job.id}: ${job.name}, catching up...")

        for (missedRun in missedRuns) {
            try {
                val enqueuedJobId = enqueueJob(job, missedRun, true)
                if (enqueuedJobId != null) {
                    logger.info("Catch-up execution created for job ${job.id} scheduled for $missedRun")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to create catch-up execution for job ${job.id}", e)
            }
        }

        // Update next run time
        val nextRunAt = cron.nextExecution(now)
        schedulerService.updateJobRunTimes(job.id, now, nextRunAt)
    }

    internal suspend fun hasEligiblePrincipal(job: ScheduledJob): Boolean = when (job.principalState) {
        ScheduledJobPrincipalState.NOT_REQUIRED -> true
        ScheduledJobPrincipalState.ACTIVE -> {
            val principal = job.executionPrincipalId?.let { securityService.getPrincipalById(it) }
            if (principal != null && principal.deletedAt == null) {
                true
            } else {
                schedulerService.parkNeedsPrincipal(job.id)
                logger.warn("Parked scheduled job {} because its execution principal is unavailable", job.id)
                false
            }
        }
        ScheduledJobPrincipalState.NEEDS_PRINCIPAL,
        ScheduledJobPrincipalState.PENDING_CONFIRMATION -> false
    }
}

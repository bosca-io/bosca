package bosca.scheduler.service

import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provideProvider
import bosca.lock.DistributedLockFactory
import bosca.scheduler.cron.CronExpression
import bosca.scheduler.listeners.ScheduledJobExecutionListener
import bosca.scheduler.model.*
import bosca.scheduler.repository.JobHistoryRepository
import bosca.scheduler.repository.ScheduledJobRepository
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.sharedqueue.jobs.JobCallback
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json

@ServiceImplementation
class SchedulerServiceImpl(
    private val jobRepository: ScheduledJobRepository,
    private val historyRepository: JobHistoryRepository,
    private val distributedLockFactory: DistributedLockFactory,
    private val securityService: SecurityService,
) : SchedulerService {

    @OptIn(InternalDI::class)
    override suspend fun getAvailableJobDefinitions(): List<JobDefinitionInfo> {
        val providers = ProviderRegistry.findAllWithNames(JobConfigurationEnqueuer::class)
        return providers.map { (name, provider) ->
            val enqueuer = provider.get()
            JobDefinitionInfo(
                id = enqueuer::class.simpleName ?: name,
                name = name,
                displayName = enqueuer.displayName,
                queueName = enqueuer.queueName,
                parameterSchema = null
            )
        }
    }

    override suspend fun getJobs(enabled: Boolean?, limit: Int, offset: Long): List<ScheduledJob> {
        return if (enabled != null) {
            jobRepository.getAllByEnabled(enabled, limit, offset)
        } else {
            jobRepository.getAll(limit, offset)
        }
    }

    override suspend fun getJob(id: UUID): ScheduledJob? {
        return jobRepository.getById(id)
    }

    override suspend fun getJobsByName(jobName: String, limit: Int, offset: Long): List<ScheduledJob> =
        jobRepository.getByJobName(jobName, limit, offset)

    override suspend fun getHistoryByJobId(jobId: UUID): JobHistory? {
        return historyRepository.getByJobId(jobId)
    }

    override suspend fun createJob(input: ScheduledJobInput, createdBy: UUID): ScheduledJob {
        val enqueuer = provideProvider<JobConfigurationEnqueuer>(name = input.jobName)
        if (!enqueuer.exists) {
            throw IllegalArgumentException("Unknown job name: ${input.jobName}")
        }

        // Validate cron expression if provided
        val cronExpr = input.cronExpression
        val error = CronExpression.validate(cronExpr)
        if (error != null) {
            throw IllegalArgumentException("Invalid cron expression: $error")
        }
        val cron = CronExpression.parse(cronExpr)
        val now = OffsetDateTime.now()
        val nextRunAt = cron.nextExecution(now)

        val principalState = if (input.requiresPrincipal == true) {
            ScheduledJobPrincipalState.NEEDS_PRINCIPAL
        } else {
            ScheduledJobPrincipalState.NOT_REQUIRED
        }
        val scheduledJob = ScheduledJob(
            id = UUID.random(),
            name = input.name,
            description = input.description,
            jobName = input.jobName,
            jobParameters = input.jobParameters,
            cronExpression = cronExpr,
            enabled = (input.enabled ?: true) && principalState == ScheduledJobPrincipalState.NOT_REQUIRED,
            allowConcurrent = input.allowConcurrent ?: false,
            catchUp = input.catchUp ?: false,
            maxCatchUp = input.maxCatchUp ?: 1,
            createdAt = now,
            updatedAt = now,
            createdBy = createdBy,
            principalState = principalState,
            lastRunAt = null,
            nextRunAt = nextRunAt
        )

        return jobRepository.add(scheduledJob)
    }

    override suspend fun updateJob(id: UUID, input: ScheduledJobInput): ScheduledJob? {
        val existing = jobRepository.getById(id) ?: return null

        // Validate job name exists
        val enqueuer = provideProvider<JobConfigurationEnqueuer>(name = input.jobName)
        if (!enqueuer.exists) {
            throw IllegalArgumentException("Unknown job name: ${input.jobName}")
        }

        // Validate cron expression if provided
        val cronExpr = input.cronExpression
        val error = CronExpression.validate(cronExpr)
        if (error != null) {
            throw IllegalArgumentException("Invalid cron expression: $error")
        }
        val cron = CronExpression.parse(cronExpr)
        val now = OffsetDateTime.now()
        val nextRunAt = cron.nextExecution(now)

        val currentlyRequiresPrincipal = existing.principalState != ScheduledJobPrincipalState.NOT_REQUIRED
        val requiresPrincipal = input.requiresPrincipal ?: currentlyRequiresPrincipal
        val principalState = when {
            !requiresPrincipal -> ScheduledJobPrincipalState.NOT_REQUIRED
            !currentlyRequiresPrincipal -> ScheduledJobPrincipalState.NEEDS_PRINCIPAL
            else -> existing.principalState
        }
        val principalReady = principalState == ScheduledJobPrincipalState.NOT_REQUIRED ||
            principalState == ScheduledJobPrincipalState.ACTIVE
        val updated = existing.copy(
            name = input.name,
            description = input.description,
            jobName = input.jobName,
            jobParameters = input.jobParameters,
            cronExpression = cronExpr,
            enabled = (input.enabled ?: existing.enabled) && principalReady,
            allowConcurrent = input.allowConcurrent ?: existing.allowConcurrent,
            catchUp = input.catchUp ?: existing.catchUp,
            maxCatchUp = input.maxCatchUp ?: existing.maxCatchUp,
            updatedAt = now,
            nextRunAt = nextRunAt,
            executionPrincipalId = if (requiresPrincipal) existing.executionPrincipalId else null,
            principalState = principalState,
            principalAssignedBy = if (requiresPrincipal) existing.principalAssignedBy else null,
            principalConfirmedBy = if (requiresPrincipal) existing.principalConfirmedBy else null,
        )

        return jobRepository.update(updated)
    }

    override suspend fun deleteJob(id: UUID) {
        return jobRepository.deleteById(id)
    }

    override suspend fun enableJob(id: UUID): ScheduledJob? {
        val job = jobRepository.getById(id) ?: return null
        check(job.principalState == ScheduledJobPrincipalState.NOT_REQUIRED ||
            job.principalState == ScheduledJobPrincipalState.ACTIVE
        ) { "Scheduled job $id needs a confirmed execution principal" }

        // Recalculate next run time
        val nextRunAt = calculateNextRunTime(job)
        jobRepository.updateRunTimes(id, job.lastRunAt ?: OffsetDateTime.now(), nextRunAt)

        return jobRepository.enable(id)
    }

    override suspend fun disableJob(id: UUID): ScheduledJob? {
        return jobRepository.disable(id)
    }

    override suspend fun assignExecutionPrincipal(
        id: UUID,
        principalId: UUID,
        assignedBy: UUID,
        confirmedBy: UUID?,
    ): ScheduledJob? {
        val job = jobRepository.getById(id) ?: return null
        check(job.principalState != ScheduledJobPrincipalState.NOT_REQUIRED) {
            "Scheduled job $id does not require an execution principal"
        }
        requireLivePrincipal(principalId)
        val state = if (confirmedBy == null) {
            ScheduledJobPrincipalState.PENDING_CONFIRMATION
        } else {
            ScheduledJobPrincipalState.ACTIVE
        }
        return jobRepository.assignPrincipal(
            id,
            principalId,
            assignedBy,
            confirmedBy,
            state,
            enabled = state == ScheduledJobPrincipalState.ACTIVE,
        )
    }

    override suspend fun confirmExecutionPrincipal(id: UUID, confirmedBy: UUID): ScheduledJob? {
        val job = jobRepository.getById(id) ?: return null
        check(job.principalState == ScheduledJobPrincipalState.PENDING_CONFIRMATION) {
            "Scheduled job $id has no pending principal assignment"
        }
        requireLivePrincipal(
            job.executionPrincipalId
                ?: throw IllegalStateException("Scheduled job $id has no assigned execution principal")
        )
        return jobRepository.confirmPrincipal(id, confirmedBy)
    }

    override suspend fun clearExecutionPrincipal(id: UUID): ScheduledJob? {
        val job = jobRepository.getById(id) ?: return null
        check(job.principalState != ScheduledJobPrincipalState.NOT_REQUIRED) {
            "Scheduled job $id does not require an execution principal"
        }
        return jobRepository.clearPrincipal(id)
    }

    override suspend fun parkNeedsPrincipal(id: UUID): ScheduledJob? {
        val job = jobRepository.getById(id) ?: return null
        check(job.principalState != ScheduledJobPrincipalState.NOT_REQUIRED) {
            "Scheduled job $id does not require an execution principal"
        }
        return jobRepository.parkNeedsPrincipal(id)
    }

    override suspend fun triggerJob(id: UUID): JobHistory? {
        val job = jobRepository.getById(id) ?: return null
        if (!hasEligiblePrincipal(job)) return null
        val lock = distributedLockFactory.create("scheduler:${id}")
        if (!lock.tryAcquire(60_000)) {
            throw IllegalStateException("Could not acquire lock for job $id")
        }
        try {
            // Check concurrent execution policy
            if (!job.allowConcurrent && historyRepository.hasActiveExecutions(id)) {
                throw IllegalStateException("Job has active executions and does not allow concurrent execution")
            }

            // Get the enqueuer for this job
            val enqueuer: JobConfigurationEnqueuer = provide(name = job.jobName)

            // Enqueue the job
            val enqueuedJob = enqueuer.enqueue(job.jobParameters) {
                addCallback(JobCallback(ScheduledJobExecutionListener::class))
                disableEmitEvent = true
                if (job.principalState != ScheduledJobPrincipalState.NOT_REQUIRED) {
                    setContext(job.executionContext())
                }
            }

            // Create history record
            return createHistory(
                scheduledJobId = id,
                jobId = enqueuedJob.getId(),
                name = job.name,
                scheduledFor = OffsetDateTime.now(),
                source = JobHistorySource.SCHEDULER,
                wasCatchUp = false,
                delayedUntil = null,
                definition = job.jobParameters,
                context = enqueuedJob.getContext()
            )
        } finally {
            // Released even when this coroutine was cancelled, instead of leaving the lock to its TTL.
            withContext(NonCancellable) { lock.release() }
        }
    }

    override suspend fun cancelJob(historyId: UUID): JobHistory {
        return historyRepository.cancelById(
            historyId,
            ScheduleExecutionStatus.CANCELLED,
            OffsetDateTime.now(),
            null
        ) ?: throw IllegalStateException(
            "Cannot cancel job $historyId: entry not found or already in a terminal state"
        )
    }

    override suspend fun getHistory(
        scheduledJobId: UUID,
        limit: Int,
        offset: Long,
        status: ScheduleExecutionStatus?,
        source: JobHistorySource?
    ): List<JobHistory> {
        return historyRepository.getByScheduledJobId(scheduledJobId, limit, offset, status, source)
    }

    override suspend fun countHistory(
        scheduledJobId: UUID,
        status: ScheduleExecutionStatus?,
        source: JobHistorySource?
    ): Long {
        return historyRepository.countByScheduledJobId(scheduledJobId, status, source)
    }

    override suspend fun getAllHistory(
        limit: Int,
        offset: Long,
        status: ScheduleExecutionStatus?,
        source: JobHistorySource?
    ): List<JobHistory> {
        return historyRepository.getAll(limit, offset, status, source)
    }

    override suspend fun countAllHistory(
        status: ScheduleExecutionStatus?,
        source: JobHistorySource?
    ): Long {
        return historyRepository.countAll(status, source)
    }

    override suspend fun validateCronExpression(expression: String): CronValidationResult {
        val error = CronExpression.validate(expression)
        if (error != null) {
            return CronValidationResult(valid = false, error = error)
        }

        val cron = CronExpression.parse(expression)
        val nextRuns = cron.nextExecutions(OffsetDateTime.now(), 5)
        return CronValidationResult(valid = true, nextRuns = nextRuns)
    }

    override suspend fun updateJobStatus(
        jobId: UUID,
        status: ScheduleExecutionStatus,
        errorMessage: String?,
        context: JsonElement?,
    ): JobHistory? {
        val completedAt = terminalCompletedAt(status)
        return historyRepository.updateStatus(jobId, status, completedAt, errorMessage, context)
    }

    override suspend fun updateJobStatusByHistoryId(
        historyId: UUID,
        status: ScheduleExecutionStatus,
        errorMessage: String?,
    ): JobHistory? {
        val completedAt = terminalCompletedAt(status)
        return historyRepository.updateStatusById(historyId, status, completedAt, errorMessage)
    }

    private fun terminalCompletedAt(status: ScheduleExecutionStatus): OffsetDateTime? = when (status) {
        ScheduleExecutionStatus.COMPLETED,
        ScheduleExecutionStatus.FAILED,
        ScheduleExecutionStatus.SKIPPED,
        ScheduleExecutionStatus.CANCELLED,
        ScheduleExecutionStatus.STALE -> OffsetDateTime.now()
        else -> null
    }

    override suspend fun setJobId(historyId: UUID, jobId: UUID) {
        historyRepository.setJobId(historyId, jobId)
    }

    override suspend fun getDueJobs(): List<ScheduledJob> {
        return jobRepository.getDueJobs(OffsetDateTime.now())
    }

    override suspend fun updateJobRunTimes(
        id: UUID,
        lastRunAt: OffsetDateTime,
        nextRunAt: OffsetDateTime?
    ): ScheduledJob? {
        return jobRepository.updateRunTimes(id, lastRunAt, nextRunAt)
    }

    override suspend fun hasActiveExecutions(scheduledJobId: UUID): Boolean {
        return historyRepository.hasActiveExecutions(scheduledJobId)
    }

    override suspend fun cleanupStaleExecutions(): Long {
        val cutoff = OffsetDateTime.now().minusHours(12)
        val count = historyRepository.countStaleRecords(cutoff)
        if (count > 0) {
            historyRepository.markStaleRecords(
                cutoff,
                "Marked as stale: no status update received within expected execution window"
            )
        }
        return count
    }

    override suspend fun purgeHistoryBefore(before: OffsetDateTime): Long =
        historyRepository.deleteCompletedBefore(before).toLong()

    override suspend fun createHistory(
        scheduledJobId: UUID?,
        jobId: UUID,
        name: String,
        scheduledFor: OffsetDateTime,
        source: JobHistorySource,
        wasCatchUp: Boolean,
        delayedUntil: OffsetDateTime?,
        definition: JsonElement?,
        context: JsonElement?,
        parentJobId: UUID?
    ): JobHistory {
        val entry = JobHistory(
            id = UUID.random(),
            scheduledJobId = scheduledJobId,
            jobId = jobId,
            name = name,
            scheduledFor = scheduledFor,
            triggeredAt = OffsetDateTime.now(),
            source = source,
            status = ScheduleExecutionStatus.PENDING,
            wasCatchUp = wasCatchUp,
            delayedUntil = delayedUntil,
            parentJobId = parentJobId,
            definition = definition,
            context = context
        )
        return historyRepository.add(entry)
    }

    override suspend fun refreshPendingHistory(
        jobId: UUID,
        scheduledFor: OffsetDateTime,
        delayedUntil: OffsetDateTime?
    ): JobHistory? = historyRepository.refreshPendingRow(jobId, scheduledFor, delayedUntil)

    private fun calculateNextRunTime(job: ScheduledJob): OffsetDateTime? {
        val cronExpr = job.cronExpression
        val cron = CronExpression.parse(cronExpr)
        return cron.nextExecution(OffsetDateTime.now())
    }

    private suspend fun hasEligiblePrincipal(job: ScheduledJob): Boolean = when (job.principalState) {
        ScheduledJobPrincipalState.NOT_REQUIRED -> true
        ScheduledJobPrincipalState.ACTIVE -> {
            val principal = job.executionPrincipalId?.let { securityService.getPrincipalById(it) }
            if (principal != null && principal.deletedAt == null) {
                true
            } else {
                jobRepository.parkNeedsPrincipal(job.id)
                false
            }
        }
        ScheduledJobPrincipalState.NEEDS_PRINCIPAL,
        ScheduledJobPrincipalState.PENDING_CONFIRMATION -> false
    }

    private suspend fun requireLivePrincipal(principalId: UUID) {
        val principal = securityService.getPrincipalById(principalId)
            ?: throw NoSuchElementException("Principal not found: $principalId")
        check(principal.deletedAt == null) { "Principal $principalId is disabled" }
    }

    private fun ScheduledJob.executionContext(): JsonElement = Json.encodeToJsonElement(
        ScheduledJobExecutionContext.serializer(),
        ScheduledJobExecutionContext(id, executionPrincipalId),
    )
}

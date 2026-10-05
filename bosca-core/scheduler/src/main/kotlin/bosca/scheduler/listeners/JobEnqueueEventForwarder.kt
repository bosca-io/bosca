package bosca.scheduler.listeners

import bosca.db.withConnectionManager
import bosca.scheduler.model.JobHistorySource
import bosca.scheduler.model.ScheduleExecutionStatus
import bosca.scheduler.service.SchedulerService
import bosca.serialization.OffsetDateTime
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEvent
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEventChannel
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class JobEnqueueEventForwarder(
    private val channel: JobEnqueueEventChannel,
    private val schedulerService: SchedulerService
) {

    private var job: Job? = null

    fun start() {
        if (job != null) return
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("JobEnqueueEventForwarder"))
        job = scope.launch {
            withConnectionManager {
                channel.events().collect { event -> handle(event) }
            }
        }
    }

    /**
     * Applies a single enqueue/status event to the history table.
     *
     * Extracted from [start]'s collector so the event-ordering logic is unit-testable without
     * driving a coroutine/flow. The forwarder collects events sequentially over an in-process
     * channel, so invoking this in arrival order reproduces production behaviour exactly.
     */
    internal suspend fun handle(event: JobEnqueueEvent) {
        try {
            if (event.status != null) {
                val status = event.status.toExecutionStatus() ?: return
                val previous = schedulerService.updateJobStatus(event.jobId, status, event.errorMessage, event.context)
                if (previous != null) return
            }
            // Two shapes of enqueue events arrive here:
            //   1. First-time enqueue of a brand new job id   -> INSERT.
            //   2. Re-enqueue of an already-tracked job id     -> UPDATE.
            // Case 2 covers DelayException re-queues from the runner and any executor-lock
            // retries. Previously both cases inserted, producing a ladder of "pending" rows
            // sharing the same job_id. We try the in-place update first; if no active row
            // exists we fall through to INSERT so the new attempt is still recorded.
            val scheduledFor = event.enqueuedAt ?: OffsetDateTime.now()
            val refreshed = schedulerService.refreshPendingHistory(
                jobId = event.jobId,
                scheduledFor = scheduledFor,
                delayedUntil = event.delayedUntil
            )
            // Enqueue and status events for one execution arrive as separate messages, and the
            // completion event can be processed before the `status == null` enqueue event. In
            // that ordering the completion path has already created and completed the row, so
            // `refreshPendingHistory` (which only matches pending/running) misses and we would
            // otherwise INSERT a second, forever-pending duplicate. Only insert when this job_id
            // has no row at all; a terminal row means the completion raced ahead and there is
            // nothing to add. Safe as a check-then-insert because the forwarder is a single
            // sequential collector over an in-process channel — no concurrent inserter per job_id.
            if (refreshed == null && schedulerService.getHistoryByJobId(event.jobId) == null) {
                schedulerService.createHistory(
                    jobId = event.jobId,
                    // Prefer the cosmetic display name set via `@JobDefinition(displayName = ...)`.
                    // Fall back through `executorName` (the DI lookup key — some jobs still use it
                    // as a visible identifier historically) and finally to the executor class name.
                    name = event.displayName ?: event.executorName ?: event.executor ?: "unknown",
                    scheduledFor = scheduledFor,
                    source = JobHistorySource.EVENT,
                    delayedUntil = event.delayedUntil,
                    definition = event.definition,
                    context = event.context,
                    parentJobId = event.parentJobId
                )
            }
            if (event.status != null) {
                val status = event.status.toExecutionStatus() ?: return
                schedulerService.updateJobStatus(event.jobId, status, event.errorMessage, event.context)
            }
            log.info("Recorded enqueue event for job {} on queue {}, status: {}", event.jobId, event.queue, event.status)
        } catch (e: Exception) {
            log.error("Failed to record enqueue event for job {}: {}", event.jobId, e.message, e)
        }
    }

    // Nullable receiver so callers don't need to smart-cast `event.status` (a cross-module
    // public property, which the compiler refuses to smart-cast). `null`/UNINITIALIZED map to null.
    private fun JobStatus?.toExecutionStatus(): ScheduleExecutionStatus? = when (this) {
        JobStatus.PENDING -> ScheduleExecutionStatus.PENDING
        JobStatus.RUNNING -> ScheduleExecutionStatus.RUNNING
        JobStatus.COMPLETE -> ScheduleExecutionStatus.COMPLETED
        JobStatus.FAILED -> ScheduleExecutionStatus.FAILED
        JobStatus.FAILED_AND_COMPLETE -> ScheduleExecutionStatus.FAILED
        else -> null
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private val log = LoggerFactory.getLogger(JobEnqueueEventForwarder::class.java)
    }
}

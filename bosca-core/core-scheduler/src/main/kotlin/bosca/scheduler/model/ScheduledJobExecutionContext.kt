package bosca.scheduler.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/** Scheduler-owned metadata attached to every enqueued principal-aware scheduled job's queue context. */
@Serializable
data class ScheduledJobExecutionContext(
    val scheduledJobId: UUID,
    val executionPrincipalId: UUID? = null,
)

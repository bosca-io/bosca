package bosca.sharedqueue.jobs.enqueue

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class JobEnqueueEvent(
    val jobId: UUID,
    val executor: String? = null,
    /**
     * The DI lookup key for the executor, carried over from [bosca.sharedqueue.jobs.Job.executorName].
     * Exposed for diagnostic logging; the admin UI uses [displayName] for rendering and falls
     * back through [executorName] / [executor] only when [displayName] is absent.
     */
    val executorName: String? = null,
    val queue: String? = null,
    val enqueuedAt: OffsetDateTime? = null,
    val delayed: Boolean = false,
    val delayedUntil: OffsetDateTime? = null,
    val status: JobStatus? = null,
    val errorMessage: String? = null,
    val definition: JsonElement? = null,
    val context: JsonElement? = null,
    /**
     * Identifier of the enclosing parent job, when this event describes a child that was
     * fanned out from a multi-job or another composite executor.
     *
     * Event-sourced history insert uses this to link the child row to its parent, so the
     * admin UI can render the child under the parent's dropdown rather than as an orphaned
     * top-level row. `null` when the job is not a fan-out child.
     */
    val parentJobId: UUID? = null,
    /**
     * Human-readable label for admin UIs. Sourced from
     * `@JobDefinition(displayName = ...)` via [bosca.sharedqueue.jobs.Job.displayName].
     * Cosmetic only — never used for DI resolution.
     */
    val displayName: String? = null,
)

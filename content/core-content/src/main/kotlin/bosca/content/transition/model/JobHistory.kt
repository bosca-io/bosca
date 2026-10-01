package bosca.content.transition.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

data class JobHistoryId(
    val name: String,
    val id: UUID
)

/**
 * Represents a single job execution history record. Each entry tracks the lifecycle of
 * a workflow job from creation through completion, including timing, status, and outcome.
 * Implemented by both [bosca.content.metadata.model.MetadataJobHistory] and
 * [bosca.content.collection.model.CollectionJobHistory].
 */
interface JobHistory {

    /** The name identifying the type of job that was executed. */
    val jobName: String

    /** The identifier of the content item (metadata or collection) this job was executed for. */
    val id: UUID

    /** The unique identifier of this specific job execution. */
    val jobId: UUID

    /** A status message describing the current or final state of the job execution. */
    val status: String

    /** The timestamp when this job execution was created. */
    val created: OffsetDateTime

    /** The timestamp when this job execution completed, or null if still running. */
    val complete: OffsetDateTime?

    /** Whether the job completed successfully. False if the job failed or is still running. */
    val success: Boolean

    /** The identifier of the principal (user) who initiated this job, or null if system-initiated. */
    val principal: UUID?

    /** The timestamp until which this job's execution was delayed, or null if not delayed. */
    val delayedUntil: OffsetDateTime?
}
package bosca.sharedqueue.jobs.listeners

import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobListener
import bosca.sharedqueue.jobs.JobStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

const val JOB_STATUS_CHANNEL = "job-status"

/**
 * Notification payload published when a job reaches a terminal state (complete or failed).
 *
 * Subscribers can use this to await the outcome of remotely enqueued jobs, inspecting
 * [status] to distinguish success from failure and [errorMessage] for failure details.
 */
@Serializable
data class JobStatusNotification(
    val jobId: UUID,
    val status: JobStatus,
    val context: JsonElement = JsonNull,
    val errorMessage: String? = null
)

/**
 * Job listener that publishes a [JobStatusNotification] to the [JOB_STATUS_CHANNEL]
 * PubSub channel when a job completes or fails.
 *
 * This enables callers that enqueue jobs remotely to subscribe and await terminal
 * outcomes without polling the job queue.
 */
class NotifyJobStatusListener(
    private val pubsub: PubSubService
) : JobListener {

    override suspend fun onStatusChanged(job: Job, status: JobStatus, errorMessage: String?) {
        // Terminal outcomes only: COMPLETE or the terminal failure FAILED_AND_COMPLETE. A non-terminal
        // FAILED (the job will retry) must not be published as an outcome to an awaiting subscriber.
        if (status != JobStatus.COMPLETE && status != JobStatus.FAILED_AND_COMPLETE) return
        pubsub.publish(
            JOB_STATUS_CHANNEL,
            JobStatusNotification.serializer(),
            JobStatusNotification(job.id, status, job.getContext(), errorMessage)
        )
    }
}

package bosca.sharedqueue.jobs.listeners

import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobListener
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.jobQueue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val JOB_COMPLETE_CHANNEL = "job-complete"

@Serializable
data class JobCompleteNotification(val jobId: UUID, val context: JsonElement)

class NotifyJobCompleteListener(
    private val pubsub: PubSubService
) : JobListener {

    override suspend fun onStatusChanged(job: Job, status: JobStatus, errorMessage: String?) {
        if (status != JobStatus.COMPLETE) return
        pubsub.publish(JOB_COMPLETE_CHANNEL, JobCompleteNotification.serializer(), JobCompleteNotification(job.id, job.getContext()))
    }
}
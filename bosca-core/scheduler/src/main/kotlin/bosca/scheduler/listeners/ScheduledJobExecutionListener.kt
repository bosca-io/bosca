package bosca.scheduler.listeners

import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobListener
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEvent
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEventChannel

class ScheduledJobExecutionListener(
    private val channel: JobEnqueueEventChannel
) : JobListener {

    override suspend fun onStatusChanged(job: Job, status: JobStatus, errorMessage: String?) {
        channel.emit(
            JobEnqueueEvent(
                jobId = job.getId(),
                status = status,
                errorMessage = errorMessage,
                definition = job.getDefinition(),
                context = job.getContext()
            )
        )
    }
}

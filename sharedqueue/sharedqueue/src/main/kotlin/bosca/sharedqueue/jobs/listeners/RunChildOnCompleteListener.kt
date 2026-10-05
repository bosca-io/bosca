package bosca.sharedqueue.jobs.listeners

import bosca.core.annotations.Internal
import bosca.lock.DistributedLockFactory
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobListener
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.jobQueue
import bosca.sharedqueue.jobs.newJobLock

internal class RunChildOnCompleteListener(
    private val distributedLockFactory: DistributedLockFactory
) : JobListener {

    @OptIn(Internal::class)
    override suspend fun onStatusChanged(job: Job, status: JobStatus, errorMessage: String?) {
        // On success run every child; on a terminal failure run only the children flagged
        // runOnFailure (which carry the failure forward, e.g. a pipeline resume that must run whether
        // its backing job succeeded or terminally failed). A non-terminal FAILED (retrying) is ignored.
        val onFailure = status == JobStatus.FAILED_AND_COMPLETE
        if (status != JobStatus.COMPLETE && !onFailure) return
        val queue = jobQueue()
        queue.getJob(job.id) {
            it?.children?.forEach { child ->
                if (onFailure && !child.getRunOnFailure()) return@forEach
                val lock = child.lock ?: newJobLock(distributedLockFactory, child.id, 60_000, true)
                try {
                    if (!lock.isHeld && !lock.renew(60_000)) {
                        error("Failed to acquire lock for child job ${child.id}")
                    }
                    child.lock = lock
                    queue.enqueue(child)
                } finally {
                    lock.release()
                }
            }
        }
    }
}
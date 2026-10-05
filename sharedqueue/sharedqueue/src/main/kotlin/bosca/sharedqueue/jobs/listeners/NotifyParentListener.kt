package bosca.sharedqueue.jobs.listeners

import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobListener
import bosca.sharedqueue.jobs.JobQueueRegistry
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.jobQueue
import org.slf4j.LoggerFactory

internal class NotifyParentListener : JobListener {

    override suspend fun onStatusChanged(job: Job, status: JobStatus, errorMessage: String?) {
        // Only react to terminal states. RUNNING status changes don't affect
        // the parent's fully-complete status (a child going PENDING->RUNNING
        // doesn't change whether the parent can mark complete), and firing
        // here on RUNNING introduces an inverse locking order vs.
        // RunChildOnCompleteListener on the parent: that listener holds the
        // parent lock and acquires the child lock to enqueue children, while
        // this path (invoked while the worker holds the child lock) would
        // then try to acquire the parent lock. Under load that turns into
        // lock waits that are hard to distinguish from a stall.
        // Terminal states only. FAILED is now non-terminal (the job will retry), so a failed child
        // notifies its parent only once it reaches FAILED_AND_COMPLETE (terminal); a successful child
        // notifies on COMPLETE.
        if (status != JobStatus.COMPLETE && status != JobStatus.FAILED_AND_COMPLETE) return
        // A COMPLETE child whose OWN children are still open is not done — only its job delivery is.
        // The parent's join evaluates children from its embedded attach-time snapshots (grandchildren
        // never appear there), so recording this child COMPLETE now would let the parent read as fully
        // complete and DELETE its state while live descendants still need it (the release-relay
        // "item N failed to start: Job not found" orphaning). Defer: when the child's last descendant
        // reports, its own markComplete fires this listener again with the subtree genuinely settled.
        // A terminal failure is NOT deferred — its state is deleted immediately either way, and a
        // deferred notify would never come.
        if (status == JobStatus.COMPLETE && !job.areChildrenComplete()) return
        val currentQueue = jobQueue()
        // The parent may live on a DIFFERENT queue than this child (e.g. a workops backing job parked
        // under a pipelines run job) — a same-queue lookup would silently find nothing and the parent
        // would never resume. Resolve the parent's queue by the name the child carries.
        val queue = job.parentQueue
            ?.takeIf { it != currentQueue.name }
            ?.let { parentQueueName ->
                JobQueueRegistry.find(parentQueueName) ?: run {
                    log.error(
                        "Parent queue '{}' for child {} is not live in this process — falling back to the child's queue '{}'",
                        parentQueueName, job.id, currentQueue.name,
                    )
                    null
                }
            }
            ?: currentQueue
        job.parentId?.let { parentId ->
            try {
                queue.getJob(parentId) { parent ->
                    if (parent == null) {
                        // Not an ignorable case: the parent will never learn this child finished, so
                        // anything joined on it (e.g. a parked pipeline run) hangs until swept.
                        log.error(
                            "Parent job {} of completed child {} not found in queue '{}' — the parent will not be notified",
                            parentId, job.id, queue.name,
                        )
                    } else {
                        parent.setChildStatus(job.id, status)
                        // Let the parent's listeners monitor their immediate dependents and react while
                        // the parent is held locked here — e.g. a coordinator that drives itself forward
                        // and enqueues its next child. This runs BEFORE the fully-complete check, so a
                        // child added by a listener keeps the parent open.
                        parent.callbacks.forEach { cb ->
                            try {
                                cb.newListener().onChildStatusChanged(parent, job, status, errorMessage)
                            } catch (e: Exception) {
                                log.error("onChildStatusChanged listener failed for parent $parentId child ${job.id}: ${e.message}", e)
                            }
                        }
                        if (parent.isFullyComplete()) {
                            queue.markComplete(parent)
                        } else {
                            queue.setJob(parent)
                        }
                    }
                }
            } catch (e: Exception) {
                log.error("Parent job $parentId not found for child ${job.id}, it may have already completed or been removed: ${e.message}")
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(NotifyParentListener::class.java)
    }
}
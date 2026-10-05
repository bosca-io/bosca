package bosca.kubernetes.jobs

import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.prepare

/**
 * Creates the stable queue representation of a durable Kubernetes dispatch.
 *
 * The caller-owned [dispatchId] lets outbox recovery use
 * [bosca.sharedqueue.jobs.JobQueue.enqueueIfAbsent] without replacing an already-published job.
 */
suspend fun KubernetesJobRequest.prepareDispatchJob(dispatchId: UUID): Job = prepare(
    id = dispatchId,
    executor = KubernetesJobDispatchExecutor::class,
    displayName = "Kubernetes Job: $profile",
) {
    // The standalone Kubernetes controller consumes these records and does not host the
    // scheduler's ordinary in-process job-history callback.
    disableEmitEvent = true
    disableEnqueueCallbacks = true
}

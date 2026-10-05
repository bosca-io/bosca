package bosca.kubernetes.jobs

import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

/**
 * Supplies serialization and catalog metadata for [KubernetesJobRequest].
 *
 * Requests are consumed by the Kubernetes controller itself. A normal Bosca JobRunner must never
 * execute this class.
 */
@JobDefinition(
    KubernetesJobRequest::class,
    KubernetesJobQueueNames.jobQueue,
    KubernetesJobQueueNames.dispatch,
    "Kubernetes Job Dispatch",
)
class KubernetesJobDispatchExecutor : AbstractJobExecutor<KubernetesJobRequest>(
    KubernetesJobRequest.serializer(),
) {
    override suspend fun execute(): Nothing =
        error("The '${KubernetesJobQueueNames.queue}' queue is consumed only by the Kubernetes controller")
}

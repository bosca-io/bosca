package bosca.kubernetes.service

import bosca.kubernetes.jobs.KubernetesJobRequest
import bosca.kubernetes.model.KubernetesJobExecution
import bosca.kubernetes.model.KubernetesJobResult
import bosca.serialization.UUID
import bosca.service.Service

/** Enqueues generic workload requests for the Kubernetes controller. */
interface KubernetesJobDispatchService : Service {

    /**
     * Enqueues [request] on the physical `kubernetes-jobs` queue.
     *
     * The returned ID identifies the durable queue item, not the Kubernetes Job. The controller
     * acknowledges the item only after it observes or creates the corresponding Kubernetes Job.
     */
    suspend fun dispatch(request: KubernetesJobRequest): UUID

    /**
     * Returns the durable execution state for [dispatchId], or null for a legacy or unknown ID.
     */
    suspend fun getExecution(dispatchId: UUID): KubernetesJobExecution?

    /**
     * Returns the terminal result for [dispatchId].
     *
     * A null result means the dispatch is unknown or still queued, materialized, or running.
     * Producers persist the dispatch ID with their own domain job and use this durable result to
     * complete, fail, or cancel that job after a restart without relying on an in-memory event.
     */
    suspend fun getResult(dispatchId: UUID): KubernetesJobResult?

    /**
     * Requests cancellation of a queued or materialized dispatch.
     *
     * This operation is idempotent. The durable cancellation intent is written before the queue
     * record is removed. The controller checks that intent before creation and deletes an already
     * materialized Kubernetes Job during reconciliation.
     */
    suspend fun cancel(dispatchId: UUID)
}

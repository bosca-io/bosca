package bosca.kubernetes.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Terminal outcome of a workload dispatched through the `kubernetes-jobs` queue. */
@Serializable
enum class KubernetesJobResultStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

/**
 * Stable Bosca-side result contract for a completed Kubernetes workload.
 *
 * The payload intentionally describes the orchestration outcome rather than a workload-specific
 * model. Training, embedding, CI, and other producers retain their own domain output coordinates
 * and use this result to settle their owning Bosca Job.
 */
@Serializable
data class KubernetesJobResult(
    @Contextual
    val dispatchId: UUID,
    val profile: String,
    val idempotencyKey: String,
    val status: KubernetesJobResultStatus,
    val message: String? = null,
    @Contextual
    val startedAt: OffsetDateTime? = null,
    @Contextual
    val finishedAt: OffsetDateTime,
)

/** Returns this execution's terminal result, or null while it can still make progress. */
fun KubernetesJobExecution.toResultOrNull(): KubernetesJobResult? {
    val resultStatus = when (status) {
        KubernetesJobExecutionStatus.SUCCEEDED -> KubernetesJobResultStatus.SUCCEEDED
        KubernetesJobExecutionStatus.FAILED -> KubernetesJobResultStatus.FAILED
        KubernetesJobExecutionStatus.CANCELLED -> KubernetesJobResultStatus.CANCELLED
        else -> return null
    }
    return KubernetesJobResult(
        dispatchId = dispatchId,
        profile = profile,
        idempotencyKey = idempotencyKey,
        status = resultStatus,
        message = message,
        startedAt = startedAt,
        finishedAt = requireNotNull(finishedAt) {
            "Terminal Kubernetes Job execution $dispatchId is missing finishedAt"
        },
    )
}

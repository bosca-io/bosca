package bosca.recommendations.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Job payload for a TFRS model training run.
 *
 * [kubernetesDispatchId] is persisted after the first dispatch so redelivery polls the same
 * external workload instead of starting another Kubernetes Job. Optional [configuration]
 * overrides let individual runs adjust parameters such as lookback days, epochs, or embedding
 * dimension without changing the stable JobProfile.
 */
@Serializable
data class TrainModelJob(
    /** Optional configuration overrides passed to the trainer as JSON. */
    val configuration: JsonElement? = null,
    /** Durable Kubernetes dispatch being monitored, or null until this job first dispatches. */
    @Contextual
    val kubernetesDispatchId: UUID? = null,
    /** Captured context model version; null schedules one training snapshot per context. */
    val contextModelVersion: Long? = null,
) : IJobDefinition

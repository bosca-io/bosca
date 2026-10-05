package bosca.kubernetes.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Durable lifecycle state for one request on the physical `kubernetes-jobs` queue. */
@DbMapper(KubernetesJobExecutionStatusMapper::class)
@Serializable
enum class KubernetesJobExecutionStatus {
    QUEUED,
    MATERIALIZED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCEL_REQUESTED,
    CANCELLED,
    ;

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

object KubernetesJobExecutionStatusMapper :
    EnumMapper<KubernetesJobExecutionStatus>({ KubernetesJobExecutionStatus.valueOf(it.uppercase()) })

/**
 * Durable control-plane record for a Kubernetes Job dispatch.
 *
 * Queue delivery remains the source of dispatch demand. This record carries lifecycle state that
 * must survive queue acknowledgement: cancellation intent, the materialized Kubernetes identity,
 * and terminal execution results for consumers such as CI and model-training schedulers.
 */
@Serializable
data class KubernetesJobExecution(
    @Contextual
    @ColumnName("dispatch_id")
    val dispatchId: UUID,
    val profile: String,
    @ColumnName("idempotency_key")
    val idempotencyKey: String,
    /** Serialized request retained as a durable dispatch outbox payload. */
    val request: JsonElement = JsonNull,
    val status: KubernetesJobExecutionStatus = KubernetesJobExecutionStatus.QUEUED,
    @Contextual
    @ColumnName("cluster_id")
    val clusterId: UUID? = null,
    val namespace: String? = null,
    @ColumnName("job_name")
    val jobName: String? = null,
    val message: String? = null,
    @Contextual
    @ColumnName("created_at")
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    @ColumnName("modified_at")
    val modifiedAt: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    @ColumnName("published_at")
    val publishedAt: OffsetDateTime? = null,
    @Contextual
    @ColumnName("materialized_at")
    val materializedAt: OffsetDateTime? = null,
    @Contextual
    @ColumnName("started_at")
    val startedAt: OffsetDateTime? = null,
    @Contextual
    @ColumnName("finished_at")
    val finishedAt: OffsetDateTime? = null,
)

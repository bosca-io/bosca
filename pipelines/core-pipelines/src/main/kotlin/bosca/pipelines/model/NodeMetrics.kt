package bosca.pipelines.model

import bosca.db.annotation.ColumnName
import kotlinx.serialization.Serializable

/**
 * Aggregate execution metrics for one node across a pipeline's runs,
 * computed over **completions** (OK + FAILED events; SUSPENDED/SKIPPED lifecycle events are excluded):
 *  - [executions] — number of completions; [failures] — how many were FAILED.
 *  - [p50Ms] / [p95Ms] — median and 95th-percentile completion duration (ms).
 *
 * A read projection (not a stored row): the repository computes it, the run service exposes it, and
 * GraphQL projects it onto `PipelineNodeMetrics`.
 */
@Serializable
data class NodeMetrics(
    @ColumnName("node_id")
    val nodeId: String,
    val executions: Long,
    val failures: Long,
    @ColumnName("p50_ms")
    val p50Ms: Double,
    @ColumnName("p95_ms")
    val p95Ms: Double,
)

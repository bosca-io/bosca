package bosca.pipelines.node

import bosca.pipelines.model.NodeExecutionStatus
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.json.JsonElement

/**
 * A node-execution event the executor emits as it runs each node — the
 * raw observation, before it is keyed to a run and persisted. Carries everything the executor knows
 * at the node level; the run identity, id, and duration are filled by the persisting sink.
 */
data class NodeExecutionEvent(
    val nodeId: String,
    val status: NodeExecutionStatus,
    val startedAt: OffsetDateTime,
    val finishedAt: OffsetDateTime,
    /** The output port the value left on (routing/error), or `null` for the implicit output. */
    val port: String? = null,
    /** Failure message when [status] is FAILED. */
    val error: String? = null,
    /** The node's output as JSON, for the snapshot (the sink may cap its size). */
    val output: JsonElement? = null,
)

/**
 * Receives per-node execution events during a run. Threaded on [bosca.pipelines.PipelineContext]
 * exactly like the dry-run trace, keeping the executor **repository-free**: it observes, it does not
 * persist. The durable run service supplies a buffering implementation and flushes the collected
 * events to the run's timeline *after* each drive returns (so the executor's concurrent fan-out never
 * issues DB writes on the run's connection); non-durable runs leave it `null`.
 *
 * [record] is called concurrently from the executor's fan-out, so implementations must be thread-safe.
 */
interface NodeExecutionSink {
    fun record(event: NodeExecutionEvent)
}

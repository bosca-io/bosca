@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.node

import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * The successful completion of a node that declares a rollback pipeline —
 * the executor emits one whenever a node with a [PipelineNode.rollbackPipeline] finishes OK. The run
 * service records these durably and, on a later non-OK terminal, runs each [rollbackPipelineId] (with
 * [output] as input) in reverse order to undo the side effects (saga rollback).
 */
data class RollbackEvent(
    val nodeId: String,
    val rollbackPipelineId: UUID,
    /** The node's output as JSON — fed as the rollback pipeline's input so it can target what was done. */
    val output: JsonElement? = null,
)

/**
 * Receives [RollbackEvent]s during a run, threaded on [bosca.pipelines.PipelineContext] exactly like
 * [NodeExecutionSink] — keeping the executor repository-free. The durable run service supplies a
 * buffering implementation and flushes after each drive; non-durable runs leave it `null` (no rollback).
 *
 * [record] is called concurrently from the executor's fan-out, so implementations must be thread-safe.
 */
interface RollbackSink {
    fun record(event: RollbackEvent)
}

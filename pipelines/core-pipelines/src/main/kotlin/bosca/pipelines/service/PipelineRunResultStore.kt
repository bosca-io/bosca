@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.service

import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * Durable staging store for a suspended node's pending output, keyed by `(runId, nodeId)`.
 * A `JobStatusNotification` only carries status/error, so the value a node will
 * emit once its out-of-band work finishes is parked here and read by the resume:
 *
 *  - a **gate** node (e.g. `ExecuteJob`) [put]s its inbound value at suspend (it passes that value
 *    through on success);
 *  - a **compute** node's backing job [put]s its computed result (it carries the run/node identity in
 *    its context).
 *
 * On resume the value is read with [get], promoted into the run's checkpoint, then [remove]d. A
 * missing entry is handled gracefully by the resume (no output). Resolved via `provide<T>()` from
 * nodes (deserialized data, not constructor-injected), so this is a [Service].
 */
interface PipelineRunResultStore : Service {

    /**
     * Stage the output node [nodeId] of run [runId] will emit on resume (upsert): [result] is the
     * value, [port] the optional outcome port to route it on (`null` = the default output).
     */
    suspend fun put(runId: UUID, nodeId: String, result: JsonElement, port: String? = null)

    /** The staged output for `(runId, nodeId)` (value + optional port), or `null` if none was stored. */
    suspend fun get(runId: UUID, nodeId: String): PipelineRunNodeResult?

    /** Discard the staged output for `(runId, nodeId)` (after it has been promoted, or on failure). */
    suspend fun remove(runId: UUID, nodeId: String)
}

@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi

/**
 * The **durable, mutable state** of a single pipeline run — the row a run is rebuilt from when it
 * resumes after suspending. This is deliberately distinct from `pipeline_run_log` (the append-only
 * one-row-per-finished-run audit trail, which is preserved unchanged): a [PipelineRun] is the *live*
 * record, updated as the run progresses and torn down (soft-deleted) only by retention.
 *
 *  - [graphSnapshot] — the pipeline's graph JSON captured at run start. Resumption evaluates *this*
 *    snapshot, so a later edit to the stored pipeline can never change a run already in flight.
 *  - [input] / [inputType] — the seed value encoded to JSON plus its origin type's serial name, so
 *    the run's `InputNode` value can be reconstructed on resume (the encoded value + origin type).
 *  - [eventName] — the catalogued event that triggered the run (`""` for non-triggered runs);
 *    the key the resume path uses to find the event serializer.
 *  - [nodeOutputs] — the checkpoint: each already-evaluated node's id → its output encoded to JSON
 *    (a node with no output is recorded as JSON `null`). A resume seeds the executor from this map
 *    instead of re-running completed nodes. Empty `{}` until the run first checkpoints.
 *  - [awaiting] — the set of `{nodeId, correlationId}` the run is parked on while [PipelineRunStatus.SUSPENDED].
 *    Empty `[]` while running. (Populated only in later phases; the column exists from the start.)
 *  - [version] — optimistic-lock guard so concurrent resume/cancel writers can't clobber each other.
 *  - [deletedAt] — soft delete (retention), per platform convention.
 *
 * The jsonb-backed fields are `@Contextual JsonElement` (handled automatically at the jsonb boundary,
 * exactly like `PipelineRecord.graph`); the typed graph is folded in/out by the service via the
 * aggregated node `SerializersModule`, so this model carries no engine knowledge of node types.
 */
@Serializable
data class PipelineRun(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("pipeline_id")
    @Contextual
    val pipelineId: UUID,
    val status: PipelineRunStatus = PipelineRunStatus.RUNNING,
    @ColumnName("event_name")
    val eventName: String = "",
    @ColumnName("graph_snapshot")
    @Contextual
    val graphSnapshot: JsonElement,
    @Contextual
    val input: JsonElement? = null,
    @ColumnName("input_type")
    val inputType: String? = null,
    @ColumnName("node_outputs")
    @Contextual
    val nodeOutputs: JsonElement = JsonObject(emptyMap()),
    @Contextual
    val awaiting: JsonElement = JsonArray(emptyList()),
    // Durable-iteration linkage: set on a child run that a ForEach started per item,
    // so the child reports its output back to the parent's iteration aggregation on completion.
    @ColumnName("parent_run_id")
    @Contextual
    val parentRunId: UUID? = null,
    @ColumnName("parent_node_id")
    val parentNodeId: String? = null,
    @ColumnName("item_index")
    val itemIndex: Int? = null,
    /**
     * The id of the **run's job** — the platform job whose execution drives this run
     * ("run = a job"). Its backing-work jobs and resume jobs are attached to it as children, so it is
     * not *fully* complete until the run reaches a terminal state; its completion is the single
     * run-completion hook. Null for a run with no driving job (an inline/on-demand run).
     */
    @ColumnName("run_job_id")
    @Contextual
    val runJobId: UUID? = null,
    /**
     * The principal that originated an **on-demand** run (manual / API), captured at start so the run —
     * driven asynchronously by its own run job, through suspend/resume and backing work — executes under
     * the caller's security context end to end rather than the service account. Null for a
     * triggered/scheduled run (those drive under the pipelines service account).
     */
    @ColumnName("principal_id")
    @Contextual
    val principalId: UUID? = null,
    /** The completed run's Output-node value (encoded JSON), set on OK; null while running or with no Output node. */
    @Contextual
    val output: JsonElement? = null,
    val error: String? = null,
    val version: Long = 0,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = java.time.OffsetDateTime.now(),
    @ColumnName("modified_at")
    @Contextual
    val modifiedAt: OffsetDateTime = java.time.OffsetDateTime.now(),
    @ColumnName("deleted_at")
    @Contextual
    val deletedAt: OffsetDateTime? = null,
)

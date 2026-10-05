@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.repository

import bosca.db.annotation.ColumnName
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi

/**
 * Aggregation state for one durable ForEach: the item [total], the
 * failure policy, and each finished child's [results] keyed by item index (as text). When the result
 * count reaches [total] the iteration is complete.
 *
 * A bounded iteration ([maxConcurrency] > 0) also carries the raw [items] array: only the first
 * [maxConcurrency] children start with the suspend, and as item `i` reports, item
 * `i + maxConcurrency` is started from [items]. Unbounded (0, and every pre-upgrade row) keeps the
 * fan-everything-at-once behavior and stores no items.
 */
@Serializable
data class PipelineRunIteration(
    @ColumnName("parent_run_id")
    @Contextual
    val parentRunId: UUID,
    @ColumnName("node_id")
    val nodeId: String,
    val total: Int,
    @ColumnName("continue_on_error")
    val continueOnError: Boolean,
    @Contextual
    val results: JsonElement = JsonObject(emptyMap()),
    @ColumnName("max_concurrency")
    val maxConcurrency: Int = 0,
    @Contextual
    val items: JsonElement? = null,
)

/**
 * Per-ForEach iteration aggregation (`pipelines.pipeline_run_iteration`). Each child run, on
 * completion, atomically merges its output into [recordResult] keyed by its item index; the call
 * returns the post-merge row so the caller can detect completion (results count == total).
 */
@Repository
interface PipelineRunIterationRepository {

    /** Open the aggregation for a ForEach node; idempotent under at-least-once redelivery of the suspend. */
    @Query(
        """
        insert into pipelines.pipeline_run_iteration (parent_run_id, node_id, total, continue_on_error, max_concurrency, items)
        values (:parentRunId, :nodeId, :total, :continueOnError, :maxConcurrency, :items)
        on conflict (parent_run_id, node_id) do nothing
        """
    )
    suspend fun create(
        parentRunId: UUID,
        nodeId: String,
        total: Int,
        continueOnError: Boolean,
        maxConcurrency: Int,
        items: JsonElement?,
    )

    /**
     * Atomically record one item's [result] (output JSON, or an error marker) at its [index] (text
     * key) and return the post-merge row. Re-recording the same index is idempotent (overwrite, count
     * unchanged), so at-least-once child redelivery converges.
     */
    @Query(
        """
        update pipelines.pipeline_run_iteration
           set results = results || jsonb_build_object(:index, :result)
         where parent_run_id = :parentRunId and node_id = :nodeId
        returning *
        """
    )
    suspend fun recordResult(parentRunId: UUID, nodeId: String, index: String, result: JsonElement): PipelineRunIteration?
}

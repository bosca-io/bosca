@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.pipelines.model.NodeExecutionRecord
import bosca.pipelines.model.NodeMetrics
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Append-only per-node execution timeline (`pipelines.pipeline_run_node`).
 * One row per node-execution event; the run-detail query and per-node metrics
 * read it back.
 *
 * The [add] insert casts the bound status to the native enum
 * (`:...::pipelines.node_execution_status`): the [bosca.db.mapper.EnumMapper] binds a `varchar` and
 * Postgres does not implicitly coerce it into an enum column.
 */
@Repository
interface NodeExecutionRepository {

    @Query(
        """
        insert into pipelines.pipeline_run_node
            (run_id, node_id, status, started_at, finished_at, duration_ms, port, error, output)
        values
            (:runId, :nodeId, :status::pipelines.node_execution_status, :startedAt, :finishedAt,
             :durationMs, :port, :error, :output)
        returning *
        """
    )
    suspend fun add(record: NodeExecutionRecord): NodeExecutionRecord

    /** The full node timeline for a run, in execution order — the source for run-detail. */
    @Query(
        """
        select * from pipelines.pipeline_run_node
         where run_id = :runId
         order by started_at, created_at
        """
    )
    suspend fun listForRun(runId: UUID): List<NodeExecutionRecord>

    /**
     * Per-node aggregate metrics across a pipeline's runs — over OK+FAILED completions only,
     * busiest node first. `percentile_cont` gives the median/p95 completion duration.
     */
    @Query(
        """
        select n.node_id,
               count(*) as executions,
               count(*) filter (where n.status = 'failed') as failures,
               coalesce(percentile_cont(0.5) within group (order by n.duration_ms), 0) as p50_ms,
               coalesce(percentile_cont(0.95) within group (order by n.duration_ms), 0) as p95_ms
          from pipelines.pipeline_run_node n
          join pipelines.pipeline_run r on r.id = n.run_id
         where r.pipeline_id = :pipelineId and n.status in ('ok', 'failed')
         group by n.node_id
         order by executions desc
        """
    )
    suspend fun metricsForPipeline(pipelineId: UUID): List<NodeMetrics>
}

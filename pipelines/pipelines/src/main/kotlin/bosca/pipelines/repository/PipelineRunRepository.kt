@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.model.PipelineRunStatus
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * Durable run-state store (`pipelines.pipeline_run`) — the live record a run is rebuilt from on
 * resume. Separate from [PipelineRunLogRepository], which keeps the append-only run *history*.
 *
 * Every write that touches `status` casts the bound value to the native enum
 * (`:status::pipelines.pipeline_run_status`): the [bosca.db.mapper.EnumMapper] binds a `varchar`, and
 * Postgres does not implicitly coerce `varchar` into an enum column.
 */
@Repository
interface PipelineRunRepository {

    @Query("select * from pipelines.pipeline_run where id = :id and deleted_at is null")
    suspend fun getById(id: UUID): PipelineRun?

    /** The child runs a For Each / Run Pipeline node spawned, in item order — for step-oriented views. */
    @Query(
        """
        select * from pipelines.pipeline_run
        where parent_run_id = :parentRunId and parent_node_id = :nodeId and deleted_at is null
        order by item_index nulls first, created_at
        """
    )
    suspend fun listByParentAndNode(parentRunId: UUID, nodeId: String): List<PipelineRun>

    /** One item's child run in a durable iteration — unique per (parent, node, index). */
    @Query(
        """
        select * from pipelines.pipeline_run
        where parent_run_id = :parentRunId and parent_node_id = :nodeId and item_index = :itemIndex
          and deleted_at is null
        """
    )
    suspend fun getByParentItem(parentRunId: UUID, nodeId: String, itemIndex: Int): PipelineRun?

    @Query(
        """
        insert into pipelines.pipeline_run
            (pipeline_id, status, event_name, graph_snapshot, input, input_type, parent_run_id, parent_node_id,
             item_index, run_job_id, principal_id)
        values
            (:pipelineId, :status::pipelines.pipeline_run_status, :eventName, :graphSnapshot, :input, :inputType,
             :parentRunId, :parentNodeId, :itemIndex, :runJobId, :principalId)
        returning *
        """
    )
    suspend fun add(run: PipelineRun): PipelineRun

    /**
     * Record a terminal outcome ([PipelineRunStatus.OK] / [PipelineRunStatus.FAILED] /
     * [PipelineRunStatus.CANCELLED]). [output] is the completed Output-node value (set on OK; null
     * otherwise) so an on-demand caller can read the result of a run that finished asynchronously.
     */
    @Query(
        """
        update pipelines.pipeline_run
           set status = :status::pipelines.pipeline_run_status,
               output = :output,
               error = :error,
               version = version + 1,
               modified_at = now()
         where id = :id and deleted_at is null
        returning *
        """
    )
    suspend fun complete(id: UUID, status: PipelineRunStatus, output: JsonElement?, error: String?): PipelineRun?

    /**
     * Atomically advance run state — the checkpoint ([nodeOutputs]), outstanding [awaiting] set, and
     * [status] — guarded by the optimistic-lock [version]. Returns the updated row, or `null` when
     * the version no longer matches (a concurrent resume/suspend won the race); callers treat `null`
     * as "someone else advanced it" and back off, which is what makes resume idempotent.
     */
    @Query(
        """
        update pipelines.pipeline_run
           set status = :status::pipelines.pipeline_run_status,
               node_outputs = :nodeOutputs,
               awaiting = :awaiting,
               error = :error,
               version = version + 1,
               modified_at = now()
         where id = :id and version = :version and deleted_at is null
        returning *
        """
    )
    suspend fun updateState(
        id: UUID,
        status: PipelineRunStatus,
        nodeOutputs: JsonElement,
        awaiting: JsonElement,
        error: String?,
        version: Long,
    ): PipelineRun?

    /** Runs stuck SUSPENDED since before [before] (their backing work never resolved) — the sweep set. */
    @Query(
        """
        select * from pipelines.pipeline_run
         where status = 'suspended' and modified_at < :before and deleted_at is null
         order by modified_at
         limit :limit
        """
    )
    suspend fun findStuckSuspended(before: OffsetDateTime, limit: Int): List<PipelineRun>

    /** In-flight durable runs (running or suspended), newest activity first — for operator visibility. */
    @Query(
        """
        select * from pipelines.pipeline_run
         where status in ('running', 'suspended') and deleted_at is null
         order by modified_at desc
         limit :limit offset :offset
        """
    )
    suspend fun listActive(offset: Long, limit: Int): List<PipelineRun>

    /** In-flight (running or suspended) run count for one pipeline — the concurrency-cap gate. */
    @Query(
        """
        select count(*) from pipelines.pipeline_run
         where pipeline_id = :pipelineId and status in ('running', 'suspended') and deleted_at is null
        """
    )
    suspend fun countActive(pipelineId: UUID): Long

    /** Count of runs of one pipeline started since [since] — the rolling-window rate-limit gate. */
    @Query("select count(*) from pipelines.pipeline_run where pipeline_id = :pipelineId and created_at >= :since")
    suspend fun countStartedSince(pipelineId: UUID, since: OffsetDateTime): Long

    /** Failed runs (the dead-letter queue), newest first, for operator triage + replay. */
    @Query(
        """
        select * from pipelines.pipeline_run
         where status = 'failed' and deleted_at is null
         order by modified_at desc
         limit :limit offset :offset
        """
    )
    suspend fun listFailed(offset: Long, limit: Int): List<PipelineRun>

    /**
     * Soft-delete terminal run-state rows last touched before [before] (retention).
     * Only terminal runs are purged; in-flight ones are never reaped. Returns the count.
     */
    @Query(
        value = """
        update pipelines.pipeline_run set deleted_at = now()
         where deleted_at is null and status in ('ok', 'failed', 'cancelled') and modified_at < :before
        """,
        returnUpdateCount = true,
    )
    suspend fun purgeTerminalBefore(before: OffsetDateTime): Int

    /**
     * Soft-delete one terminal run by id (operator removal from the dead-letter queue). Gated on a
     * terminal status so an in-flight run can't be deleted out from under its drive — it must be
     * cancelled first. Returns the rows affected (0 when missing, already deleted, or still running).
     */
    @Query(
        value = """
        update pipelines.pipeline_run set deleted_at = now()
         where id = :id and deleted_at is null and status in ('ok', 'failed', 'cancelled')
        """,
        returnUpdateCount = true,
    )
    suspend fun softDeleteById(id: UUID): Int
}

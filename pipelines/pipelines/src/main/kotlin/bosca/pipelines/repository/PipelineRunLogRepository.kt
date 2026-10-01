@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.pipelines.model.PipelineRunLog
import bosca.pipelines.model.PipelineRunLogWithName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/** Append-only run-history for triggered pipeline executions. */
@Repository
interface PipelineRunLogRepository {

    @Query(
        """
        insert into pipelines.pipeline_run_log
            (pipeline_id, run_id, event_name, outcome, started_at, finished_at, duration_ms, error_message)
        values (:pipelineId, :runId, :eventName, :outcome, :startedAt, :finishedAt, :durationMs, :errorMessage)
        returning *
        """
    )
    suspend fun add(log: PipelineRunLog): PipelineRunLog

    @Query(
        """
        select r.*, p.name as pipeline_name
        from pipelines.pipeline_run_log r
        join pipelines.pipelines p on p.id = r.pipeline_id
        where r.pipeline_id = :pipelineId
        order by r.started_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listForPipeline(pipelineId: UUID, offset: Long, limit: Int): List<PipelineRunLogWithName>

    @Query(
        """
        select r.*, p.name as pipeline_name
        from pipelines.pipeline_run_log r
        join pipelines.pipelines p on p.id = r.pipeline_id
        order by r.started_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listAll(offset: Long, limit: Int): List<PipelineRunLogWithName>

    /** Delete run-history rows finished before [before] (retention). Returns the count. */
    @Query(
        value = "delete from pipelines.pipeline_run_log where finished_at is not null and finished_at < :before",
        returnUpdateCount = true,
    )
    suspend fun deleteFinishedBefore(before: OffsetDateTime): Int
}

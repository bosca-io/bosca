@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.pipelines.model.RollbackRecord
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/** Saga-rollback log (`pipelines.pipeline_run_rollback`). */
@Repository
interface RollbackRepository {

    @Query(
        """
        insert into pipelines.pipeline_run_rollback (run_id, node_id, rollback_pipeline_id, output)
        values (:runId, :nodeId, :rollbackPipelineId, :output)
        """
    )
    suspend fun add(record: RollbackRecord)

    /** A run's rollback entries in **reverse** order (last completed first) — the rollback order. */
    @Query("select * from pipelines.pipeline_run_rollback where run_id = :runId order by seq desc")
    suspend fun listForRunReversed(runId: UUID): List<RollbackRecord>

    /** A run's failed rollback entries in rollback order. */
    @Query("select * from pipelines.pipeline_run_rollback where run_id = :runId and failed_at is not null order by seq desc")
    suspend fun listFailedReversed(runId: UUID): List<RollbackRecord>

    /** Records why a rollback did not complete. */
    @Query("update pipelines.pipeline_run_rollback set error = :error, failed_at = now() where seq = :seq")
    suspend fun markFailed(seq: Long, error: String)

    /** Clears a failure for one retry; only the caller that receives 1 may run the rollback again. */
    @Query("update pipelines.pipeline_run_rollback set error = null, failed_at = null where seq = :seq and failed_at is not null", returnUpdateCount = true)
    suspend fun claimFailed(seq: Long): Int
}

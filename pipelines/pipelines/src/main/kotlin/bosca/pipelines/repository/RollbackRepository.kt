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
}

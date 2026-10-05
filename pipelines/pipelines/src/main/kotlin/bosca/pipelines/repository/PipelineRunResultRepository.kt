@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * Staging store for a suspended node's pending output, keyed by `(run_id, node_id)`
 * (`pipelines.pipeline_run_result`). See [bosca.pipelines.service.PipelineRunResultStore].
 */
@Repository
interface PipelineRunResultRepository {

    @Query(
        """
        insert into pipelines.pipeline_run_result (run_id, node_id, result)
        values (:runId, :nodeId, :result)
        on conflict (run_id, node_id) do update set result = :result
        """
    )
    suspend fun put(runId: UUID, nodeId: String, result: JsonElement)

    @Query("select result from pipelines.pipeline_run_result where run_id = :runId and node_id = :nodeId")
    suspend fun get(runId: UUID, nodeId: String): JsonElement?

    @Query("delete from pipelines.pipeline_run_result where run_id = :runId and node_id = :nodeId")
    suspend fun remove(runId: UUID, nodeId: String)
}

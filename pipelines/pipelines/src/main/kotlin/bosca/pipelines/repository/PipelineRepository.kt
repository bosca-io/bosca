@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

@Repository
interface PipelineRepository {

    @Query("select * from pipelines.pipelines where deleted_at is null order by name")
    suspend fun getAll(): List<PipelineRecord>

    @Query("select * from pipelines.pipelines where id = :id and deleted_at is null")
    suspend fun getById(id: UUID): PipelineRecord?

    @Query("select * from pipelines.pipelines where triggered and accepted_input_type = :eventType and deleted_at is null")
    suspend fun getTriggeredByEventType(eventType: String): List<PipelineRecord>

    @Query("select distinct accepted_input_type from pipelines.pipelines where triggered and deleted_at is null")
    suspend fun getTriggeredEventTypes(): List<String>

    @Query("select * from pipelines.pipelines where key = :key and deleted_at is null")
    suspend fun getByKey(key: String): PipelineRecord?

    @Query(
        """
        insert into pipelines.pipelines
            (name, description, accepted_input_type, tags, triggered, key, api, public, schedule,
             max_concurrent_runs, max_runs_per_minute, graph)
        values
            (:name, :description, :acceptedInputType, :tags, :triggered, :key, :api, :public, :schedule,
             :maxConcurrentRuns, :maxRunsPerMinute, :graph)
        returning *
        """
    )
    suspend fun add(record: PipelineRecord): PipelineRecord

    @Query(
        """
        update pipelines.pipelines
           set name = :name, description = :description, accepted_input_type = :acceptedInputType,
               tags = :tags, triggered = :triggered, key = :key, api = :api, public = :public, schedule = :schedule,
               max_concurrent_runs = :maxConcurrentRuns, max_runs_per_minute = :maxRunsPerMinute, graph = :graph,
               version = version + 1, modified_at = now()
         where id = :id and version = :version and deleted_at is null
        returning *
        """
    )
    suspend fun update(record: PipelineRecord): PipelineRecord?

    @Query("update pipelines.pipelines set deleted_at = now() where id = :id")
    suspend fun softDelete(id: UUID)

    @Query("select * from pipelines.pipelines where git_repository_id = :repositoryId and git_path = :path and deleted_at is null")
    suspend fun getByGitRepository(repositoryId: UUID, path: String): PipelineRecord?

    // Git linkage and sync-status writes deliberately do NOT bump `version`: they are
    // bookkeeping, not edits, and must not invalidate a concurrent editor's optimistic lock.
    @Query("update pipelines.pipelines set git_repository_id = :repositoryId, git_path = :path where id = :id")
    suspend fun linkToGit(id: UUID, repositoryId: UUID, path: String)

    @Query("update pipelines.pipelines set last_sync_error = :message where id = :id")
    suspend fun setSyncError(id: UUID, message: String?)
}

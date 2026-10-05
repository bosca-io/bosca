package bosca.git.ci.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.PipelineArtifact
import bosca.serialization.UUID

@Repository
interface PipelineArtifactRepository {

    @Query("""
        insert into git.pipeline_artifacts (repository_id, pipeline_run_id, run_number, name, size_bytes)
        values (:repositoryId, :pipelineRunId, :runNumber, :name, :sizeBytes)
        on conflict (repository_id, run_number, name)
        do update set size_bytes = excluded.size_bytes, created = now()
        returning *
    """)
    suspend fun upsert(artifact: PipelineArtifact): PipelineArtifact

    @Query("select * from git.pipeline_artifacts where pipeline_run_id = :pipelineRunId order by name")
    suspend fun findByRun(pipelineRunId: UUID): List<PipelineArtifact>

    @Query("delete from git.pipeline_artifacts where id = :id")
    suspend fun delete(id: UUID)
}

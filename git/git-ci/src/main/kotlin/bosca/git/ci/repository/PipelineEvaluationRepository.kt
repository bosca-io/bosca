package bosca.git.ci.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.PipelineEvaluation
import bosca.serialization.UUID

@Repository
interface PipelineEvaluationRepository {
    /** Reserves the durable trigger identity once; redelivery cannot reset its decision. */
    @Query("""
        insert into git.pipeline_evaluations (id, repository_id, commit_sha, ref)
        values (:id, :repositoryId, :commitSha, :ref)
        on conflict (id) do nothing
    """, returnUpdateCount = true)
    suspend fun create(evaluation: PipelineEvaluation): Int

    /** Reads the recorded selection and any evaluation error. */
    @Query("select * from git.pipeline_evaluations where id = :id")
    suspend fun findById(id: UUID): PipelineEvaluation?

    /** Lists trigger decisions in creation order. */
    @Query("""
        select * from git.pipeline_evaluations
        where repository_id = :repositoryId and commit_sha = :commitSha
        order by created, id
    """)
    suspend fun findByCommit(repositoryId: UUID, commitSha: String): List<PipelineEvaluation>

    /** Recent decisions and unfinished evaluations remain eligible for recovery. */
    @Query("""
        select * from git.pipeline_evaluations where repository_id = :repositoryId
          and (modified >= :since or not completed)
        order by modified, id limit :limit offset :offset
    """)
    suspend fun findChangedSince(repositoryId: UUID, since: bosca.serialization.OffsetDateTime, offset: Long, limit: Int): List<PipelineEvaluation>

    /** Makes deletion of a commit's run visible to its aggregate checks. */
    @Query("update git.pipeline_evaluations set modified = clock_timestamp() where repository_id = :repositoryId and commit_sha = :commitSha returning *")
    suspend fun refreshByCommit(repositoryId: UUID, commitSha: String): List<PipelineEvaluation>

    /** Moves selections of [previousRunId] to [runId], including on completed decisions. */
    @Query("""
        update git.pipeline_evaluations
        set modified = clock_timestamp(), selections = (
            select jsonb_agg(case when entry.selection->>'runId' = :previousRunId::text
                then jsonb_set(entry.selection, '{runId}', to_jsonb(:runId::text)) else entry.selection end
                order by entry.position)
            from jsonb_array_elements(selections) with ordinality as entry(selection, position)
        )
        where repository_id = :repositoryId and commit_sha = :commitSha
          and selections @> jsonb_build_array(jsonb_build_object('runId', :previousRunId::text))
    """, returnUpdateCount = true)
    suspend fun replaceRun(repositoryId: UUID, commitSha: String, previousRunId: UUID, runId: UUID): Int

    /** Adds or replaces one file's selection atomically while evaluation remains open. */
    @Query("""
        update git.pipeline_evaluations
        set modified = clock_timestamp(), selections = coalesce((
            select jsonb_agg(selection) from jsonb_array_elements(selections) selection
            where selection->>'filePath' <> :filePath
        ), '[]'::jsonb) || jsonb_build_array(jsonb_build_object('filePath', :filePath::text, 'runId', :runId::uuid))
        where id = :id and not completed
    """, returnUpdateCount = true)
    suspend fun recordRun(id: UUID, filePath: String, runId: UUID?): Int

    /** Updates an open decision; completed decisions are immutable. */
    @Query("""
        update git.pipeline_evaluations set modified = clock_timestamp(), completed = :completed, error = :error
        where id = :id and not completed
        returning *
    """)
    suspend fun finish(id: UUID, completed: Boolean, error: String?): PipelineEvaluation?
}

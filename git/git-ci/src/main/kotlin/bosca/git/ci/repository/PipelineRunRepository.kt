package bosca.git.ci.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.serialization.UUID

@Repository
interface PipelineRunRepository {

    /** Reserves one automatic trigger occurrence; a competing transaction waits for its outcome. */
    @Query("""
        insert into git.pipeline_trigger_occurrences (trigger_id, pipeline_id)
        values (:triggerId, :pipelineId)
        on conflict (trigger_id, pipeline_id) do nothing
    """, returnUpdateCount = true)
    suspend fun reserveTrigger(triggerId: UUID, pipelineId: UUID): Int

    @Query("select * from git.pipeline_runs where id = :id")
    suspend fun findById(id: UUID): PipelineRun?

    @Query("select * from git.pipeline_runs where id = :id for update")
    suspend fun findByIdForUpdate(id: UUID): PipelineRun?

    @Query("select * from git.pipeline_runs where pipeline_id = :pipelineId order by number desc limit :limit offset :offset")
    suspend fun findByPipeline(pipelineId: UUID, offset: Long, limit: Int): List<PipelineRun>

    @Query("select * from git.pipeline_runs where repository_id = :repositoryId order by created desc limit :limit offset :offset")
    suspend fun findByRepository(repositoryId: UUID, offset: Long, limit: Int): List<PipelineRun>

    @Query("""
        select * from git.pipeline_runs
        where parameters->>'release.id' = :releaseId
        order by created desc
        limit :limit offset :offset
    """)
    suspend fun findByReleaseId(releaseId: String, offset: Long, limit: Int): List<PipelineRun>

    @Query("""
        select * from git.pipeline_runs
        where concurrency_group = :concurrencyGroup and status in ('queued', 'running')
        order by created
    """)
    suspend fun findActiveByConcurrencyGroup(concurrencyGroup: String): List<PipelineRun>

    @Query("select coalesce(max(number), 0) from git.pipeline_runs where pipeline_id = :pipelineId")
    suspend fun getMaxNumber(pipelineId: UUID): Int

    /**
     * The pipeline's newest run for a ref — the pipeline-requirement correlation lookup.
     * Latest by number wins, so a failed run followed by a successful re-run satisfies the requirement.
     */
    @Query("""
        select * from git.pipeline_runs
        where pipeline_id = :pipelineId and ref = :ref
        order by number desc
        limit 1
    """)
    suspend fun findLatestByPipelineAndRef(pipelineId: UUID, ref: String): PipelineRun?

    /** Latest explicit attempt of a selected root run; independent executions cannot replace it. */
    @Query("""
        select * from git.pipeline_runs
        where id = :runId or rerun_of = :runId
        order by attempt_order desc
        limit 1
    """)
    suspend fun findLatestAttempt(runId: UUID): PipelineRun?

    /** Recent source changes and active runs, in stable pages for recovery. */
    @Query("""
        select * from git.pipeline_runs where repository_id = :repositoryId
          and (modified >= :since or status in ('queued', 'running'))
        order by modified, id limit :limit offset :offset
    """)
    suspend fun findChangedSince(repositoryId: UUID, since: bosca.serialization.OffsetDateTime, offset: Long, limit: Int): List<PipelineRun>

    /** All executions for an immutable commit, including explicit retry lineage. */
    @Query("select * from git.pipeline_runs where repository_id = :repositoryId and commit_sha = :commitSha order by number, id")
    suspend fun findByCommit(repositoryId: UUID, commitSha: String): List<PipelineRun>

    @Query("""
        insert into git.pipeline_runs (pipeline_id, repository_id, commit_sha, ref, trigger_type, triggered_by, status, number, concurrency_group, parameters, rerun_of)
        values (:pipelineId, :repositoryId, :commitSha, :ref, :triggerType::git.pipeline_trigger_type, :triggeredBy, :status::git.pipeline_run_status, :number, :concurrencyGroup, :parameters, :rerunOf)
        returning *
    """)
    suspend fun create(run: PipelineRun): PipelineRun

    /** The latest successful RELEASE run for [ref] — proof the release phase (and its
     *  deploy-on-release environments) completed for this tag (chain validation). */
    @Query("""
        select * from git.pipeline_runs
        where pipeline_id = :pipelineId and ref = :ref and trigger_type = 'release' and status = 'success'
        order by number desc limit 1
    """)
    suspend fun findLatestSuccessfulRelease(pipelineId: UUID, ref: String): PipelineRun?

    /** The latest successful PROMOTION of [ref] into [environment] — proof that promotion stage
     *  completed for this tag (chain validation). */
    @Query("""
        select * from git.pipeline_runs
        where pipeline_id = :pipelineId and ref = :ref and trigger_type = 'promotion' and status = 'success'
          and parameters->>'promotion.environment' = :environment
        order by number desc limit 1
    """)
    suspend fun findLatestSuccessfulPromotion(pipelineId: UUID, ref: String, environment: String): PipelineRun?

    /** The latest successful promotion into [environment] across ALL refs — what version the
     *  environment currently holds, for the downgrade guard. */
    @Query("""
        select * from git.pipeline_runs
        where pipeline_id = :pipelineId and trigger_type = 'promotion' and status = 'success'
          and parameters->>'promotion.environment' = :environment
        order by number desc limit 1
    """)
    suspend fun findLatestSuccessfulPromotionToEnvironment(pipelineId: UUID, environment: String): PipelineRun?

    /** Starts the run and returns the persisted revision for event dispatch. */
    @Query("update git.pipeline_runs set modified = clock_timestamp(), status = :status::git.pipeline_run_status, started = now() where id = :id returning *")
    suspend fun markStarted(id: UUID, status: PipelineRunStatus): PipelineRun?

    /** Finishes the run and returns the persisted revision for event dispatch. */
    @Query("update git.pipeline_runs set modified = clock_timestamp(), status = :status::git.pipeline_run_status, finished = now() where id = :id returning *")
    suspend fun markFinished(id: UUID, status: PipelineRunStatus): PipelineRun?

    /** Changes the run status and returns the persisted revision for event dispatch. */
    @Query("update git.pipeline_runs set modified = clock_timestamp(), status = :status::git.pipeline_run_status where id = :id returning *")
    suspend fun updateStatus(id: UUID, status: PipelineRunStatus): PipelineRun?

    /**
     * Re-run-failed: re-opens a FAILED/CANCELLED run whose failed jobs were reset — the
     * run keeps its identity and number and rejoins the normal lifecycle (RUNNING when a job starts,
     * finalized when every job is terminal again). The re-runner becomes the run's initiator.
     */
    @Query("""
        update git.pipeline_runs
        set modified = clock_timestamp(), status = 'queued'::git.pipeline_run_status, finished = null,
            attempt_order = nextval('git.pipeline_run_attempts'),
            triggered_by = coalesce(:triggeredBy, triggered_by)
        where id = :id and status in ('failure', 'cancelled')
        returning *
    """)
    suspend fun resetForRerun(id: UUID, triggeredBy: UUID?): PipelineRun?

    @Query("delete from git.pipeline_runs where id = :id returning *")
    suspend fun delete(id: UUID): PipelineRun?
}

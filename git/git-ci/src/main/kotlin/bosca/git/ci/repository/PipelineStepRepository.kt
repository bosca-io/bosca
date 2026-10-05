package bosca.git.ci.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineStep
import bosca.serialization.UUID

@Repository
interface PipelineStepRepository {

    @Query("select * from git.pipeline_steps where id = :id")
    suspend fun findById(id: UUID): PipelineStep?

    @Query("select * from git.pipeline_steps where pipeline_job_id = :jobId order by ordinal")
    suspend fun findByJob(jobId: UUID): List<PipelineStep>

    @Query("""
        insert into git.pipeline_steps (
            pipeline_job_id, name, ordinal, status,
            uses, run, image, condition, working_directory, with_args, env
        )
        values (
            :pipelineJobId, :name, :ordinal, :status::git.pipeline_run_status,
            :uses, :run, :image, :condition, :workingDirectory, :with::jsonb, :env::jsonb
        )
        returning *
    """)
    suspend fun create(step: PipelineStep): PipelineStep

    /**
     * Guarded like the job transitions (run control): a terminal step is immutable, so a
     * late agent report cannot overwrite a server-side cancellation. Re-running resets via
     * [resetForRerun].
     */
    @Query("""
        update git.pipeline_steps
        set status = :status::git.pipeline_run_status, exit_code = :exitCode, started = now()
        where id = :id and status in ('queued', 'running')
    """)
    suspend fun markStarted(id: UUID, status: PipelineRunStatus, exitCode: Int?)

    @Query("""
        update git.pipeline_steps
        set status = :status::git.pipeline_run_status, exit_code = :exitCode, error_message = :errorMessage, finished = now()
        where id = :id and status in ('queued', 'running')
    """)
    suspend fun markFinished(id: UUID, status: PipelineRunStatus, exitCode: Int?, errorMessage: String?)

    @Query("""
        update git.pipeline_steps
        set status = :status::git.pipeline_run_status
        where id = :id and status in ('queued', 'running')
    """)
    suspend fun updateStatus(id: UUID, status: PipelineRunStatus)

    /** Re-run-failed: resets every step of a re-run job back to queued. */
    @Query("""
        update git.pipeline_steps
        set status = 'queued'::git.pipeline_run_status, exit_code = null, error_message = null,
            started = null, finished = null
        where pipeline_job_id = :jobId
    """)
    suspend fun resetForRerun(jobId: UUID)
}

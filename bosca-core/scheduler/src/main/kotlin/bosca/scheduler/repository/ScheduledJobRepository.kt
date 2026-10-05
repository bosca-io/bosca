package bosca.scheduler.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.model.ScheduledJobPrincipalState
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlin.time.Instant

@Repository
interface ScheduledJobRepository {

    @Query("""
        INSERT INTO scheduler.scheduled_jobs (
            name, description, job_name, job_parameters, cron_expression, enabled, allow_concurrent,
            catch_up, max_catch_up, created_by, next_run_at, execution_principal_id,
            principal_state, principal_assigned_by, principal_confirmed_by
        ) VALUES (
            :name, :description, :jobName, :jobParameters, :cronExpression, :enabled, :allowConcurrent,
            :catchUp, :maxCatchUp, :createdBy, :nextRunAt, :executionPrincipalId,
            :principalState::scheduler.scheduled_job_principal_state, :principalAssignedBy, :principalConfirmedBy
        ) RETURNING *
    """)
    suspend fun add(scheduledJob: ScheduledJob): ScheduledJob

    @Query("SELECT * FROM scheduler.scheduled_jobs WHERE id = :id")
    suspend fun getById(id: UUID): ScheduledJob?

    @Query("SELECT * FROM scheduler.scheduled_jobs ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getAll(limit: Int, offset: Long): List<ScheduledJob>

    @Query("SELECT * FROM scheduler.scheduled_jobs WHERE enabled = :enabled ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getAllByEnabled(enabled: Boolean, limit: Int, offset: Long): List<ScheduledJob>

    @Query("SELECT * FROM scheduler.scheduled_jobs WHERE job_name = :jobName ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getByJobName(jobName: String, limit: Int, offset: Long): List<ScheduledJob>

    @Query("""
        SELECT * FROM scheduler.scheduled_jobs
        WHERE enabled = true
          AND principal_state IN ('not_required', 'active')
          AND next_run_at IS NOT NULL AND next_run_at <= :now
        ORDER BY next_run_at ASC
    """)
    suspend fun getDueJobs(now: OffsetDateTime): List<ScheduledJob>

    @Query("""
        UPDATE scheduler.scheduled_jobs SET
            name = :name, description = :description, job_name = :jobName,
            job_parameters = :jobParameters, cron_expression = :cronExpression, enabled = :enabled,
            allow_concurrent = :allowConcurrent, catch_up = :catchUp,
            max_catch_up = :maxCatchUp, updated_at = NOW(), next_run_at = :nextRunAt,
            execution_principal_id = :executionPrincipalId,
            principal_state = :principalState::scheduler.scheduled_job_principal_state,
            principal_assigned_by = :principalAssignedBy,
            principal_confirmed_by = :principalConfirmedBy
        WHERE id = :id RETURNING *
    """)
    suspend fun update(scheduledJob: ScheduledJob): ScheduledJob?

    @Query("UPDATE scheduler.scheduled_jobs SET enabled = true, updated_at = NOW() WHERE id = :id RETURNING *")
    suspend fun enable(id: UUID): ScheduledJob?

    @Query("UPDATE scheduler.scheduled_jobs SET enabled = false, updated_at = NOW() WHERE id = :id RETURNING *")
    suspend fun disable(id: UUID): ScheduledJob?

    @Query(
        """
        UPDATE scheduler.scheduled_jobs SET
            execution_principal_id = :principalId,
            principal_assigned_by = :assignedBy,
            principal_confirmed_by = :confirmedBy,
            principal_state = :state::scheduler.scheduled_job_principal_state,
            enabled = :enabled,
            updated_at = NOW()
        WHERE id = :id AND principal_state != 'not_required'
        RETURNING *
        """
    )
    suspend fun assignPrincipal(
        id: UUID,
        principalId: UUID,
        assignedBy: UUID,
        confirmedBy: UUID?,
        state: ScheduledJobPrincipalState,
        enabled: Boolean,
    ): ScheduledJob?

    @Query(
        """
        UPDATE scheduler.scheduled_jobs SET
            principal_confirmed_by = :confirmedBy,
            principal_state = 'active'::scheduler.scheduled_job_principal_state,
            enabled = true,
            updated_at = NOW()
        WHERE id = :id AND principal_state = 'pending_confirmation'
        RETURNING *
        """
    )
    suspend fun confirmPrincipal(id: UUID, confirmedBy: UUID): ScheduledJob?

    @Query(
        """
        UPDATE scheduler.scheduled_jobs SET
            execution_principal_id = null,
            principal_assigned_by = null,
            principal_confirmed_by = null,
            principal_state = 'needs_principal'::scheduler.scheduled_job_principal_state,
            enabled = false,
            updated_at = NOW()
        WHERE id = :id AND principal_state != 'not_required'
        RETURNING *
        """
    )
    suspend fun clearPrincipal(id: UUID): ScheduledJob?

    @Query(
        """
        UPDATE scheduler.scheduled_jobs SET
            principal_confirmed_by = null,
            principal_state = 'needs_principal'::scheduler.scheduled_job_principal_state,
            enabled = false,
            updated_at = NOW()
        WHERE id = :id AND principal_state != 'not_required'
        RETURNING *
        """
    )
    suspend fun parkNeedsPrincipal(id: UUID): ScheduledJob?

    @Query("UPDATE scheduler.scheduled_jobs SET last_run_at = :lastRunAt, next_run_at = :nextRunAt, updated_at = NOW() WHERE id = :id RETURNING *")
    suspend fun updateRunTimes(id: UUID, lastRunAt: OffsetDateTime, nextRunAt: OffsetDateTime?): ScheduledJob?

    @Query("DELETE FROM scheduler.scheduled_jobs WHERE id = :id")
    suspend fun deleteById(id: UUID)
}

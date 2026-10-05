package bosca.git.ci.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRunStatus
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@Repository
interface PipelineJobRepository {

    @Query("select * from git.pipeline_jobs where id = :id")
    suspend fun findById(id: UUID): PipelineJob?

    @Query("select * from git.pipeline_jobs where pipeline_run_id = :pipelineRunId order by name")
    suspend fun findByRun(pipelineRunId: UUID): List<PipelineJob>

    @Query("""
        insert into git.pipeline_jobs (
            pipeline_run_id, name, status, runner_label, matrix_values, artifacts,
            requirements, pipeline_requirements, requirements_satisfied_at, requirements_deadline,
            condition, condition_satisfied_at, environment, approval_required, depends_on, secrets, timeout_minutes
        )
        values (
            :pipelineRunId, :name, :status::git.pipeline_run_status, :runnerLabel,
            :matrixValues::jsonb, :artifacts::jsonb,
            :requirements::jsonb, :pipelineRequirements::jsonb, :requirementsSatisfiedAt, :requirementsDeadline,
            :condition, :conditionSatisfiedAt, :environment, :approvalRequired, :dependsOn, :secretNames, :timeoutMinutes
        )
        returning *
    """)
    suspend fun create(job: PipelineJob): PipelineJob

    /**
     * Stamps an approval-required job approved. Guarded on being queued and unapproved
     * so concurrent approvers race harmlessly — the second returns null.
     */
    @Query("""
        update git.pipeline_jobs
        set approved_at = now(), approved_by = :approvedBy, approval_comment = :comment
        where id = :id and status = 'queued' and approval_required = true and approved_at is null
        returning *
    """)
    suspend fun approve(id: UUID, approvedBy: UUID?, comment: String?): PipelineJob?

    /**
     * Terminal statuses are immutable: the guard keeps a late agent report (SUCCESS after the server
     * cancelled the job, RUNNING racing a cancellation) from resurrecting a settled row (run
     * control). Re-running is the only path back from terminal, via [resetForRerun].
     */
    @Query("""
        update git.pipeline_jobs
        set status = :status::git.pipeline_run_status, error_message = :errorMessage, finished = now()
        where id = :id and status in ('queued', 'running')
    """)
    suspend fun markFinished(id: UUID, status: PipelineRunStatus, errorMessage: String?)

    @Query("""
        update git.pipeline_jobs
        set status = :status::git.pipeline_run_status, error_message = :errorMessage, finished = now()
        where id = :id and status in ('queued', 'running')
        returning *
    """)
    suspend fun markFinishedIfActive(
        id: UUID,
        status: PipelineRunStatus,
        errorMessage: String?,
    ): PipelineJob?

    @Query("""
        update git.pipeline_jobs
        set status = :status::git.pipeline_run_status
        where id = :id and status in ('queued', 'running')
    """)
    suspend fun updateStatus(id: UUID, status: PipelineRunStatus)

    /**
     * Re-run-failed: resets a FAILED/CANCELLED job back to queued in place — same row,
     * next attempt — clearing its execution state. The requirement stamp is deliberately KEPT
     * (satisfied requirements stay satisfied); the deadline is refreshed by the caller so a job that
     * failed on a requirement deadline gets a new waiting window.
     */
    @Query("""
        update git.pipeline_jobs
        set status = 'queued'::git.pipeline_run_status, agent_id = null, error_message = null,
            started = null, finished = null, attempt = attempt + 1,
            requirements_deadline = :requirementsDeadline, condition_satisfied_at = null,
            approved_at = null, approved_by = null, approval_comment = null,
            kubernetes_dispatch_id = null, kubernetes_finalized_at = null
        where id = :id and status in ('failure', 'cancelled')
        returning *
    """)
    suspend fun resetForRerun(id: UUID, requirementsDeadline: OffsetDateTime?): PipelineJob?

    /**
     * A deferred-condition job (non-null `condition`, failure routing) is claimable only
     * once the server evaluated its condition true (`condition_satisfied_at`) — which happens after
     * its dependencies are terminal, in ANY status; an ordinary job keeps the all-dependencies-
     * succeeded rule.
     */
    @Query("""
        select j.* from git.pipeline_jobs j
        where j.status = 'queued' and j.runner_label = any(:labels)
          and j.kubernetes_dispatch_id is null
          and ((j.requirements = '[]'::jsonb and j.pipeline_requirements = '[]'::jsonb)
               or j.requirements_satisfied_at is not null)
          and (j.approval_required = false or j.approved_at is not null)
          and (
              (j.condition is not null and j.condition_satisfied_at is not null)
              or (j.condition is null and not exists (
                  select 1 from unnest(j.depends_on) as dep(name)
                  where not exists (
                      select 1 from git.pipeline_jobs d
                      where d.pipeline_run_id = j.pipeline_run_id
                        and d.name = dep.name
                        and d.status = 'success'
                  )
              ))
          )
        order by j.created
        limit 1
        for update skip locked
    """)
    suspend fun findNextAvailable(labels: List<String>): PipelineJob?

    /**
     * Locks the oldest claimable pipeline job that has not yet been handed to Kubernetes.
     *
     * This intentionally repeats [findNextAvailable]'s gates. The producer and exact-ID consumer
     * therefore agree on the moment a CI job is allowed to execute.
     */
    @Query("""
        select j.* from git.pipeline_jobs j
        where j.status = 'queued'
          and j.kubernetes_dispatch_id is null
          and j.runner_label = any(:profiles)
          and ((j.requirements = '[]'::jsonb and j.pipeline_requirements = '[]'::jsonb)
               or j.requirements_satisfied_at is not null)
          and (j.approval_required = false or j.approved_at is not null)
          and (
              (j.condition is not null and j.condition_satisfied_at is not null)
              or (j.condition is null and not exists (
                  select 1 from unnest(j.depends_on) as dep(name)
                  where not exists (
                      select 1 from git.pipeline_jobs d
                      where d.pipeline_run_id = j.pipeline_run_id
                        and d.name = dep.name
                        and d.status = 'success'
                  )
              ))
          )
        order by j.created
        limit 1
        for update skip locked
    """)
    suspend fun findNextKubernetesDispatchCandidate(profiles: List<String>): PipelineJob?

    /**
     * Stamps the durable queue item and reserves this attempt for its generated ephemeral agent.
     */
    @Query("""
        update git.pipeline_jobs
        set kubernetes_dispatch_id = :dispatchId, agent_id = :agentId
        where id = :id and status = 'queued' and kubernetes_dispatch_id is null
        returning *
    """)
    suspend fun markKubernetesDispatched(id: UUID, dispatchId: UUID, agentId: UUID): PipelineJob?

    /**
     * CI jobs whose durable Kubernetes lifecycle has not been transactionally finalized.
     *
     * The Kubernetes controller persists terminal startup/execution failures independently of
     * pub/sub, so this sweep is the recovery path across server restarts and missed notifications.
     */
    @Query("""
        select * from git.pipeline_jobs
        where kubernetes_dispatch_id is not null
          and kubernetes_finalized_at is null
        order by created
        limit :limit
    """)
    suspend fun findUnfinalizedKubernetesDispatched(limit: Int): List<PipelineJob>

    /**
     * Transactionally claims the terminal-result side effects for one Kubernetes-dispatched job.
     *
     * The returned row is non-null only for the replica that changed the marker. Callers perform
     * all CI/run/agent finalization in the same transaction, so rollback also releases the claim.
     */
    @Query("""
        update git.pipeline_jobs
        set kubernetes_finalized_at = now()
        where id = :id
          and kubernetes_dispatch_id is not null
          and kubernetes_finalized_at is null
        returning *
    """)
    suspend fun claimKubernetesFinalization(id: UUID): PipelineJob?

    /**
     * Stamps a deferred-condition job claimable after its condition evaluated true. Null-guarded so
     * concurrent finalizers race harmlessly.
     */
    @Query("""
        update git.pipeline_jobs
        set condition_satisfied_at = now()
        where id = :id and condition_satisfied_at is null
    """)
    suspend fun markConditionSatisfied(id: UUID)

    /**
     * The requirement checker's working set: queued jobs whose requirements — artifact
     * or pipeline — haven't been verified yet. The checker resolves each against the registry and the
     * upstream run history, and either stamps [markRequirementsSatisfied] or, past the deadline,
     * fails the job.
     */
    @Query("""
        select * from git.pipeline_jobs
        where status = 'queued' and requirements_satisfied_at is null
          and (requirements != '[]'::jsonb or pipeline_requirements != '[]'::jsonb)
        order by created
    """)
    suspend fun findAwaitingRequirements(): List<PipelineJob>

    /** One run's gated jobs — for the immediate post-creation check (requirements may already be met). */
    @Query("""
        select * from git.pipeline_jobs
        where pipeline_run_id = :pipelineRunId
          and status = 'queued' and requirements_satisfied_at is null
          and (requirements != '[]'::jsonb or pipeline_requirements != '[]'::jsonb)
        order by created
    """)
    suspend fun findAwaitingRequirementsByRun(pipelineRunId: UUID): List<PipelineJob>

    /**
     * Stamp a gated job dispatchable. Guarded on the stamp still being null so the event listener
     * and the sweep can race harmlessly — the second writer is a no-op.
     */
    @Query("""
        update git.pipeline_jobs
        set requirements_satisfied_at = now()
        where id = :id and requirements_satisfied_at is null
    """)
    suspend fun markRequirementsSatisfied(id: UUID)

    /**
     * Opens only the external requirement gate and records the explicit override. The queued and
     * unstamped guards make a claim/checker race fail loudly instead of rewriting job history.
     */
    @Query("""
        update git.pipeline_jobs
        set requirements_satisfied_at = now(),
            requirements_bypassed_at = now(),
            requirements_bypassed_by = :requestedBy,
            requirements_bypass_reason = :reason
        where id = :id and status = 'queued'
          and requirements_satisfied_at is null
          and (requirements != '[]'::jsonb or pipeline_requirements != '[]'::jsonb)
        returning *
    """)
    suspend fun bypassRequirements(id: UUID, requestedBy: UUID, reason: String?): PipelineJob?

    @Query("""
        update git.pipeline_jobs j
        set status = :status::git.pipeline_run_status,
            agent_id = :agentId,
            started = coalesce(started, now())
        where j.id = :id
          and (
            (
              j.status = 'queued'
              and (
                  j.kubernetes_dispatch_id is null
                  or (
                      j.kubernetes_dispatch_id is not null
                      and j.agent_id = :agentId
                  )
              )
              and ((j.requirements = '[]'::jsonb and j.pipeline_requirements = '[]'::jsonb)
                   or j.requirements_satisfied_at is not null)
              and (j.approval_required = false or j.approved_at is not null)
              and (
                  (j.condition is not null and j.condition_satisfied_at is not null)
                  or (j.condition is null and not exists (
                      select 1 from unnest(j.depends_on) as dep(name)
                      where not exists (
                          select 1 from git.pipeline_jobs d
                          where d.pipeline_run_id = j.pipeline_run_id
                            and d.name = dep.name
                            and d.status = 'success'
                      )
                  ))
              )
            )
            or (
              j.status = 'running'
              and cast(:previousAgentId as uuid) is not null
              and j.agent_id = :previousAgentId
            )
          )
        returning j.*
    """)
    suspend fun claimJobById(
        id: UUID,
        agentId: UUID,
        previousAgentId: UUID?,
        status: PipelineRunStatus,
    ): PipelineJob?

    @Query("select * from git.pipeline_jobs where agent_id = :agentId and status = 'running' order by started desc limit 1")
    suspend fun findCurrentByAgent(agentId: UUID): PipelineJob?

    @Query("select * from git.pipeline_jobs where agent_id = :agentId order by created desc limit :limit")
    suspend fun findByAgent(agentId: UUID, limit: Int): List<PipelineJob>

    @Query("""
        select pj.* from git.pipeline_jobs pj
        join git.pipeline_agents pa on pj.agent_id = pa.id
        where pj.status = 'running'
          and pa.last_heartbeat is not null
          and pa.last_heartbeat < now() - interval '5 minutes'
    """)
    suspend fun findOrphanedRunning(): List<PipelineJob>

    @Query("""
        select * from git.pipeline_jobs
        where status = 'running'
          and started is not null
          and started + (coalesce(timeout_minutes, 60) || ' minutes')::interval < now()
    """)
    suspend fun findTimedOutRunning(): List<PipelineJob>

    /**
     * Deferred-condition jobs are exempt (`condition is null` guard): a failed dependency is exactly
     * when a failure-routed job may need to run — its fate is decided by condition evaluation, not
     * by this sweep.
     */
    @Query("""
        update git.pipeline_jobs
        set status = 'cancelled'::git.pipeline_run_status, finished = now()
        where pipeline_run_id = :pipelineRunId
          and status = 'queued'
          and condition is null
          and exists (
              select 1 from unnest(depends_on) as dep(name)
              where exists (
                  select 1 from git.pipeline_jobs d
                  where d.pipeline_run_id = :pipelineRunId
                    and d.name = dep.name
                    and d.status in ('failure', 'cancelled')
              )
          )
        returning *
    """)
    suspend fun cancelBlockedJobs(pipelineRunId: UUID): List<PipelineJob>
}

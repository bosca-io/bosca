package bosca.kubernetes.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.kubernetes.model.KubernetesJobExecution
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Persists the durable lifecycle of requests dispatched through `kubernetes-jobs`. */
@Repository
interface KubernetesJobExecutionRepository {

    @Query("select * from kubernetes.job_execution where dispatch_id = :dispatchId")
    suspend fun getById(dispatchId: UUID): KubernetesJobExecution?

    @Query(
        """
        select * from kubernetes.job_execution
        where profile = :profile and idempotency_key = :idempotencyKey
        """
    )
    suspend fun findByIdempotencyKey(
        profile: String,
        idempotencyKey: String,
    ): KubernetesJobExecution?

    @Query(
        """
        insert into kubernetes.job_execution (dispatch_id, profile, idempotency_key, request)
        values (:dispatchId, :profile, :idempotencyKey, :request)
        on conflict (profile, idempotency_key) do update
        set profile = excluded.profile
        returning *
        """
    )
    suspend fun create(
        dispatchId: UUID,
        profile: String,
        idempotencyKey: String,
        request: JsonElement = JsonNull,
    ): KubernetesJobExecution

    @Query(
        """
        update kubernetes.job_execution
        set modified_at = now()
        where dispatch_id in (
            select dispatch_id
            from kubernetes.job_execution
            where status = 'queued'
              and published_at is null
              and modified_at < :before
            order by modified_at
            for update skip locked
            limit :limit
        )
        returning *
        """
    )
    suspend fun claimQueuedForPublication(
        before: OffsetDateTime,
        limit: Int,
    ): List<KubernetesJobExecution>

    @Query(
        """
        update kubernetes.job_execution
        set published_at = coalesce(published_at, now()),
            request = kubernetes.sanitize_job_request(request),
            modified_at = now()
        where dispatch_id = :dispatchId
          and status = 'queued'
          and published_at is null
        """
    )
    suspend fun markPublished(dispatchId: UUID)

    @Query(
        """
        update kubernetes.job_execution
        set modified_at = now()
        where dispatch_id = :dispatchId and status = 'queued'
        """
    )
    suspend fun touchQueued(dispatchId: UUID)

    @Query(
        """
        select * from kubernetes.job_execution
        where status in ('materialized', 'running')
        order by modified_at
        limit :limit
        """
    )
    suspend fun findMaterializedOrRunning(limit: Int): List<KubernetesJobExecution>

    @Query(
        """
        select * from kubernetes.job_execution
        where status = 'cancel_requested'
        order by created_at
        limit :limit
        """
    )
    suspend fun findCancellationRequested(limit: Int): List<KubernetesJobExecution>

    @Query(
        """
        update kubernetes.job_execution
        set status = 'cancel_requested',
            request = kubernetes.sanitize_job_request(request),
            modified_at = now()
        where dispatch_id = :dispatchId
          and status in ('queued', 'materialized', 'running', 'cancel_requested')
        returning *
        """
    )
    suspend fun requestCancellation(dispatchId: UUID): KubernetesJobExecution?

    @Query(
        """
        update kubernetes.job_execution
        set cluster_id = :clusterId,
            namespace = :namespace,
            job_name = :jobName,
            request = kubernetes.sanitize_job_request(request),
            status = case when status = 'queued' then 'materialized' else status end,
            materialized_at = coalesce(materialized_at, now()),
            modified_at = now()
        where dispatch_id = :dispatchId
        returning *
        """
    )
    suspend fun markMaterialized(
        dispatchId: UUID,
        clusterId: UUID,
        namespace: String,
        jobName: String,
    ): KubernetesJobExecution?

    @Query(
        """
        update kubernetes.job_execution
        set status = 'running',
            started_at = coalesce(started_at, now()),
            modified_at = now()
        where dispatch_id = :dispatchId
          and status in ('queued', 'materialized')
        returning *
        """
    )
    suspend fun markRunning(dispatchId: UUID): KubernetesJobExecution?

    @Query(
        """
        update kubernetes.job_execution
        set status = 'succeeded',
            message = null,
            request = kubernetes.sanitize_job_request(request),
            finished_at = coalesce(finished_at, now()),
            modified_at = now()
        where dispatch_id = :dispatchId
          and status in ('queued', 'materialized', 'running')
        returning *
        """
    )
    suspend fun markSucceeded(dispatchId: UUID): KubernetesJobExecution?

    @Query(
        """
        update kubernetes.job_execution
        set status = 'failed',
            message = :message,
            request = kubernetes.sanitize_job_request(request),
            finished_at = coalesce(finished_at, now()),
            modified_at = now()
        where dispatch_id = :dispatchId
          and status in ('queued', 'materialized', 'running')
        returning *
        """
    )
    suspend fun markFailed(dispatchId: UUID, message: String): KubernetesJobExecution?

    @Query(
        """
        update kubernetes.job_execution
        set status = 'cancelled',
            message = coalesce(message, 'Kubernetes Job was cancelled'),
            request = kubernetes.sanitize_job_request(request),
            finished_at = coalesce(finished_at, now()),
            modified_at = now()
        where dispatch_id = :dispatchId
          and status = 'cancel_requested'
        returning *
        """
    )
    suspend fun markCancelled(dispatchId: UUID): KubernetesJobExecution?
}

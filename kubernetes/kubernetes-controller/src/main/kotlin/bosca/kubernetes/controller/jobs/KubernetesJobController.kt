package bosca.kubernetes.controller.jobs

import bosca.db.withConnectionManager
import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.jobs.KubernetesJobQueueNames
import bosca.kubernetes.jobs.KubernetesJobRequest
import bosca.kubernetes.jobs.prepareDispatchJob
import bosca.kubernetes.model.KubernetesJobExecution
import bosca.kubernetes.model.KubernetesJobExecutionStatus
import bosca.kubernetes.repository.ClusterRepository
import bosca.kubernetes.repository.KubernetesJobExecutionRepository
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Consumes the single `kubernetes-jobs` queue and materializes each request as a Kubernetes Job.
 *
 * Queue acknowledgment happens only after the deterministic Job exists. A controller crash between
 * creation and acknowledgment is therefore safe: redelivery observes the same Job and acknowledges
 * the request without launching a duplicate workload.
 */
class KubernetesJobController(
    private val pool: ClusterClientPool,
    private val clusters: ClusterRepository,
    private val executions: KubernetesJobExecutionRepository,
    queueFactory: JobQueueFactory,
    private val distributedLockFactory: DistributedLockFactory,
    private val json: Json,
    private val intervalMillis: Long = 5_000L,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue: JobQueue = queueFactory.create(KubernetesJobQueueNames.queue)
    @Volatile private var loop: CoroutineJob? = null

    /** Starts the reconciliation loop. Repeated calls are idempotent. */
    fun start() {
        if (loop != null) return
        loop = scope.launch {
            log.info(
                "Kubernetes Job controller started (queue={}, interval={}ms)",
                KubernetesJobQueueNames.queue,
                intervalMillis,
            )
            while (isActive) {
                try {
                    reconcileAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Kubernetes Job reconciliation failed", e)
                }
                delay(intervalMillis.milliseconds)
            }
        }
    }

    /** Performs one complete reconciliation across all registered clusters. */
    suspend fun reconcileAll() {
        val profiles = loadProfiles()
        val lock = distributedLockFactory.create(KubernetesJobQueueNames.dispatch)
        if (!lock.tryAcquire(DISPATCH_LOCK_TTL_MILLIS)) {
            log.debug("Kubernetes Job dispatch reconciliation is already active")
            return
        }
        try {
            coroutineScope {
                val renewal = launch {
                    while (isActive) {
                        delay(DISPATCH_LOCK_RENEW_INTERVAL_MILLIS.milliseconds)
                        check(lock.renew(DISPATCH_LOCK_TTL_MILLIS)) {
                            "Lost the Kubernetes Job dispatch lock during reconciliation"
                        }
                    }
                }
                try {
                    reconcileQueue(profiles)
                } finally {
                    renewal.cancelAndJoin()
                }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { lock.release() }
                    .onFailure { log.warn("Failed releasing Kubernetes Job dispatch lock", it) }
            }
        }
    }

    private suspend fun reconcileQueue(profiles: List<ClusterProfile>) {
        recoverUnpublishedDispatches()
        val jobsByProfile = profiles.associateWith { managedJobs(it.client, it.profile) }
        val removedDispatches = reconcileCancellations(profiles, jobsByProfile)
        reconcileExecutions(jobsByProfile, removedDispatches)
        val activeCounts = jobsByProfile.mapValues { (_, jobs) ->
            jobs.count { job ->
                job.metadata?.deletionTimestamp == null &&
                    !job.isTerminal() &&
                    job.dispatchIdOrNull() !in removedDispatches
            }
        }.toMutableMap()

        // A single physical queue intentionally carries every profile. Scan beyond a full
        // profile's head item so capacity for another profile cannot sit idle behind it. The
        // bounded scan also runs when aggregate capacity is zero: otherwise a missing profile, or
        // a permanently full one, prevents published requests from ever reaching their dispatch
        // wait timeout.
        var examined = 0
        while (examined < MAX_QUEUE_ITEMS_PER_RECONCILE) {
            val queuedJob = queue.dequeue(QUEUE_WAIT) ?: break
            examined++
            try {
                if (!queue.checkin(queuedJob, JOB_LOCK_TTL_MILLIS)) {
                    throw LostQueueLockException(queuedJob.getId())
                }
                withConnectionManager { executions.touchQueued(queuedJob.getId()) }
                val request = json.decodeFromJsonElement(
                    KubernetesJobRequest.serializer(),
                    queuedJob.getDefinition(),
                )
                dispatch(queuedJob.getId(), request, profiles, activeCounts)
                if (!queue.checkin(queuedJob, JOB_LOCK_TTL_MILLIS)) {
                    throw LostQueueLockException(queuedJob.getId())
                }
                queue.markComplete(queuedJob)
            } catch (e: LostQueueLockException) {
                // Ownership may already have moved to another controller. It is not safe to mutate,
                // retry, or acknowledge the item from this stale delivery.
                log.warn(e.message)
            } catch (_: NoCapacityException) {
                queue.enqueueLater(queuedJob, intervalMillis.coerceAtLeast(1_000L).milliseconds)
            } catch (e: UnroutableDispatchException) {
                log.warn("Kubernetes Job queue item {} is no longer routable: {}", queuedJob.getId(), e.message)
                queue.markFailed(queuedJob, e, false)
                withConnectionManager {
                    executions.markFailed(queuedJob.getId(), requireNotNull(e.message))
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    runCatching {
                        queue.enqueueLater(queuedJob, intervalMillis.coerceAtLeast(1_000L).milliseconds)
                    }.onFailure {
                        log.warn("Failed requeuing Kubernetes Job queue item during shutdown", it)
                    }
                }
                throw e
            } catch (e: Exception) {
                log.warn("Failed dispatching Kubernetes Job queue item {}", queuedJob.getId(), e)
                queue.markFailed(queuedJob, e, true)
                if (queuedJob.isFullyComplete()) {
                    withConnectionManager {
                        executions.markFailed(
                            queuedJob.getId(),
                            "Kubernetes Job dispatch failed after exhausting retries: " +
                                (e.message ?: e::class.simpleName.orEmpty()),
                        )
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    runCatching { queue.releaseJobLock(queuedJob) }
                        .onFailure { log.warn("Failed releasing queue lock for {}", queuedJob.getId(), it) }
                }
            }
        }

        profiles.forEach { profile ->
            updateStatus(profile.client, profile.profile, activeCounts.getValue(profile))
        }
    }

    /**
     * Recovers durable QUEUED outbox records whose original after-commit publication may have
     * been lost. The queue's idempotent operation leaves an already-published item unchanged.
     */
    private suspend fun recoverUnpublishedDispatches() {
        val stale = withConnectionManager {
            executions.claimQueuedForPublication(
                OffsetDateTime.now().minusSeconds(PUBLICATION_RECOVERY_SECONDS),
                MAX_PUBLICATIONS_PER_RECONCILE,
            )
        }
        for (execution in stale) {
            val request = runCatching {
                json.decodeFromJsonElement(KubernetesJobRequest.serializer(), execution.request)
            }.getOrElse {
                withConnectionManager {
                    executions.markFailed(
                        execution.dispatchId,
                        "Persisted Kubernetes Job request could not be decoded: ${it.message}",
                    )
                }
                continue
            }
            if (
                OffsetDateTime.now().isAfter(
                    execution.createdAt.plusSeconds(request.dispatchWaitTimeoutSeconds)
                )
            ) {
                queue.markCancelled(execution.dispatchId)
                withConnectionManager {
                    executions.markFailed(
                        execution.dispatchId,
                        "Kubernetes Job request for profile '${request.profile}' was not " +
                            "materialized within ${request.dispatchWaitTimeoutSeconds} seconds",
                    )
                }
                continue
            }
            queue.enqueueIfAbsent(request.prepareDispatchJob(execution.dispatchId))
            withConnectionManager { executions.markPublished(execution.dispatchId) }
            log.info("Recovered durable Kubernetes Job dispatch {}", execution.dispatchId)
        }
    }

    private suspend fun dispatch(
        dispatchId: UUID,
        request: KubernetesJobRequest,
        profiles: List<ClusterProfile>,
        activeCounts: MutableMap<ClusterProfile, Int>,
    ) {
        request.validate()
        val execution = ensureExecution(dispatchId, request)
        check(execution.profile == request.profile && execution.idempotencyKey == request.idempotencyKey) {
            "Kubernetes Job execution $dispatchId conflicts with its queued request"
        }
        if (execution.status.isTerminal || execution.status == KubernetesJobExecutionStatus.CANCEL_REQUESTED) {
            if (execution.status == KubernetesJobExecutionStatus.CANCEL_REQUESTED) {
                withConnectionManager { executions.markCancelled(dispatchId) }
            }
            log.info("Skipping Kubernetes Job dispatch {} in state {}", dispatchId, execution.status)
            return
        }
        if (
            OffsetDateTime.now().isAfter(
                execution.createdAt.plusSeconds(request.dispatchWaitTimeoutSeconds)
            )
        ) {
            throw UnroutableDispatchException(
                "Kubernetes Job request for profile '${request.profile}' was not " +
                    "materialized within ${request.dispatchWaitTimeoutSeconds} seconds"
            )
        }
        val matching = profiles.filter { it.profile.name == request.profile }
        if (matching.isEmpty()) {
            throw NoCapacityException("No JobProfile named '${request.profile}'")
        }
        val jobName = kubernetesJobName(request.profile, request.idempotencyKey)

        for (candidate in matching) {
            val existing = getJob(candidate.client, candidate.profile.namespace, jobName) ?: continue
            check(isExpectedJob(existing, request, dispatchId)) {
                "Kubernetes Job ${candidate.profile.namespace}/$jobName has conflicting dispatch identity"
            }
            val materialized = markMaterialized(dispatchId, candidate, existing)
            if (materialized.status == KubernetesJobExecutionStatus.CANCEL_REQUESTED) {
                deleteJob(candidate.client, candidate.profile.namespace, jobName)
                releaseResultFinalizer(candidate.client, existing)
                withConnectionManager { executions.markCancelled(dispatchId) }
            }
            log.info(
                "Kubernetes Job {}/{} already exists for dispatch {}",
                candidate.profile.namespace,
                jobName,
                dispatchId,
            )
            return
        }

        val target = matching.firstOrNull {
            activeCounts.getValue(it) < it.profile.maxParallelism
        } ?: throw NoCapacityException("All JobProfile '${request.profile}' capacity is in use")

        val desired = buildKubernetesJob(target.profile, request, dispatchId)
        val created = try {
            createJob(target.client, target.profile.namespace, desired)
        } catch (e: KubernetesClientException) {
            if (e.code != 409) throw e
            val existing = getJob(target.client, target.profile.namespace, jobName) ?: throw e
            check(isExpectedJob(existing, request, dispatchId)) {
                "Kubernetes Job ${target.profile.namespace}/$jobName was created with conflicting dispatch identity"
            }
            existing
        }
        val materialized = markMaterialized(dispatchId, target, created)
        if (materialized.status == KubernetesJobExecutionStatus.CANCEL_REQUESTED) {
            deleteJob(target.client, target.profile.namespace, jobName)
            releaseResultFinalizer(target.client, created)
            withConnectionManager { executions.markCancelled(dispatchId) }
        } else {
            activeCounts[target] = activeCounts.getValue(target) + if (created.isTerminal()) 0 else 1
        }
        log.info(
            "Dispatched queue item {} as Kubernetes Job {}/{} using profile {}",
            dispatchId,
            target.profile.namespace,
            created.metadata?.name,
            request.profile,
        )
    }

    private suspend fun ensureExecution(
        dispatchId: UUID,
        request: KubernetesJobRequest,
    ): KubernetesJobExecution = withConnectionManager {
        executions.getById(dispatchId)
            ?: executions.create(
                dispatchId,
                request.profile,
                request.idempotencyKey,
                json.encodeToJsonElement(KubernetesJobRequest.serializer(), request),
            )
    }

    private suspend fun markMaterialized(
        dispatchId: UUID,
        profile: ClusterProfile,
        job: Job,
    ): KubernetesJobExecution = withConnectionManager {
        requireNotNull(
            executions.markMaterialized(
                dispatchId = dispatchId,
                clusterId = profile.clusterId,
                namespace = profile.profile.namespace,
                jobName = requireNotNull(job.metadata?.name) {
                    "Kubernetes API returned a Job without metadata.name"
                },
            )
        ) { "Kubernetes Job execution $dispatchId disappeared during materialization" }
    }

    /**
     * Deletes materialized jobs before queue dispatch is considered. Because this runs under the
     * same distributed lock as creation, a cancellation cannot be acknowledged and then recreated
     * later in this reconciliation.
     */
    private suspend fun reconcileCancellations(
        profiles: List<ClusterProfile>,
        jobsByProfile: Map<ClusterProfile, List<Job>>,
    ): MutableSet<UUID> {
        val requested = withConnectionManager {
            executions.findCancellationRequested(MAX_CANCELLATIONS_PER_RECONCILE)
        }
        if (requested.isEmpty()) return mutableSetOf()

        val observed = observedJobs(jobsByProfile).associateBy(ObservedJob::dispatchId)
        val removed = mutableSetOf<UUID>()
        for (execution in requested) {
            queue.markCancelled(execution.dispatchId)
            val known = observed[execution.dispatchId]
            if (known != null) {
                deleteJob(
                    known.profile.client,
                    known.profile.profile.namespace,
                    requireNotNull(known.job.metadata?.name),
                )
                releaseResultFinalizer(known.profile.client, known.job)
            } else {
                val clusterId = execution.clusterId
                val namespace = execution.namespace
                val jobName = execution.jobName
                if (clusterId != null && namespace != null && jobName != null) {
                    val client = profiles.firstOrNull { it.clusterId == clusterId }?.client
                        ?: withConnectionManager { pool.get(clusterId) }
                    getJob(client, namespace, jobName)?.let {
                        deleteJob(client, namespace, jobName)
                        releaseResultFinalizer(client, it)
                    }
                }
            }
            withConnectionManager { executions.markCancelled(execution.dispatchId) }
            removed += execution.dispatchId
            log.info("Cancelled Kubernetes Job dispatch {}", execution.dispatchId)
        }
        return removed
    }

    /** Persists Kubernetes Job and pod outcomes for durable consumption by the dispatch owner. */
    private suspend fun reconcileExecutions(
        jobsByProfile: Map<ClusterProfile, List<Job>>,
        removedDispatches: MutableSet<UUID>,
    ) {
        val observedJobs = observedJobs(jobsByProfile)
        val observedDispatches = observedJobs.mapTo(mutableSetOf(), ObservedJob::dispatchId)
        for (observedJob in observedJobs) {
            if (observedJob.dispatchId in removedDispatches) continue
            reconcileExecution(observedJob, removedDispatches)
        }
        reconcileMissingMaterializedExecutions(observedDispatches, removedDispatches)
    }

    private fun observedJobs(
        jobsByProfile: Map<ClusterProfile, List<Job>>,
    ): List<ObservedJob> = jobsByProfile.flatMap { (profile, jobs) ->
        jobs.mapNotNull { job ->
            job.dispatchIdOrNull()?.let { ObservedJob(profile, job, it) }
        }
    }

    private suspend fun reconcileExecution(
        observed: ObservedJob,
        removedDispatches: MutableSet<UUID>,
    ) {
        val profile = observed.profile
        val job = observed.job
        val dispatchId = observed.dispatchId
        val idempotencyKey = job.metadata?.annotations?.get(IDEMPOTENCY_KEY_ANNOTATION) ?: return
        val existingExecution = withConnectionManager {
            executions.getById(dispatchId)
                ?: executions.create(
                    dispatchId,
                    profile.profile.name,
                    idempotencyKey,
                    json.encodeToJsonElement(
                        KubernetesJobRequest.serializer(),
                        KubernetesJobRequest(profile.profile.name, idempotencyKey),
                    ),
                )
        }
        val execution = markMaterialized(existingExecution.dispatchId, profile, job)
        if (
            job.metadata?.deletionTimestamp != null &&
            !job.isTerminal() &&
            !execution.status.isTerminal
        ) {
            withConnectionManager {
                executions.markFailed(
                    dispatchId,
                    "Kubernetes Job was deleted before reaching a terminal state",
                )
            }
            releaseResultFinalizer(profile.client, job)
            removedDispatches += dispatchId
            return
        }
        reconcileExecutionStatus(observed, execution, removedDispatches)
    }

    private suspend fun reconcileExecutionStatus(
        observed: ObservedJob,
        execution: KubernetesJobExecution,
        removedDispatches: MutableSet<UUID>,
    ) {
        when (execution.status) {
            KubernetesJobExecutionStatus.CANCEL_REQUESTED,
            KubernetesJobExecutionStatus.CANCELLED,
            -> reconcileCancelledExecution(observed, execution.status, removedDispatches)

            KubernetesJobExecutionStatus.FAILED ->
                reconcileFailedExecution(observed, removedDispatches)

            KubernetesJobExecutionStatus.SUCCEEDED ->
                releaseResultFinalizer(observed.profile.client, observed.job)

            else -> reconcileActiveExecution(observed, execution, removedDispatches)
        }
    }

    private suspend fun reconcileCancelledExecution(
        observed: ObservedJob,
        status: KubernetesJobExecutionStatus,
        removedDispatches: MutableSet<UUID>,
    ) {
        deleteJob(
            observed.profile.client,
            observed.profile.profile.namespace,
            requireNotNull(observed.job.metadata?.name),
        )
        releaseResultFinalizer(observed.profile.client, observed.job)
        if (status == KubernetesJobExecutionStatus.CANCEL_REQUESTED) {
            withConnectionManager { executions.markCancelled(observed.dispatchId) }
        }
        removedDispatches += observed.dispatchId
    }

    private suspend fun reconcileFailedExecution(
        observed: ObservedJob,
        removedDispatches: MutableSet<UUID>,
    ) {
        if (!observed.job.isTerminal()) {
            deleteJob(
                observed.profile.client,
                observed.profile.profile.namespace,
                requireNotNull(observed.job.metadata?.name),
            )
        }
        releaseResultFinalizer(observed.profile.client, observed.job)
        removedDispatches += observed.dispatchId
    }

    private suspend fun reconcileActiveExecution(
        observed: ObservedJob,
        execution: KubernetesJobExecution,
        removedDispatches: MutableSet<UUID>,
    ) {
        val jobFailure = observed.job.failureMessage()
        if (jobFailure != null) {
            val failure = listJobPods(
                observed.profile.client,
                observed.profile.profile.namespace,
                requireNotNull(observed.job.metadata?.name),
            ).firstNotNullOfOrNull(Pod::runtimeFailureMessage) ?: jobFailure
            withConnectionManager { executions.markFailed(observed.dispatchId, failure) }
            releaseResultFinalizer(observed.profile.client, observed.job)
            removedDispatches += observed.dispatchId
            return
        }
        if (observed.job.isComplete()) {
            withConnectionManager { executions.markSucceeded(observed.dispatchId) }
            releaseResultFinalizer(observed.profile.client, observed.job)
            removedDispatches += observed.dispatchId
            return
        }
        reconcileRunningExecution(observed, execution, removedDispatches)
    }

    private suspend fun reconcileRunningExecution(
        observed: ObservedJob,
        execution: KubernetesJobExecution,
        removedDispatches: MutableSet<UUID>,
    ) {
        val profile = observed.profile
        val job = observed.job
        val pods = listJobPods(
            profile.client,
            profile.profile.namespace,
            requireNotNull(job.metadata?.name),
        )
        val startupFailure = pods.firstNotNullOfOrNull(Pod::startupFailureMessage)
        val graceElapsed = OffsetDateTime.now().isAfter(
            requireNotNull(execution.materializedAt) {
                "Materialized Kubernetes Job execution ${observed.dispatchId} has no materialized timestamp"
            }.plusSeconds(profile.profile.startupFailureGraceSeconds)
        )
        if (startupFailure != null && graceElapsed) {
            withConnectionManager {
                executions.markFailed(observed.dispatchId, startupFailure)
            }
            deleteJob(
                profile.client,
                profile.profile.namespace,
                requireNotNull(job.metadata?.name),
            )
            releaseResultFinalizer(profile.client, job)
            removedDispatches += observed.dispatchId
            log.warn(
                "Kubernetes Job {}/{} failed to start: {}",
                profile.profile.namespace,
                job.metadata?.name,
                startupFailure,
            )
        } else if (
            (job.status?.active ?: 0) > 0 ||
            pods.any { it.status?.phase == "Running" || it.status?.phase == "Succeeded" }
        ) {
            withConnectionManager { executions.markRunning(observed.dispatchId) }
        }
    }

    /**
     * Resolves executions whose profile or Job disappeared between list operations. A finalizer
     * normally preserves the Job until its result is persisted; this fallback prevents an
     * externally force-deleted object from leaving the Bosca execution active forever.
     */
    private suspend fun reconcileMissingMaterializedExecutions(
        observedDispatches: Set<UUID>,
        removedDispatches: MutableSet<UUID>,
    ) {
        val active = withConnectionManager {
            executions.findMaterializedOrRunning(MAX_MISSING_EXECUTIONS_PER_RECONCILE)
        }
        for (execution in active) {
            if (execution.dispatchId in observedDispatches || execution.dispatchId in removedDispatches) {
                continue
            }
            val clusterId = execution.clusterId ?: continue
            val namespace = execution.namespace ?: continue
            val jobName = execution.jobName ?: continue
            val client = try {
                withConnectionManager { pool.get(clusterId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Cannot inspect missing Kubernetes Job dispatch {}", execution.dispatchId, e)
                continue
            }
            val job = try {
                getJob(client, namespace, jobName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Cannot inspect Kubernetes Job {}/{}", namespace, jobName, e)
                continue
            }
            when {
                job == null -> withConnectionManager {
                    executions.markFailed(
                        execution.dispatchId,
                        "Kubernetes Job $namespace/$jobName disappeared before its result was persisted",
                    )
                }

                job.failureMessage() != null -> {
                    withConnectionManager {
                        executions.markFailed(execution.dispatchId, requireNotNull(job.failureMessage()))
                    }
                    releaseResultFinalizer(client, job)
                }

                job.isComplete() -> {
                    withConnectionManager { executions.markSucceeded(execution.dispatchId) }
                    releaseResultFinalizer(client, job)
                }

                job.metadata?.deletionTimestamp != null -> {
                    withConnectionManager {
                        executions.markFailed(
                            execution.dispatchId,
                            "Kubernetes Job $namespace/$jobName was deleted before completion",
                        )
                    }
                    releaseResultFinalizer(client, job)
                }

                else -> continue
            }
            removedDispatches += execution.dispatchId
        }
    }

    private suspend fun loadProfiles(): List<ClusterProfile> {
        val registered = withConnectionManager { clusters.list() }
        val profiles = mutableListOf<ClusterProfile>()
        for (cluster in registered) {
            val client = try {
                withConnectionManager { pool.get(cluster.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Cannot load cluster {} while reconciling Kubernetes Jobs", cluster.id, e)
                continue
            }
            val resources = try {
                listProfiles(client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Cannot list JobProfile resources on cluster {}", cluster.id, e)
                continue
            }
            resources.forEach { resource ->
                runCatching { JobProfile.from(resource) }
                    .onSuccess { profiles += ClusterProfile(cluster.id, client, it) }
                    .onFailure {
                        log.warn(
                            "Invalid JobProfile {}/{} on cluster {}: {}",
                            resource.metadata?.namespace,
                            resource.metadata?.name,
                            cluster.id,
                            it.message,
                        )
                    }
            }
        }
        return profiles.sortedWith(profileOrder)
    }

    private suspend fun listProfiles(client: KubernetesClient): List<GenericKubernetesResource> =
        withContext(Dispatchers.IO) {
            try {
                client.genericKubernetesResources(RESOURCE_CONTEXT)
                    .inAnyNamespace()
                    .list()
                    .items
                    .orEmpty()
            } catch (e: KubernetesClientException) {
                if (e.code == 404) emptyList() else throw e
            }
        }

    private suspend fun managedJobs(client: KubernetesClient, profile: JobProfile): List<Job> =
        withContext(Dispatchers.IO) {
            client.batch().v1().jobs()
                .inNamespace(profile.namespace)
                .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .withLabel(PROFILE_LABEL, kubernetesLabelValue(profile.name))
                .withLabel(PROFILE_UID_LABEL, kubernetesLabelValue(profile.uid))
                .list()
                .items
                .orEmpty()
        }

    private suspend fun getJob(client: KubernetesClient, namespace: String, name: String): Job? =
        withContext(Dispatchers.IO) {
            client.batch().v1().jobs().inNamespace(namespace).withName(name).get()
        }

    private suspend fun createJob(client: KubernetesClient, namespace: String, job: Job): Job =
        withContext(Dispatchers.IO) {
            client.batch().v1().jobs().inNamespace(namespace).resource(job).create()
        }

    private suspend fun deleteJob(client: KubernetesClient, namespace: String, name: String) {
        withContext(Dispatchers.IO) {
            client.batch().v1().jobs()
                .inNamespace(namespace)
                .withName(name)
                .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                .delete()
        }
    }

    private suspend fun releaseResultFinalizer(client: KubernetesClient, job: Job) {
        if (JOB_RESULT_FINALIZER !in job.metadata?.finalizers.orEmpty()) return
        val namespace = requireNotNull(job.metadata?.namespace)
        val name = requireNotNull(job.metadata?.name)
        withContext(Dispatchers.IO) {
            try {
                client.batch().v1().jobs()
                    .inNamespace(namespace)
                    .withName(name)
                    .edit { current ->
                        JobBuilder(current)
                            .editMetadata()
                            .withFinalizers(
                                current.metadata?.finalizers.orEmpty()
                                    .filterNot { it == JOB_RESULT_FINALIZER }
                            )
                            .endMetadata()
                            .build()
                    }
            } catch (e: KubernetesClientException) {
                if (e.code != 404) throw e
            }
        }
    }

    private suspend fun listJobPods(
        client: KubernetesClient,
        namespace: String,
        jobName: String,
    ): List<Pod> = withContext(Dispatchers.IO) {
        client.pods()
            .inNamespace(namespace)
            .withLabel("job-name", jobName)
            .list()
            .items
            .orEmpty()
    }

    private suspend fun updateStatus(
        client: KubernetesClient,
        profile: JobProfile,
        active: Int,
    ) {
        withContext(Dispatchers.IO) {
            profile.resource.additionalProperties["status"] = mapOf(
                "observedGeneration" to (profile.resource.metadata?.generation ?: 0),
                "activeJobs" to active,
                "lastReconciledAt" to Instant.now().toString(),
                "conditions" to listOf(
                    mapOf(
                        "type" to "Ready",
                        "status" to "True",
                        "reason" to "Reconciled",
                        "message" to "Kubernetes Job profile is available",
                    )
                ),
            )
            try {
                client.resource(profile.resource).patchStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(
                    "Failed updating JobProfile status {}/{}: {}",
                    profile.namespace,
                    profile.name,
                    e.message,
                )
            }
        }
    }

    override fun close() {
        loop?.cancel()
        loop = null
    }

    /**
     * Identity key for one loaded profile. This deliberately uses reference equality: [profile]
     * contains the mutable Fabric8 resource whose status is patched during reconciliation, so
     * structural hash codes would make it unsafe as an active-count map key.
     */
    private class ClusterProfile(
        val clusterId: UUID,
        val client: KubernetesClient,
        val profile: JobProfile,
    )

    private data class ObservedJob(
        val profile: ClusterProfile,
        val job: Job,
        val dispatchId: UUID,
    )

    private class NoCapacityException(message: String) : Exception(message)
    private class UnroutableDispatchException(message: String) : Exception(message)
    private class LostQueueLockException(dispatchId: UUID) :
        Exception("Lost ownership of Kubernetes Job queue item $dispatchId before dispatch")

    companion object {
        private val log = LoggerFactory.getLogger(KubernetesJobController::class.java)
        private const val DISPATCH_LOCK_TTL_MILLIS = 300_000L
        private const val DISPATCH_LOCK_RENEW_INTERVAL_MILLIS = 60_000L
        private const val JOB_LOCK_TTL_MILLIS = 300_000L
        private const val MAX_QUEUE_ITEMS_PER_RECONCILE = 100
        private const val MAX_CANCELLATIONS_PER_RECONCILE = 1_000
        private const val MAX_MISSING_EXECUTIONS_PER_RECONCILE = 1_000
        private const val MAX_PUBLICATIONS_PER_RECONCILE = 100
        private const val PUBLICATION_RECOVERY_SECONDS = 60L
        private val QUEUE_WAIT = 250.milliseconds
        private val RESOURCE_CONTEXT = ResourceDefinitionContext.Builder()
            .withGroup(API_GROUP)
            .withVersion(API_VERSION_NAME)
            .withKind(KIND)
            .withPlural(PLURAL)
            .withNamespaced(true)
            .build()
        private val profileOrder = compareBy<ClusterProfile>(
            { it.profile.name },
            { it.clusterId.toString() },
            { it.profile.namespace },
            { it.profile.uid },
        )
    }
}

internal fun Job.isTerminal(): Boolean = status?.conditions.orEmpty().any {
    it.status == "True" && (it.type == "Complete" || it.type == "Failed")
}

internal fun Job.isComplete(): Boolean = status?.conditions.orEmpty().any {
    it.status == "True" && it.type == "Complete"
}

internal fun Job.failureMessage(): String? {
    val condition = status?.conditions.orEmpty().firstOrNull {
        it.status == "True" && it.type == "Failed"
    } ?: return null
    return condition.message?.takeIf(String::isNotBlank)
        ?: condition.reason?.takeIf(String::isNotBlank)
        ?: "Kubernetes Job failed"
}

internal fun Job.dispatchIdOrNull(): UUID? {
    val value = metadata?.annotations?.get(DISPATCH_ID_ANNOTATION) ?: return null
    return runCatching { UUID.parse(value) }.getOrNull()
}

private val STARTUP_FAILURE_REASONS = setOf(
    "CrashLoopBackOff",
    "ImagePullBackOff",
    "ErrImagePull",
    "CreateContainerConfigError",
    "CreateContainerError",
    "RunContainerError",
    "InvalidImageName",
)

internal fun Pod.startupFailureMessage(): String? {
    val statuses = status?.initContainerStatuses.orEmpty() + status?.containerStatuses.orEmpty()
    val waiting = statuses.asSequence()
        .mapNotNull { it.state?.waiting }
        .firstOrNull { it.reason in STARTUP_FAILURE_REASONS }
        ?: return null
    val podName = metadata?.name ?: "unknown pod"
    val detail = waiting.message?.takeIf(String::isNotBlank)
    val reason = requireNotNull(waiting.reason)
    return buildString {
        append("Pod ")
        append(podName)
        append(" failed to start: ")
        append(reason)
        if (detail != null) {
            append(" — ")
            append(detail)
        }
    }
}

internal fun Pod.runtimeFailureMessage(): String? {
    val failed = (status?.containerStatuses.orEmpty() + status?.initContainerStatuses.orEmpty())
        .firstOrNull { container ->
            val terminated = container.state?.terminated
            terminated != null && terminated.exitCode != 0
        }
        ?: return null
    val terminated = requireNotNull(failed.state?.terminated)
    val podName = metadata?.name ?: "unknown pod"
    val containerName = failed.name?.takeIf(String::isNotBlank) ?: "unknown container"
    val reason = terminated.reason?.takeIf(String::isNotBlank)
    val detail = terminated.message?.takeIf(String::isNotBlank)
    return buildString {
        append("Pod ")
        append(podName)
        append(" container ")
        append(containerName)
        append(" failed")
        if (reason != null) {
            append(": ")
            append(reason)
        }
        append(" (exit code ")
        append(terminated.exitCode)
        append(")")
        if (detail != null) {
            append(" — ")
            append(detail)
        }
    }
}

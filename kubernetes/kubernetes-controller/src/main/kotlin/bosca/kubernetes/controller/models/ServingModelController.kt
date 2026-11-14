package bosca.kubernetes.controller.models

import bosca.db.withConnectionManager
import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.repository.ClusterRepository
import bosca.kubernetes.service.withServingModelLock
import bosca.kubernetes.service.ServingModelLease
import bosca.lock.DistributedLockFactory
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.DeletableWithOptions
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** Reconciles long-lived ServingModels independently of Kubernetes Jobs and the dispatch queue. */
class ServingModelController(
    private val pool: ClusterClientPool,
    private val clusters: ClusterRepository,
    private val discovery: ServingModelDiscovery,
    private val lockFactory: DistributedLockFactory,
    private val intervalMillis: Long = 5_000,
    private val now: () -> Instant = Instant::now,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    /** Starts periodic reconciliation; existing resource identities survive process restarts. */
    fun start() {
        if (loop != null) return
        loop = scope.launch {
            while (isActive) {
                try {
                    reconcileAll()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    log.warn("ServingModel reconciliation failed", exception)
                }
                delay(intervalMillis)
            }
        }
    }

    /** Discovers profiles on registered clusters, isolating recoverable failures by cluster and profile. */
    suspend fun reconcileAll() {
        val registered = withConnectionManager { clusters.list() }
        for (cluster in registered) {
            try {
                val client = withConnectionManager { pool.get(cluster.id) }
                val profiles = withContext(Dispatchers.IO) {
                    try {
                        client.genericKubernetesResources(SERVING_PROFILES).inAnyNamespace().list().items.orEmpty()
                    } catch (exception: KubernetesClientException) {
                        if (exception.code == 404) emptyList() else throw exception
                    }
                }
                for (resource in profiles) {
                    try {
                        reconcile(client, ServingModelProfile(resource))
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        log.warn("ServingModelProfile {}/{} reconciliation failed; preserving serving resources",
                            resource.metadata.namespace, resource.metadata.name, exception)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                log.warn("Cannot reconcile ServingModels on cluster {}", cluster.id, exception)
            }
        }
    }

    /** Reconciles only after a complete authenticated discovery; discovery failures cannot retire models. */
    suspend fun reconcile(client: KubernetesClient, profile: ServingModelProfile) {
        withServingModelLock(lockFactory) { lease -> reconcileLocked(client, profile, lease) }
    }

    private suspend fun reconcileLocked(client: KubernetesClient, profile: ServingModelProfile, lease: ServingModelLease) {
        val secret = lease.blocking { client.secrets().inNamespace(profile.namespace).withName(profile.tokenSecretName).get() }
            ?: error("Serving discovery token Secret not found")
        val token = String(Base64.getDecoder().decode(requireNotNull(secret.data[profile.tokenSecretKey])), Charsets.UTF_8)
        val (contexts, exports) = discovery.discover(profile, token)
        val active = contexts.mapNotNull { it.activeModelVersion }.toSet()
        check(active.all { version -> exports.any { it.version == version } }) { "Discovery omitted an active export" }
        check(contexts.mapNotNull { it.requestedModelVersion }.all { version -> exports.any { it.version == version } }) {
            "Discovery omitted a requested export"
        }
        val candidates = contexts.mapNotNull { context ->
            val available = exports.filter { it.contextId == context.id }
            available.firstOrNull { it.version == context.requestedModelVersion }
                ?: available.filter { it.status == "RUNNING" }.minByOrNull { it.version }
        }.filter { it.version !in active }
        val desired = (exports.filter { it.version in active } + candidates).associateBy { it.version }
        val revisions = desired.mapValues { (_, export) ->
            contexts.firstOrNull { it.id == export.contextId && it.requestedModelVersion == export.version }
                ?.selectionRevision ?: export.selectionRevision
        }
        val models = lease.blocking {
            client.genericKubernetesResources(SERVING_MODELS).inNamespace(profile.namespace)
                .withLabel(SERVING_PROFILE_LABEL, profile.uid).list().items.orEmpty().toMutableList()
        }

        for ((version, export) in desired) {
            if (models.none { version(it) == version }) {
                val expected = buildServingModel(profile, export)
                val model = createOwned(client, expected, profile.uid, lease)
                models += model
            }
        }
        val pods = lease.blocking {
            client.pods().inNamespace(profile.namespace).list().items.orEmpty().associateBy { it.metadata.name }
        }
        var occupied = models.count { model -> version(model) !in active && model.metadata.name in pods }
        for (model in models.sortedBy { version(it) }) {
            val resources = ServingModelResources(model)
            val activeModel = resources.version in active
            // Foreground deletion keeps the capacity slot until owned Pods finish terminating.
            // A newly requested instance with the same name is created after this one is gone.
            if (model.metadata.deletionTimestamp != null) continue
            if (resources.version !in desired) {
                drain(client, profile, model, resources, lease)
                continue
            }
            if (model.metadata.annotations.orEmpty().containsKey(DRAIN_SINCE)) {
                model.metadata.annotations = model.metadata.annotations.orEmpty() - DRAIN_SINCE
                model.metadata = lease.blocking { client.resource(model).update().metadata }
            }
            val existingPod = pods[resources.name]
            if (existingPod == null && !activeModel && occupied >= profile.maxCandidates) {
                status(client, model, "Pending", "Waiting for candidate capacity", resources.endpoint, lease)
                continue
            }
            createOwned(client, resources.configMap(), resources.uid, lease)
            createOwned(client, resources.service(), resources.uid, lease)
            if (existingPod != null && !resources.owns(existingPod)) error("ServingModel Pod has another owner")
            val pod = existingPod ?: createOwned(client, resources.pod(), resources.uid, lease).also {
                if (!activeModel) occupied++
            }
            val ready = pod.metadata.deletionTimestamp == null &&
                pod.status?.conditions.orEmpty().any { it.type == "Ready" && it.status == "True" }
            val terminated = pod.status?.phase in listOf("Failed", "Succeeded")
            val phase = when {
                ready && activeModel -> "Active"
                ready -> "Ready"
                pod.metadata.deletionTimestamp != null -> "Pending"
                terminated -> "Failed"
                pod.status?.phase == "Pending" && pod.status?.conditions.orEmpty().none {
                    it.type == "PodScheduled" && it.status == "True"
                } -> "Pending"
                pod.status?.initContainerStatuses.orEmpty().plus(pod.status?.containerStatuses.orEmpty()).any {
                    it.state?.waiting?.reason in listOf("CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull") ||
                        (it.state?.terminated?.exitCode?.let { code -> code != 0 } == true)
                } -> "Failed"
                pod.status?.initContainerStatuses.orEmpty().any { it.state?.terminated?.exitCode != 0 } -> "Downloading"
                else -> "Loading"
            }
            var annotations = model.metadata.annotations.orEmpty()
            val revision = revisions.getValue(resources.version).toString()
            if (!activeModel && annotations[SELECTION_REVISION] != revision) {
                annotations = annotations - FAILURE_SINCE - STARTUP_SINCE - PREPARED_REVISION +
                    (SELECTION_REVISION to revision)
            }
            if (ready) {
                annotations = annotations - FAILURE_SINCE - STARTUP_SINCE
            } else if (!activeModel) {
                if (STARTUP_SINCE !in annotations) annotations = annotations + (STARTUP_SINCE to now().toString())
                if (phase == "Failed" && FAILURE_SINCE !in annotations) annotations = annotations + (FAILURE_SINCE to now().toString())
            }
            if (annotations != model.metadata.annotations.orEmpty()) {
                model.metadata.annotations = annotations
                model.metadata = lease.blocking { client.resource(model).update().metadata }
            }
            if (model.metadata.deletionTimestamp != null) continue
            if (ready && !activeModel && annotations[PREPARED_REVISION] != revision) {
                // Recording this selection revision fences deletes captured before the new intent.
                // Notification follows that write and can be retried safely after a restart.
                val controlToken = controlToken(client, profile, lease)
                lease.ensureHeld()
                if (discovery.prepareModel(profile, controlToken, resources.version, revisions.getValue(resources.version), resources.endpoint)) {
                    model.metadata.annotations = annotations + (PREPARED_REVISION to revision)
                    model.metadata = lease.blocking { client.resource(model).update().metadata }
                }
            }
            if (!ready && !activeModel) {
                val failureSince = annotations[FAILURE_SINCE]?.let(Instant::parse)
                val startupSince = annotations[STARTUP_SINCE]?.let(Instant::parse)
                val startupExpired = startupSince != null && !now().isBefore(startupSince.plusSeconds(profile.startupTimeoutSeconds))
                val failureExpired = failureSince != null && !now().isBefore(failureSince.plusSeconds(profile.failureGraceSeconds))
                if (startupExpired || failureExpired) {
                    val controlToken = controlToken(client, profile, lease)
                    val failure = if (startupExpired) "Serving candidate exceeded startupTimeoutSeconds (${profile.startupTimeoutSeconds})"
                        else podMessage(pod).ifBlank { "Serving candidate did not recover" }
                    lease.ensureHeld()
                    if (discovery.failModel(profile, controlToken, resources.version, revisions.getValue(resources.version), failure)) {
                        status(client, model, "Failed", "Terminal serving failure reported", resources.endpoint, lease)
                        deleteModel(client, model, lease)
                        continue
                    }
                }
            }
            if (terminated) {
                // Recreated Pods retain the candidate's startup and failure deadlines.
                lease.blocking { client.resource(pod).lockResourceVersion().delete() }
                status(client, model, "Pending", pod.status?.message ?: "Recreating terminated Pod", resources.endpoint, lease)
            } else {
                status(client, model, phase, podMessage(pod), resources.endpoint, lease)
            }
        }
    }

    private suspend fun controlToken(client: KubernetesClient, profile: ServingModelProfile, lease: ServingModelLease): String {
        val secret = lease.blocking {
            client.secrets().inNamespace(profile.namespace).withName(profile.controlTokenSecretName).get()
        } ?: error("Serving control token Secret not found")
        return String(Base64.getDecoder().decode(requireNotNull(secret.data[profile.controlTokenSecretKey])), Charsets.UTF_8)
    }

    private suspend fun drain(client: KubernetesClient, profile: ServingModelProfile, model: GenericKubernetesResource, resources: ServingModelResources, lease: ServingModelLease) {
        val since = model.metadata.annotations.orEmpty()[DRAIN_SINCE]?.let(Instant::parse)
        if (since == null) {
            model.metadata.annotations = model.metadata.annotations.orEmpty() + (DRAIN_SINCE to now().toString())
            model.metadata = lease.blocking { client.resource(model).update().metadata }
            status(client, model, "Draining", "Generation is no longer selected", resources.endpoint, lease)
        } else if (!now().isBefore(since.plusSeconds(profile.drainSeconds))) {
            // Ownership garbage collection removes only this instance's Pod, Service and config.
            deleteModel(client, model, lease)
        } else {
            status(client, model, "Draining", "Generation is no longer selected", resources.endpoint, lease)
        }
    }

    private suspend fun status(client: KubernetesClient, model: GenericKubernetesResource, phase: String, message: String, endpoint: String, lease: ServingModelLease) {
        val previous = model.additionalProperties["status"] as? Map<*, *>
        if (previous?.get("phase") == phase && previous["message"] == message && previous["endpoint"] == endpoint) return
        model.additionalProperties["status"] = mapOf("phase" to phase, "message" to message, "endpoint" to endpoint,
            "observedGeneration" to model.metadata.generation, "lastTransitionTime" to now().toString())
        model.metadata = lease.blocking { client.resource(model).patchStatus().metadata }
    }

    private fun podMessage(pod: Pod): String = pod.status?.initContainerStatuses.orEmpty()
        .plus(pod.status?.containerStatuses.orEmpty()).firstNotNullOfOrNull {
            it.state?.waiting?.message ?: it.state?.terminated?.message
        } ?: pod.status?.message ?: pod.status?.conditions.orEmpty().firstOrNull { it.status == "False" }?.message.orEmpty()

    private fun version(model: GenericKubernetesResource): Long =
        ((model.additionalProperties["spec"] as Map<*, *>)["version"] as Number).toLong()

    private suspend fun deleteModel(client: KubernetesClient, model: GenericKubernetesResource, lease: ServingModelLease) = lease.blocking {
        val locked = client.resource(model).lockResourceVersion()
        // The locked operation retains deletion options although its fluent return type narrows them.
        (locked as DeletableWithOptions).withPropagationPolicy(DeletionPropagation.FOREGROUND).delete()
    }

    private suspend fun <T : HasMetadata> createOwned(client: KubernetesClient, expected: T, ownerUid: String, lease: ServingModelLease): T {
        val handle = client.resource(expected)
        val existing = lease.blocking { handle.get() } ?: try {
            lease.blocking { handle.create() }
        } catch (exception: KubernetesClientException) {
            if (exception.code != 409) throw exception
            requireNotNull(lease.blocking { handle.get() })
        }
        check(existing.metadata.ownerReferences.orEmpty().any { it.uid == ownerUid && it.controller == true }) {
            "Refusing to adopt ${existing.kind} ${existing.metadata.namespace}/${existing.metadata.name} owned by another resource"
        }
        return existing
    }

    override fun close() { scope.cancel() }

    companion object { private val log = LoggerFactory.getLogger(ServingModelController::class.java) }
}

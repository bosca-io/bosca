package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.model.ResourceChangeEvent
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.Watch
import io.fabric8.kubernetes.client.Watcher
import io.fabric8.kubernetes.client.WatcherException
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Generic change-notification stream — emits a [ResourceChangeEvent]
 * every time any resource of a requested kind is added, modified, or
 * deleted in the watched cluster. This is what keeps the studio's
 * list views live: a page subscribes for the kinds it renders and
 * re-runs its existing list query when a change tick arrives.
 *
 * URL: `GET /clusters/{id}/resources/watch?kinds=Service,Ingress&namespace=...`
 *
 * `kinds` is a comma-separated list of kind tokens:
 *
 *  * **Built-in / well-known kinds** resolve through [KIND_CONTEXTS]
 *    (core, apps, batch, rbac, storage, networking, Gateway API,
 *    cert-manager, CloudNativePG, CRDs themselves).
 *  * **`HelmRelease`** is a pseudo-kind that watches the `owner=helm`
 *    release Secrets — a new revision or status flip on any release
 *    emits a tick.
 *  * **Anything else** (optionally `group/Kind` to disambiguate) is
 *    resolved dynamically against the cluster's installed CRDs, so
 *    the custom-resources view can watch arbitrary operator kinds.
 *
 * Kinds whose CRD isn't installed on the target cluster are skipped
 * (consistent with the list routes' empty-state behaviour) rather
 * than failing the whole stream.
 *
 * Wire format: NDJSON, one [ResourceChangeEvent] per line. Watch
 * opens replay the existing resources as synthetic `ADDED` events —
 * the studio debounces, so the initial burst collapses into one
 * refresh.
 *
 * Authorization: REQUIRED JWT + independent admin re-check, same
 * floor as every other route.
 *
 * Reconnection: `respondStreaming` enforces the shared 5-minute cap;
 * if any underlying watch closes the stream completes and the studio
 * composable reconnects — re-establishing every watch is cheaper than
 * tracking partial-failure state per kind.
 */
@RouteController(
    path = "/clusters/{id}/resources/watch",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class ResourcesWatchRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
    private val json: Json,
) : Route<Unit>() {

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): Unit? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val kinds = call.request.queryParameters["kinds"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
        if (kinds.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest)
            return null
        }
        val namespace = call.request.queryParameters["namespace"]

        val client = pool.get(clusterId)
        call.response.respondStreaming(NDJSON) { output ->
            val channel = Channel<ResourceChangeEvent>(capacity = 256)
            val watches = mutableListOf<Watch>()

            try {
                withContext(Dispatchers.IO) {
                    for (kind in kinds) {
                        val watch = try {
                            openWatch(client, kind, namespace, channel)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (isCrdNotInstalled(e)) {
                                log.debug("skipping watch for {} on cluster {}: CRD not installed", kind, clusterId)
                                null
                            } else {
                                log.warn("failed opening watch for {} on cluster {}: {}", kind, clusterId, e.message)
                                null
                            }
                        }
                        if (watch != null) watches += watch
                    }
                }

                if (watches.isEmpty()) {
                    // Nothing watchable (all CRDs missing / all opens failed).
                    // Close immediately — an idle stream would look "live"
                    // to the studio while never delivering a tick.
                    channel.close()
                }

                while (currentCoroutineContext().isActive) {
                    val event = channel.receiveCatching().getOrNull() ?: break
                    val line = json.encodeToString(ResourceChangeEvent.serializer(), event) + "\n"
                    output.write(line.toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
            } finally {
                watches.forEach { watch ->
                    runCatching { watch.close() }
                        .onFailure { log.warn("failed closing resource watch for cluster {}: {}", clusterId, it.message) }
                }
                channel.close()
            }
        }
        return Unit
    }

    /**
     * Opens the fabric8 watch for one kind token. Throws on hard
     * failures (bad credentials, network); throws the fabric8 404 for
     * not-installed CRDs so the caller can skip the kind.
     */
    private fun openWatch(
        client: KubernetesClient,
        kind: String,
        namespace: String?,
        channel: Channel<ResourceChangeEvent>,
    ): Watch {
        if (kind == HELM_RELEASE_KIND) return openHelmWatch(client, namespace, channel)

        val context = KIND_CONTEXTS[kind] ?: resolveCrdContext(client, kind)
        val watcher = changeWatcher(kind, channel)
        val ops = client.genericKubernetesResources(context)
        return when {
            !context.isNamespaceScoped -> ops.watch(watcher)
            namespace != null -> ops.inNamespace(namespace).watch(watcher)
            else -> ops.inAnyNamespace().watch(watcher)
        }
    }

    /**
     * `HelmRelease` rides the `owner=helm` release Secrets — the same
     * source [HelmReleaseStatusWatchRoute] uses for a single release,
     * widened to every release so the releases list stays live.
     */
    private fun openHelmWatch(
        client: KubernetesClient,
        namespace: String?,
        channel: Channel<ResourceChangeEvent>,
    ): Watch {
        val watcher = object : Watcher<io.fabric8.kubernetes.api.model.Secret> {
            override fun eventReceived(action: Watcher.Action, resource: io.fabric8.kubernetes.api.model.Secret) {
                if (action != Watcher.Action.ADDED && action != Watcher.Action.MODIFIED && action != Watcher.Action.DELETED) return
                channel.trySend(
                    ResourceChangeEvent(
                        kind = HELM_RELEASE_KIND,
                        namespace = resource.metadata?.namespace,
                        // The release name lives in the `name` label;
                        // the Secret's own name carries the revision
                        // suffix (`sh.helm.release.v1.<release>.v<rev>`).
                        name = resource.metadata?.labels?.get("name") ?: resource.metadata?.name.orEmpty(),
                        action = action.name,
                        timestamp = Instant.now().toString(),
                    ),
                )
            }

            override fun onClose(cause: WatcherException?) {
                if (cause != null) log.debug("helm release watch closed: {}", cause.message)
                channel.close()
            }
        }
        val ops = client.secrets()
        return if (namespace != null) {
            ops.inNamespace(namespace).withLabel("owner", "helm").watch(watcher)
        } else {
            ops.inAnyNamespace().withLabel("owner", "helm").watch(watcher)
        }
    }

    private fun changeWatcher(
        kind: String,
        channel: Channel<ResourceChangeEvent>,
    ): Watcher<io.fabric8.kubernetes.api.model.GenericKubernetesResource> =
        object : Watcher<io.fabric8.kubernetes.api.model.GenericKubernetesResource> {
            override fun eventReceived(action: Watcher.Action, resource: io.fabric8.kubernetes.api.model.GenericKubernetesResource) {
                if (action != Watcher.Action.ADDED && action != Watcher.Action.MODIFIED && action != Watcher.Action.DELETED) return
                channel.trySend(resource.toChangeEvent(kind, action))
            }

            override fun onClose(cause: WatcherException?) {
                if (cause != null) log.debug("resource watch for {} closed: {}", kind, cause.message)
                channel.close()
            }
        }

    /**
     * Resolves an unknown kind token against the cluster's installed
     * CRDs. Accepts a bare `Kind` or `group/Kind` for disambiguation
     * (e.g. `postgresql.cnpg.io/Cluster`). Picks the storage version
     * (falling back to the first served one). Throws when no CRD
     * matches — surfaced to the caller as a skipped kind.
     */
    private fun resolveCrdContext(client: KubernetesClient, token: String): ResourceDefinitionContext {
        val (group, kind) = token.lastIndexOf('/').let { idx ->
            if (idx >= 0) token.take(idx) to token.substring(idx + 1) else null to token
        }
        val crd = client.apiextensions().v1().customResourceDefinitions().list().items
            .firstOrNull { it.spec.names.kind == kind && (group == null || it.spec.group == group) }
            ?: throw IllegalArgumentException("no CRD found for kind token '$token'")
        val version = crd.spec.versions.firstOrNull { it.storage }?.name
            ?: crd.spec.versions.firstOrNull { it.served }?.name
            ?: throw IllegalArgumentException("CRD ${crd.metadata.name} has no served version")
        return ResourceDefinitionContext.Builder()
            .withGroup(crd.spec.group)
            .withVersion(version)
            .withKind(kind)
            .withPlural(crd.spec.names.plural)
            .withNamespaced(crd.spec.scope == "Namespaced")
            .build()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ResourcesWatchRoute::class.java)
        private val NDJSON = ContentType("application", "x-ndjson")

        internal const val HELM_RELEASE_KIND = "HelmRelease"

        private fun context(
            group: String,
            version: String,
            kind: String,
            plural: String,
            namespaced: Boolean,
        ): ResourceDefinitionContext = ResourceDefinitionContext.Builder()
            .withGroup(group)
            .withVersion(version)
            .withKind(kind)
            .withPlural(plural)
            .withNamespaced(namespaced)
            .build()

        private fun io.fabric8.kubernetes.api.model.GenericKubernetesResource.toChangeEvent(
            kind: String,
            action: Watcher.Action,
        ): ResourceChangeEvent = ResourceChangeEvent(
            kind = kind,
            namespace = metadata?.namespace,
            name = metadata?.name.orEmpty(),
            action = action.name,
            timestamp = Instant.now().toString(),
        )

        /**
         * Every kind a studio view renders, addressed by the token the
         * studio passes in `kinds`. Built-ins use the generic resource
         * client too (one watch code path instead of a per-type
         * fabric8 switch); CRD-backed entries (Gateway API,
         * cert-manager, CloudNativePG) fail watch-open with a 404 when
         * the CRD is absent, which the route treats as skip.
         */
        internal val KIND_CONTEXTS: Map<String, ResourceDefinitionContext> = mapOf(
            // core/v1
            "Pod" to context("", "v1", "Pod", "pods", true),
            "Service" to context("", "v1", "Service", "services", true),
            "ConfigMap" to context("", "v1", "ConfigMap", "configmaps", true),
            "Secret" to context("", "v1", "Secret", "secrets", true),
            "Namespace" to context("", "v1", "Namespace", "namespaces", false),
            "Node" to context("", "v1", "Node", "nodes", false),
            "PersistentVolumeClaim" to context("", "v1", "PersistentVolumeClaim", "persistentvolumeclaims", true),
            "ServiceAccount" to context("", "v1", "ServiceAccount", "serviceaccounts", true),
            // apps/v1
            "Deployment" to context("apps", "v1", "Deployment", "deployments", true),
            "StatefulSet" to context("apps", "v1", "StatefulSet", "statefulsets", true),
            "DaemonSet" to context("apps", "v1", "DaemonSet", "daemonsets", true),
            "ReplicaSet" to context("apps", "v1", "ReplicaSet", "replicasets", true),
            // batch/v1
            "Job" to context("batch", "v1", "Job", "jobs", true),
            "CronJob" to context("batch", "v1", "CronJob", "cronjobs", true),
            // autoscaling/v2
            "HorizontalPodAutoscaler" to context("autoscaling", "v2", "HorizontalPodAutoscaler", "horizontalpodautoscalers", true),
            // policy/v1
            "PodDisruptionBudget" to context("policy", "v1", "PodDisruptionBudget", "poddisruptionbudgets", true),
            // networking.k8s.io/v1
            "Ingress" to context("networking.k8s.io", "v1", "Ingress", "ingresses", true),
            "NetworkPolicy" to context("networking.k8s.io", "v1", "NetworkPolicy", "networkpolicies", true),
            // storage.k8s.io/v1
            "StorageClass" to context("storage.k8s.io", "v1", "StorageClass", "storageclasses", false),
            // rbac.authorization.k8s.io/v1
            "Role" to context("rbac.authorization.k8s.io", "v1", "Role", "roles", true),
            "ClusterRole" to context("rbac.authorization.k8s.io", "v1", "ClusterRole", "clusterroles", false),
            "RoleBinding" to context("rbac.authorization.k8s.io", "v1", "RoleBinding", "rolebindings", true),
            "ClusterRoleBinding" to context("rbac.authorization.k8s.io", "v1", "ClusterRoleBinding", "clusterrolebindings", false),
            // apiextensions.k8s.io/v1
            "CustomResourceDefinition" to context("apiextensions.k8s.io", "v1", "CustomResourceDefinition", "customresourcedefinitions", false),
            // gateway.networking.k8s.io/v1
            "GatewayClass" to context("gateway.networking.k8s.io", "v1", "GatewayClass", "gatewayclasses", false),
            "Gateway" to context("gateway.networking.k8s.io", "v1", "Gateway", "gateways", true),
            "HTTPRoute" to context("gateway.networking.k8s.io", "v1", "HTTPRoute", "httproutes", true),
            // cert-manager.io/v1
            "Certificate" to context("cert-manager.io", "v1", "Certificate", "certificates", true),
            "Issuer" to context("cert-manager.io", "v1", "Issuer", "issuers", true),
            "ClusterIssuer" to context("cert-manager.io", "v1", "ClusterIssuer", "clusterissuers", false),
            // postgresql.cnpg.io/v1
            "CnpgCluster" to context("postgresql.cnpg.io", "v1", "Cluster", "clusters", true),
            "CnpgBackup" to context("postgresql.cnpg.io", "v1", "Backup", "backups", true),
            "CnpgScheduledBackup" to context("postgresql.cnpg.io", "v1", "ScheduledBackup", "scheduledbackups", true),
        )
    }
}

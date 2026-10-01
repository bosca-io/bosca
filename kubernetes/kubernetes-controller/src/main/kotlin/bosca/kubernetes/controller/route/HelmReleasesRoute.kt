package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.HelmReleaseDecoder
import bosca.kubernetes.model.HelmReleasesResponse
import bosca.kubernetes.model.K8sHelmRelease
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.ServerCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

/**
 * Lists helm releases in `{id}`. Helm 3 stores releases as Secrets
 * with `type=helm.sh/release.v1` and `owner=helm` label; we list those
 * Secrets and keep the highest-revision row per `(namespace, name)`.
 *
 * The list path is intentionally a single fabric8 call — one Secret
 * list with a field selector if possible, then in-memory filtering —
 * because helm release Secrets can be voluminous on busy clusters
 * (every revision lives forever unless `--max-history` was set).
 *
 * REQUIRED JWT + admin re-check.
 */
@RouteController(
    path = "/clusters/{id}/helm/releases",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class HelmReleasesRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<HelmReleasesResponse>() {

    override fun serializer(): KSerializer<HelmReleasesResponse> = HelmReleasesResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): HelmReleasesResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.request.queryParameters["namespace"]
        val client = pool.get(clusterId)

        return withContext(Dispatchers.IO) {
            try {
                // fabric8 requires the namespace scope before label
                // filtering — `withLabel` flips into a Listable type
                // that has no `inNamespace` method.
                val list = if (namespace != null) {
                    client.secrets().inNamespace(namespace).withLabel("owner", "helm").list()
                } else {
                    client.secrets().inAnyNamespace().withLabel("owner", "helm").list()
                }

                // Group by `(namespace, name)`, pick the highest revision per
                // group so the list reflects each release's current state
                // rather than every historical revision.
                val byRelease = mutableMapOf<String, K8sHelmRelease>()
                for (secret in list.items) {
                    if (!HelmReleaseDecoder.isHelmReleaseSecret(secret)) continue
                    val mapped = HelmReleaseDecoder.decodeRelease(secret) ?: continue
                    val key = "${mapped.namespace}/${mapped.name}"
                    val existing = byRelease[key]
                    if (existing == null || mapped.revision > existing.revision) {
                        byRelease[key] = mapped
                    }
                }
                HelmReleasesResponse(items = byRelease.values.sortedBy { it.namespace + "/" + it.name })
            } catch (e: Exception) {
                log.warn("helm releases list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(HelmReleasesRoute::class.java)
    }
}

/**
 * Returns every revision of a single helm release, sorted newest-first.
 *
 * URL: `GET /clusters/{id}/helm/releases/{namespace}/{name}/history`
 *
 * REQUIRED JWT + admin re-check.
 */
@RouteController(
    path = "/clusters/{id}/helm/releases/{namespace}/{name}/history",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class HelmReleaseHistoryRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<bosca.kubernetes.model.HelmReleaseHistoryResponse>() {

    override fun serializer(): KSerializer<bosca.kubernetes.model.HelmReleaseHistoryResponse> =
        bosca.kubernetes.model.HelmReleaseHistoryResponse.serializer()

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): bosca.kubernetes.model.HelmReleaseHistoryResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.pathParameters["namespace"]
            ?: return null.also { call.respond(bosca.server.HttpStatusCode.BadRequest) }
        val name = call.pathParameters["name"]
            ?: return null.also { call.respond(bosca.server.HttpStatusCode.BadRequest) }
        val client = pool.get(clusterId)

        return withContext(Dispatchers.IO) {
            try {
                val list = client.secrets()
                    .inNamespace(namespace)
                    .withLabel("owner", "helm")
                    .withLabel("name", name)
                    .list()
                val items = list.items
                    .mapNotNull { HelmReleaseDecoder.decodeRevision(it) }
                    .sortedByDescending { it.revision }
                bosca.kubernetes.model.HelmReleaseHistoryResponse(items = items)
            } catch (e: Exception) {
                log.warn("helm release history failed for {}/{} in cluster {}: {}", namespace, name, clusterId, e.message)
                throw e
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(HelmReleaseHistoryRoute::class.java)
    }
}

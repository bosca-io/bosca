package bosca.kubernetes.controller.route

import bosca.db.withConnectionManager
import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.helm.HelmRuntime
import bosca.kubernetes.model.HelmInstallRequest
import bosca.kubernetes.model.HelmRollbackRequest
import bosca.kubernetes.model.HelmUpgradeRequest
import bosca.kubernetes.model.K8sHelmRelease
import bosca.kubernetes.service.HelmRepoService
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory

/**
 * Helm install. Shells out to the `helm` binary (see [HelmRuntime]
 * for the security model). On success the route reads the freshly-
 * written release Secret from the cluster and returns it as a wire
 * [K8sHelmRelease] — same shape the catalog read path emits, so the
 * studio's install wizard can drop the result straight onto the
 * releases page.
 *
 * REQUIRED JWT + admin re-check.
 */
@RouteController(
    path = "/clusters/{id}/helm/install",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.REQUIRED,
)
class HelmInstallRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
    private val helm: HelmRuntime,
    private val repos: HelmRepoService,
) : Route<K8sHelmRelease>() {

    override fun serializer(): KSerializer<K8sHelmRelease> = K8sHelmRelease.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): K8sHelmRelease? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val body = call.receive<HelmInstallRequest>()
        if (body.name.isBlank() || body.repo.isBlank() || body.chart.isBlank() || body.version.isBlank()) {
            call.respond(HttpStatusCode.BadRequest)
            return null
        }
        // Resolve the repo's URL — Bosca's helmRepoAdd persists repos in the
        // controller's Postgres `kubernetes.helm_repo` table without
        // registering them with the helm CLI. Pass `--repo <url>` directly
        // so helm install resolves the chart from the URL on every call.
        val repoAndCredentials = withConnectionManager {
            repos.getByName(body.repo)?.let { it to repos.credentials(it.name) }
        } ?: return null.also {
            log.warn("helm install: repo '{}' not registered in Bosca", body.repo)
            call.respond(HttpStatusCode.BadRequest)
        }
        val (repository, repoCredentials) = repoAndCredentials
        val client = pool.get(clusterId)
        return try {
            helm.install(client, clusterId, body, repository.url, repoCredentials)
        } catch (e: Exception) {
            log.warn("helm install failed for {}/{} on cluster {}: {}", body.namespace, body.name, clusterId, e.message)
            throw e
        }
    }

    companion object { private val log = LoggerFactory.getLogger(HelmInstallRoute::class.java) }
}

/**
 * Helm upgrade. The upstream `repo` + `chart` aren't passed in the
 * GraphQL `HelmUpgradeInput` (they're inferred from the existing
 * release), but `helm upgrade` needs both at the CLI. We resolve them
 * by reading the existing release's metadata before the upgrade —
 * the chart name is on the release; the repo back-link sometimes is
 * not, so the caller may need to supply it via the URL query params.
 *
 * Query params:
 *   * `repo`  (required) — repo name the chart came from
 *   * `chart` (required) — chart name (the upgrade can switch chart
 *                          for releases that take a chart override, but
 *                          we don't expose that knob in v1)
 */
@RouteController(
    path = "/clusters/{id}/helm/upgrade",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.REQUIRED,
)
class HelmUpgradeRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
    private val helm: HelmRuntime,
    private val repos: HelmRepoService,
) : Route<K8sHelmRelease>() {

    override fun serializer(): KSerializer<K8sHelmRelease> = K8sHelmRelease.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): K8sHelmRelease? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val params = call.request.queryParameters
        val repo = params["repo"]?.takeIf { it.isNotBlank() }
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val chart = params["chart"]?.takeIf { it.isNotBlank() }
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val body = call.receive<HelmUpgradeRequest>()
        if (body.name.isBlank() || body.version.isBlank()) {
            call.respond(HttpStatusCode.BadRequest)
            return null
        }
        // Resolve repo URL — see HelmInstallRoute for the rationale.
        val repoAndCredentials = withConnectionManager {
            repos.getByName(repo)?.let { it to repos.credentials(it.name) }
        } ?: return null.also {
            log.warn("helm upgrade: repo '{}' not registered in Bosca", repo)
            call.respond(HttpStatusCode.BadRequest)
        }
        val (repository, repoCredentials) = repoAndCredentials
        val client = pool.get(clusterId)
        return try {
            helm.upgrade(client, clusterId, body, repository.url, chart, repoCredentials)
        } catch (e: Exception) {
            log.warn("helm upgrade failed for {}/{} on cluster {}: {}", body.namespace, body.name, clusterId, e.message)
            throw e
        }
    }

    companion object { private val log = LoggerFactory.getLogger(HelmUpgradeRoute::class.java) }
}

@RouteController(
    path = "/clusters/{id}/helm/rollback",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.REQUIRED,
)
class HelmRollbackRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
    private val helm: HelmRuntime,
) : Route<K8sHelmRelease>() {

    override fun serializer(): KSerializer<K8sHelmRelease> = K8sHelmRelease.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): K8sHelmRelease? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val body = call.receive<HelmRollbackRequest>()
        if (body.name.isBlank() || body.namespace.isBlank() || body.toRevision <= 0) {
            call.respond(HttpStatusCode.BadRequest)
            return null
        }
        val client = pool.get(clusterId)
        return try {
            helm.rollback(client, clusterId, body)
        } catch (e: Exception) {
            log.warn("helm rollback failed for {}/{}@{} on cluster {}: {}",
                body.namespace, body.name, body.toRevision, clusterId, e.message)
            throw e
        }
    }

    companion object { private val log = LoggerFactory.getLogger(HelmRollbackRoute::class.java) }
}

/**
 * Uninstall a helm release. Optional query param `keepHistory=true`
 * preserves the release Secrets so a future rollback can restore an
 * uninstalled release.
 */
@RouteController(
    path = "/clusters/{id}/helm/releases/{namespace}/{name}",
    method = RouteMethod.DELETE,
    authentication = RouteAuthentication.REQUIRED,
)
class HelmUninstallRoute(
    private val groups: GroupEvaluator,
    private val helm: HelmRuntime,
) : Route<Boolean>() {

    override fun serializer(): KSerializer<Boolean> = Boolean.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Boolean? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.pathParameters["namespace"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val name = call.pathParameters["name"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val keepHistory = call.request.queryParameters["keepHistory"]?.toBooleanStrictOrNull() ?: false
        return try {
            helm.uninstall(clusterId, namespace, name, keepHistory)
        } catch (e: Exception) {
            log.warn("helm uninstall failed for {}/{} on cluster {}: {}", namespace, name, clusterId, e.message)
            throw e
        }
    }

    companion object { private val log = LoggerFactory.getLogger(HelmUninstallRoute::class.java) }
}

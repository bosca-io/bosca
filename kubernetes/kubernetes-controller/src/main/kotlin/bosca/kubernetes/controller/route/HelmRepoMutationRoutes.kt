package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.helm.HelmIndexFetcher
import bosca.kubernetes.model.HelmRepoAddRequest
import bosca.kubernetes.model.HelmReposResponse
import bosca.kubernetes.model.K8sHelmRepo
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
 * Adds a helm repo: validates the URL by fetching `index.yaml` at
 * register time. A failed fetch surfaces as a wire error, so the
 * studio's "Add repo" dialog can render the failure inline and the
 * row is not persisted.
 *
 * `oci://` URLs are rejected with 400 — OCI registry support is a
 * focused follow-up.
 */
@RouteController(
    path = "/helm/repos",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.REQUIRED,
)
class HelmRepoAddRoute(
    private val groups: GroupEvaluator,
    private val repos: HelmRepoService,
    private val fetcher: HelmIndexFetcher,
) : Route<K8sHelmRepo>() {

    override fun serializer(): KSerializer<K8sHelmRepo> = K8sHelmRepo.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): K8sHelmRepo? {
        groups.verifyHasAdminGroup(authenticationContext)
        val body = call.receive<HelmRepoAddRequest>()
        if (body.name.isBlank() || body.url.isBlank()) {
            call.respond(HttpStatusCode.BadRequest)
            return null
        }
        val type = if (body.url.startsWith("oci://")) {
            call.respond(HttpStatusCode.BadRequest)
            return null
        } else {
            "http"
        }
        return try {
            val plaintextCredentials = body.credentials()
            val indexYaml = fetcher.fetchIndex(body.url, plaintextCredentials)
            val row = repos.save(
                name = body.name,
                url = body.url,
                type = type,
                indexYaml = indexYaml,
                repoCredentials = plaintextCredentials,
            )
            fetcher.toRepoView(row.name, row.url, row.type, row.indexYaml, row.lastIndexAt)
        } catch (e: Exception) {
            log.warn("helm repo add failed for {} ({}): {}", body.name, body.url, e.message)
            call.respond(HttpStatusCode.BadRequest)
            null
        }
    }

    companion object { private val log = LoggerFactory.getLogger(HelmRepoAddRoute::class.java) }
}

/**
 * Re-fetches every configured repo's `index.yaml` and updates the
 * cached row. Returns the refreshed list so the studio's repos page
 * gets fresh chart counts in the same round-trip.
 */
@RouteController(
    path = "/helm/repos/refresh",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.REQUIRED,
)
class HelmRepoRefreshRoute(
    private val groups: GroupEvaluator,
    private val repos: HelmRepoService,
    private val fetcher: HelmIndexFetcher,
) : Route<HelmReposResponse>() {

    override fun serializer(): KSerializer<HelmReposResponse> = HelmReposResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): HelmReposResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val refreshed = mutableListOf<K8sHelmRepo>()
        for (row in repos.list()) {
            val view = try {
                val indexYaml = fetcher.fetchIndex(row.url, repos.credentials(row.name))
                val updated = repos.updateIndex(row.name, indexYaml) ?: row.copy(indexYaml = indexYaml)
                fetcher.toRepoView(
                    name = updated.name, url = updated.url, type = updated.type,
                    indexYaml = updated.indexYaml, lastIndexAt = updated.lastIndexAt,
                )
            } catch (e: Exception) {
                log.warn("helm repo refresh failed for {} ({}): {}", row.name, row.url, e.message)
                // Surface the stale view so the studio still sees the
                // row — last_index_at signals staleness.
                fetcher.toRepoView(
                    name = row.name, url = row.url, type = row.type,
                    indexYaml = row.indexYaml, lastIndexAt = row.lastIndexAt,
                )
            }
            refreshed += view
        }
        return HelmReposResponse(items = refreshed.sortedBy { it.name })
    }

    companion object { private val log = LoggerFactory.getLogger(HelmRepoRefreshRoute::class.java) }
}

@RouteController(
    path = "/helm/repos/{name}",
    method = RouteMethod.DELETE,
    authentication = RouteAuthentication.REQUIRED,
)
class HelmRepoRemoveRoute(
    private val groups: GroupEvaluator,
    private val repos: HelmRepoService,
) : Route<Boolean>() {

    override fun serializer(): KSerializer<Boolean> = Boolean.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Boolean? {
        groups.verifyHasAdminGroup(authenticationContext)
        val name = call.pathParameters["name"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        repos.remove(name)
        return true
    }
}

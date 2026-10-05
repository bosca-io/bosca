package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.helm.HelmIndex
import bosca.kubernetes.controller.helm.HelmIndexFetcher
import bosca.kubernetes.model.HelmChartVersionsResponse
import bosca.kubernetes.model.HelmChartsResponse
import bosca.kubernetes.model.HelmReposResponse
import bosca.kubernetes.model.K8sHelmChart
import bosca.kubernetes.model.K8sHelmChartValues
import bosca.kubernetes.service.HelmRepoService
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

/**
 * Helm catalog reads — all of these go against the in-Bosca repo
 * registry, not the target cluster. There's no `{id}` path parameter
 * because the catalog is cluster-agnostic; the studio's install flow
 * is what binds a chosen chart to a target cluster.
 *
 * Authorization: REQUIRED JWT + admin re-check. Helm repo membership
 * carries influence (deciding which charts users see) so the same
 * admin-only floor applies as cluster-side reads.
 */

@RouteController(
    path = "/helm/repos",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class HelmReposRoute(
    private val groups: GroupEvaluator,
    private val repos: HelmRepoService,
    private val fetcher: HelmIndexFetcher,
) : Route<HelmReposResponse>() {

    override fun serializer(): KSerializer<HelmReposResponse> = HelmReposResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): HelmReposResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val rows = repos.list()
        return HelmReposResponse(
            items = rows.map {
                fetcher.toRepoView(
                    name = it.name,
                    url = it.url,
                    type = it.type,
                    indexYaml = it.indexYaml,
                    lastIndexAt = it.lastIndexAt,
                )
            },
        )
    }
}

@RouteController(
    path = "/helm/charts",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class HelmChartsRoute(
    private val groups: GroupEvaluator,
    private val repos: HelmRepoService,
) : Route<HelmChartsResponse>() {

    override fun serializer(): KSerializer<HelmChartsResponse> = HelmChartsResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): HelmChartsResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val params = call.request.queryParameters
        val repoFilter = params["repo"]
        val search = params["search"]

        val rows = if (repoFilter != null) {
            listOfNotNull(repos.getByName(repoFilter))
        } else {
            repos.list()
        }
        val collected = mutableListOf<K8sHelmChart>()
        for (row in rows) {
            collected += HelmIndex.charts(row.name, row.indexYaml, search)
        }
        return HelmChartsResponse(items = collected.sortedBy { it.repo + "/" + it.name })
    }
}

@RouteController(
    path = "/helm/repos/{repo}/charts/{chart}/versions",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class HelmChartVersionsRoute(
    private val groups: GroupEvaluator,
    private val repos: HelmRepoService,
) : Route<HelmChartVersionsResponse>() {

    override fun serializer(): KSerializer<HelmChartVersionsResponse> = HelmChartVersionsResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): HelmChartVersionsResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val repoName = call.pathParameters["repo"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val chart = call.pathParameters["chart"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val row = repos.getByName(repoName)
            ?: return null.also { call.respond(HttpStatusCode.NotFound) }
        return HelmChartVersionsResponse(items = HelmIndex.versions(row.indexYaml, chart))
    }
}

@RouteController(
    path = "/helm/repos/{repo}/charts/{chart}/versions/{version}/values",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class HelmChartValuesRoute(
    private val groups: GroupEvaluator,
    private val repos: HelmRepoService,
    private val fetcher: HelmIndexFetcher,
) : Route<K8sHelmChartValues>() {

    override fun serializer(): KSerializer<K8sHelmChartValues> = K8sHelmChartValues.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): K8sHelmChartValues? {
        groups.verifyHasAdminGroup(authenticationContext)
        val repoName = call.pathParameters["repo"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val chart = call.pathParameters["chart"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val version = call.pathParameters["version"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val row = repos.getByName(repoName)
            ?: return null.also { call.respond(HttpStatusCode.NotFound) }
        val tgzUrl = HelmIndex.chartUrl(row.indexYaml, chart, version)
            ?: return null.also { call.respond(HttpStatusCode.NotFound) }

        return withContext(Dispatchers.IO) {
            try {
                fetcher.fetchValues(
                    tgzUrl = tgzUrl,
                    repoUrl = row.url,
                    credentials = repos.credentials(row.name),
                )
            } catch (e: Exception) {
                log.warn("helm chart values fetch failed for {}/{}@{}: {}", repoName, chart, version, e.message)
                K8sHelmChartValues(defaultValues = "", schema = null)
            }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(HelmChartValuesRoute::class.java) }
}

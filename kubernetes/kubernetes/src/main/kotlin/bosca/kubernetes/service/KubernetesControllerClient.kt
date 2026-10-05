package bosca.kubernetes.service

import bosca.kubernetes.model.ApplyManifestRequest
import bosca.kubernetes.model.ApplyResult
import bosca.kubernetes.model.CertificatesResponse
import bosca.kubernetes.model.CnpgClusterDetailResponse
import bosca.kubernetes.model.CnpgClustersResponse
import bosca.kubernetes.model.ConfigKind
import bosca.kubernetes.model.ConfigResourceEntriesResponse
import bosca.kubernetes.model.ConfigResourcesResponse
import bosca.kubernetes.model.CreateNamespaceRequest
import bosca.kubernetes.model.CustomResourcesResponse
import bosca.kubernetes.model.DeleteResponse
import bosca.kubernetes.model.GatewayClassesResponse
import bosca.kubernetes.model.GatewaysResponse
import bosca.kubernetes.model.HpasResponse
import bosca.kubernetes.model.K8sHpa
import bosca.kubernetes.model.PdbsResponse
import bosca.kubernetes.model.UpdateHpaLimitsRequest
import bosca.kubernetes.model.HelmChartVersionsResponse
import bosca.kubernetes.model.HelmChartsResponse
import bosca.kubernetes.model.HelmInstallRequest
import bosca.kubernetes.model.HelmReleaseHistoryResponse
import bosca.kubernetes.model.HelmReleaseTextResponse
import bosca.kubernetes.model.HelmReleasesResponse
import bosca.kubernetes.model.HelmRepoAddRequest
import bosca.kubernetes.model.HelmReposResponse
import bosca.kubernetes.model.HelmRollbackRequest
import bosca.kubernetes.model.HelmUpgradeRequest
import bosca.kubernetes.model.K8sHelmRelease
import bosca.kubernetes.model.HttpRoutesResponse
import bosca.kubernetes.model.IssuersResponse
import bosca.kubernetes.model.K8sHelmChartValues
import bosca.kubernetes.model.K8sHelmRepo
import bosca.kubernetes.model.K8sEvent
import bosca.kubernetes.model.OperatorsResponse
import bosca.kubernetes.model.YamlResponse
import bosca.kubernetes.model.EventLevel
import bosca.kubernetes.model.EventsResponse
import bosca.kubernetes.model.IngressesResponse
import bosca.kubernetes.model.LogLine
import bosca.kubernetes.model.Namespace
import bosca.kubernetes.model.NamespacesResponse
import bosca.kubernetes.model.NetworkPoliciesResponse
import bosca.kubernetes.model.NodesResponse
import bosca.kubernetes.model.PodsResponse
import bosca.kubernetes.model.PvcsResponse
import bosca.kubernetes.model.RoleBindingsResponse
import bosca.kubernetes.model.RolesResponse
import bosca.kubernetes.model.ScaleWorkloadRequest
import bosca.kubernetes.model.ServiceAccountsResponse
import bosca.kubernetes.model.ServicesResponse
import bosca.kubernetes.model.StorageClassesResponse
import bosca.kubernetes.model.Workload
import bosca.kubernetes.model.WorkloadKind
import bosca.kubernetes.model.WorkloadsResponse
import kotlinx.serialization.json.JsonElement
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.server.http.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * HTTP client for the standalone `kubernetes-controller` service.
 *
 * ## Security model
 *
 * `kubernetes-controller` is reachable only on the internal docker
 * network, but defense in depth requires it to authenticate every
 * caller — there is no "trusted backend" tier. Every method here
 * therefore takes the caller's [AuthenticationContext], mints a
 * short-lived JWT representing **the actual user** via
 * [SecurityService.createJwtToken], and forwards it as a Bearer token
 * in the `Authorization` header.
 *
 * The kubernetes-controller binary shares the same `JWT_SECRET` and
 * principal database with bosca-server (see
 * `web/docker-compose.yaml`), so it validates the token natively and
 * resolves the actual `AuthenticatedPrincipal`. The controller then
 * re-verifies that the caller is in the `administrators` group on
 * every route — bosca-server's own group check is *not* trusted: a
 * bug in bosca-server's resolver code cannot grant a non-admin user
 * access to cluster operations.
 *
 * Anonymous or unauthenticated callers throw at the resolver layer
 * (see [bosca.kubernetes.controller.KubernetesQueriesController]); the
 * client itself fails fast if the principal is missing.
 */
class KubernetesControllerClient(
    private val baseUrl: String,
    private val securityService: SecurityService,
    private val http: OkHttpClient = defaultClient(),
    private val streamingHttp: OkHttpClient = streamingClient(http),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** Fetches the list of namespaces in [clusterId]. */
    suspend fun namespaces(
        authentication: AuthenticationContext,
        clusterId: UUID,
    ): NamespacesResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "namespaces"), NamespacesResponse.serializer()) { }
            ?: NamespacesResponse(emptyList())

    suspend fun configResources(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
        kind: ConfigKind? = null,
    ): ConfigResourcesResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "config"), ConfigResourcesResponse.serializer()) {
            namespace?.let { addQueryParameter("namespace", it) }
            kind?.let { addQueryParameter("kind", it.name) }
        } ?: ConfigResourcesResponse(emptyList())

    suspend fun configEntries(
        authentication: AuthenticationContext,
        clusterId: UUID,
        kind: ConfigKind,
        namespace: String,
        name: String,
    ): ConfigResourceEntriesResponse =
        get(
            authentication,
            listOf("clusters", clusterId.toString(), "config", kind.name, namespace, name, "entries"),
            ConfigResourceEntriesResponse.serializer(),
        ) { }
            ?: ConfigResourceEntriesResponse(emptyList())

    suspend fun services(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
    ): ServicesResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "services"), ServicesResponse.serializer()) {
            namespace?.let { addQueryParameter("namespace", it) }
        } ?: ServicesResponse(emptyList())

    suspend fun ingresses(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
    ): IngressesResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "ingresses"), IngressesResponse.serializer()) {
            namespace?.let { addQueryParameter("namespace", it) }
        } ?: IngressesResponse(emptyList())

    suspend fun networkPolicies(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
    ): NetworkPoliciesResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "networkpolicies"), NetworkPoliciesResponse.serializer()) {
            namespace?.let { addQueryParameter("namespace", it) }
        } ?: NetworkPoliciesResponse(emptyList())

    suspend fun storageClasses(
        authentication: AuthenticationContext,
        clusterId: UUID,
    ): StorageClassesResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "storageclasses"), StorageClassesResponse.serializer()) { }
            ?: StorageClassesResponse(emptyList())

    suspend fun pvcs(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
    ): PvcsResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "pvcs"), PvcsResponse.serializer()) {
            namespace?.let { addQueryParameter("namespace", it) }
        } ?: PvcsResponse(emptyList())

    suspend fun horizontalPodAutoscalers(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
    ): HpasResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "hpas"), HpasResponse.serializer()) {
            namespace?.let { addQueryParameter("namespace", it) }
        } ?: HpasResponse(emptyList())

    suspend fun podDisruptionBudgets(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
    ): PdbsResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "pdbs"), PdbsResponse.serializer()) {
            namespace?.let { addQueryParameter("namespace", it) }
        } ?: PdbsResponse(emptyList())

    suspend fun updateHpaLimits(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String,
        name: String,
        minReplicas: Int,
        maxReplicas: Int,
    ): K8sHpa {
        require(minReplicas >= 1) { "minReplicas must be >= 1" }
        require(maxReplicas >= minReplicas) { "maxReplicas must be >= minReplicas" }
        return post(
            authentication = authentication,
            pathSegments = listOf("clusters", clusterId.toString(), "hpas", namespace, name, "limits"),
            body = UpdateHpaLimitsRequest(minReplicas, maxReplicas),
            bodySerializer = UpdateHpaLimitsRequest.serializer(),
            responseSerializer = K8sHpa.serializer(),
        ) ?: error("kubernetes-controller returned empty body for updateHpaLimits")
    }

    suspend fun roles(
        authentication: AuthenticationContext,
        clusterId: UUID,
        kind: String? = null,
    ): RolesResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "roles"), RolesResponse.serializer()) {
            kind?.let { addQueryParameter("kind", it) }
        } ?: RolesResponse(emptyList())

    suspend fun roleBindings(
        authentication: AuthenticationContext,
        clusterId: UUID,
        kind: String? = null,
    ): RoleBindingsResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "rolebindings"), RoleBindingsResponse.serializer()) {
            kind?.let { addQueryParameter("kind", it) }
        } ?: RoleBindingsResponse(emptyList())

    suspend fun serviceAccounts(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
    ): ServiceAccountsResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "serviceaccounts"), ServiceAccountsResponse.serializer()) {
            namespace?.let { addQueryParameter("namespace", it) }
        } ?: ServiceAccountsResponse(emptyList())

    suspend fun yaml(
        authentication: AuthenticationContext,
        clusterId: UUID,
        kind: String,
        name: String,
        namespace: String? = null,
        group: String? = null,
        version: String? = null,
    ): YamlResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "yaml"), YamlResponse.serializer()) {
            addQueryParameter("kind", kind)
            addQueryParameter("name", name)
            namespace?.let { addQueryParameter("namespace", it) }
            group?.let { addQueryParameter("group", it) }
            version?.let { addQueryParameter("version", it) }
        } ?: YamlResponse(yaml = "")

    suspend fun operators(
        authentication: AuthenticationContext,
        clusterId: UUID,
    ): OperatorsResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "operators"), OperatorsResponse.serializer()) { }
            ?: OperatorsResponse(emptyList())

    suspend fun customResources(
        authentication: AuthenticationContext,
        clusterId: UUID,
        group: String? = null,
        namespace: String? = null,
    ): CustomResourcesResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "customresources"), CustomResourcesResponse.serializer()) {
            group?.let { addQueryParameter("group", it) }
            namespace?.let { addQueryParameter("namespace", it) }
        } ?: CustomResourcesResponse(emptyList())

    suspend fun gatewayClasses(
        authentication: AuthenticationContext,
        clusterId: UUID,
    ): GatewayClassesResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "gatewayclasses"), GatewayClassesResponse.serializer()) { }
            ?: GatewayClassesResponse(emptyList())

    suspend fun gateways(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
    ): GatewaysResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "gateways"), GatewaysResponse.serializer()) {
            namespace?.let { addQueryParameter("namespace", it) }
        } ?: GatewaysResponse(emptyList())

    suspend fun httpRoutes(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
    ): HttpRoutesResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "httproutes"), HttpRoutesResponse.serializer()) {
            namespace?.let { addQueryParameter("namespace", it) }
        } ?: HttpRoutesResponse(emptyList())

    suspend fun certManagerCertificates(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
    ): CertificatesResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "certmanager", "certificates"), CertificatesResponse.serializer()) {
            namespace?.let { addQueryParameter("namespace", it) }
        } ?: CertificatesResponse(emptyList())

    suspend fun certManagerIssuers(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
    ): IssuersResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "certmanager", "issuers"), IssuersResponse.serializer()) {
            namespace?.let { addQueryParameter("namespace", it) }
        } ?: IssuersResponse(emptyList())

    suspend fun cnpgClusters(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
    ): CnpgClustersResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "cnpg", "clusters"), CnpgClustersResponse.serializer()) {
            namespace?.let { addQueryParameter("namespace", it) }
        } ?: CnpgClustersResponse(emptyList())

    suspend fun cnpgClusterDetail(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String,
        name: String,
    ): CnpgClusterDetailResponse =
        get(
            authentication,
            listOf("clusters", clusterId.toString(), "cnpg", "clusters", namespace, name),
            CnpgClusterDetailResponse.serializer(),
        ) { } ?: CnpgClusterDetailResponse(detail = null)

    suspend fun helmRepos(authentication: AuthenticationContext): HelmReposResponse =
        get(authentication, listOf("helm", "repos"), HelmReposResponse.serializer()) { }
            ?: HelmReposResponse(emptyList())

    suspend fun helmCharts(
        authentication: AuthenticationContext,
        repo: String? = null,
        search: String? = null,
    ): HelmChartsResponse =
        get(authentication, listOf("helm", "charts"), HelmChartsResponse.serializer()) {
            repo?.let { addQueryParameter("repo", it) }
            search?.let { addQueryParameter("search", it) }
        } ?: HelmChartsResponse(emptyList())

    suspend fun helmChartVersions(
        authentication: AuthenticationContext,
        repo: String,
        chart: String,
    ): HelmChartVersionsResponse =
        get(
            authentication,
            listOf("helm", "repos", repo, "charts", chart, "versions"),
            HelmChartVersionsResponse.serializer(),
        ) { }
            ?: HelmChartVersionsResponse(emptyList())

    suspend fun helmChartValues(
        authentication: AuthenticationContext,
        repo: String,
        chart: String,
        version: String,
    ): K8sHelmChartValues =
        get(
            authentication,
            listOf("helm", "repos", repo, "charts", chart, "versions", version, "values"),
            K8sHelmChartValues.serializer(),
        ) { }
            ?: K8sHelmChartValues(defaultValues = "", schema = null)

    suspend fun helmReleases(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
    ): HelmReleasesResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "helm", "releases"), HelmReleasesResponse.serializer()) {
            namespace?.let { addQueryParameter("namespace", it) }
        } ?: HelmReleasesResponse(emptyList())

    suspend fun helmReleaseHistory(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String,
        name: String,
    ): HelmReleaseHistoryResponse =
        get(
            authentication,
            listOf("clusters", clusterId.toString(), "helm", "releases", namespace, name, "history"),
            HelmReleaseHistoryResponse.serializer(),
        ) { }
            ?: HelmReleaseHistoryResponse(emptyList())

    /**
     * Returns the merged effective values YAML for a single release.
     * `revision = null` returns the current revision's values.
     */
    suspend fun helmReleaseValues(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String,
        name: String,
        revision: Int? = null,
    ): String =
        get(
            authentication,
            listOf("clusters", clusterId.toString(), "helm", "releases", namespace, name, "values"),
            HelmReleaseTextResponse.serializer(),
        ) {
            revision?.let { addQueryParameter("revision", it.toString()) }
        }?.yaml ?: ""

    /**
     * Returns the rendered manifest YAML stream for a single release.
     * `revision = null` returns the current revision's manifest.
     */
    suspend fun helmReleaseManifest(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String,
        name: String,
        revision: Int? = null,
    ): String =
        get(
            authentication,
            listOf("clusters", clusterId.toString(), "helm", "releases", namespace, name, "manifest"),
            HelmReleaseTextResponse.serializer(),
        ) {
            revision?.let { addQueryParameter("revision", it.toString()) }
        }?.yaml ?: ""

    /** Adds a helm repo and validates by fetching its `index.yaml`. */
    suspend fun helmRepoAdd(
        authentication: AuthenticationContext,
        name: String,
        url: String,
        username: String? = null,
        password: String? = null,
    ): K8sHelmRepo = post(
        authentication = authentication,
        pathSegments = listOf("helm", "repos"),
        body = HelmRepoAddRequest(name = name, url = url, username = username, password = password),
        bodySerializer = HelmRepoAddRequest.serializer(),
        responseSerializer = K8sHelmRepo.serializer(),
    ) ?: error("helm repo add returned empty body")

    /** Refreshes every configured repo's cached index. */
    suspend fun helmRepoUpdate(authentication: AuthenticationContext): HelmReposResponse = post(
        authentication = authentication,
        pathSegments = listOf("helm", "repos", "refresh"),
        body = Unit,
        bodySerializer = null,
        responseSerializer = HelmReposResponse.serializer(),
    ) ?: HelmReposResponse(emptyList())

    suspend fun helmInstall(
        authentication: AuthenticationContext,
        clusterId: UUID,
        name: String,
        namespace: String,
        createNamespace: Boolean,
        repo: String,
        chart: String,
        version: String,
        values: String? = null,
        dryRun: Boolean = false,
    ): K8sHelmRelease = post(
        authentication = authentication,
        pathSegments = listOf("clusters", clusterId.toString(), "helm", "install"),
        body = HelmInstallRequest(
            name = name,
            namespace = namespace,
            createNamespace = createNamespace,
            repo = repo,
            chart = chart,
            version = version,
            values = values,
            dryRun = dryRun,
        ),
        bodySerializer = HelmInstallRequest.serializer(),
        responseSerializer = K8sHelmRelease.serializer(),
    ) ?: error("helm install returned empty body")

    suspend fun helmUpgrade(
        authentication: AuthenticationContext,
        clusterId: UUID,
        name: String,
        namespace: String,
        version: String,
        values: String? = null,
        dryRun: Boolean = false,
        resetValues: Boolean = false,
        repo: String,
        chart: String,
    ): K8sHelmRelease {
        val token = mintBearerToken(authentication)
        val url = buildUrl(listOf("clusters", clusterId.toString(), "helm", "upgrade")) {
            addQueryParameter("repo", repo)
            addQueryParameter("chart", chart)
        }
        val requestBody = json.encodeToString(
            HelmUpgradeRequest.serializer(),
            HelmUpgradeRequest(
                name = name,
                namespace = namespace,
                version = version,
                values = values,
                dryRun = dryRun,
                resetValues = resetValues,
            ),
        ).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(requestBody)
            .build()
        return executeAndDecode(request, "helm/upgrade", K8sHelmRelease.serializer())
            ?: error("helm upgrade returned empty body")
    }

    suspend fun helmRollback(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String,
        name: String,
        toRevision: Int,
    ): K8sHelmRelease = post(
        authentication = authentication,
        pathSegments = listOf("clusters", clusterId.toString(), "helm", "rollback"),
        body = HelmRollbackRequest(name = name, namespace = namespace, toRevision = toRevision),
        bodySerializer = HelmRollbackRequest.serializer(),
        responseSerializer = K8sHelmRelease.serializer(),
    ) ?: error("helm rollback returned empty body")

    suspend fun helmUninstall(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String,
        name: String,
        keepHistory: Boolean = false,
    ): Boolean {
        val token = mintBearerToken(authentication)
        val url = buildUrl(listOf("clusters", clusterId.toString(), "helm", "releases", namespace, name)) {
            if (keepHistory) addQueryParameter("keepHistory", "true")
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        return executeAndDecode(request, "helm uninstall", Boolean.serializer()) ?: false
    }

    /** Removes a helm repo by name. Idempotent. */
    suspend fun helmRepoRemove(
        authentication: AuthenticationContext,
        name: String,
    ): Boolean {
        val token = mintBearerToken(authentication)
        val url = buildUrl(listOf("helm", "repos", name))
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        return executeAndDecode(request, "helm/repos/$name", Boolean.serializer())
            ?: false
    }

    /**
     * Fetches workloads in [clusterId], optionally narrowed to a single
     * [namespace] and/or [kind]. Filters are applied controller-side so
     * the response is already trimmed.
     */
    suspend fun workloads(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
        kind: WorkloadKind? = null,
    ): WorkloadsResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "workloads"), WorkloadsResponse.serializer()) {
            namespace?.let { addQueryParameter("namespace", it) }
            kind?.let { addQueryParameter("kind", it.name) }
        } ?: WorkloadsResponse(emptyList())

    /**
     * Fetches pods in [clusterId] with the same filter knobs the
     * GraphQL surface exposes. [limit] caps the page size;
     * controller-side pagination keeps the wire payload bounded for
     * very large clusters.
     */
    suspend fun pods(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
        workloadId: String? = null,
        search: String? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): PodsResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "pods"), PodsResponse.serializer()) {
            namespace?.let { addQueryParameter("namespace", it) }
            workloadId?.let { addQueryParameter("workloadId", it) }
            search?.let { addQueryParameter("search", it) }
            limit?.let { addQueryParameter("limit", it.toString()) }
            offset?.let { addQueryParameter("offset", it.toString()) }
        } ?: PodsResponse(total = 0, items = emptyList())

    /** Fetches the node list for [clusterId]. */
    suspend fun nodes(
        authentication: AuthenticationContext,
        clusterId: UUID,
    ): NodesResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "nodes"), NodesResponse.serializer()) { }
            ?: NodesResponse(emptyList())

    /**
     * Fetches recent events in [clusterId], optionally narrowed to a
     * single [namespace] / [level], and capped at [limit] rows.
     */
    suspend fun events(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
        level: EventLevel? = null,
        limit: Int? = null,
    ): EventsResponse =
        get(authentication, listOf("clusters", clusterId.toString(), "events"), EventsResponse.serializer()) {
            namespace?.let { addQueryParameter("namespace", it) }
            level?.let { addQueryParameter("level", it.name) }
            limit?.let { addQueryParameter("limit", it.toString()) }
        } ?: EventsResponse(emptyList())

    /**
     * Streams container log lines for a pod as a cold [Flow]. Each
     * record is one fully-parsed [LogLine].
     *
     * Transport:
     *  * NDJSON over chunked HTTP from `kubernetes-controller`.
     *  * The flow stays subscribed until either the upstream HTTP body
     *    ends (the controller caps streaming responses at 5 min for
     *    runaway protection) or the consumer cancels.
     *  * The dedicated [streamingHttp] client has no read timeout —
     *    quiet log periods are normal and should not collapse the
     *    stream the way the default 30s read timeout would.
     *
     * Authorization: the same minted-JWT pattern as every other
     * client call — the controller validates against the shared
     * `JWT_SECRET` and re-checks admin membership inside the route.
     */
    fun streamPodLogs(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String,
        pod: String,
        container: String? = null,
        tailLines: Int? = null,
        follow: Boolean? = null,
    ): Flow<LogLine> = channelFlow {
        val token = mintBearerToken(authentication)
        val url = buildUrl(listOf("clusters", clusterId.toString(), "pods", namespace, pod, "logs")) {
            container?.let { addQueryParameter("container", it) }
            tailLines?.let { addQueryParameter("tailLines", it.toString()) }
            follow?.let { addQueryParameter("follow", it.toString()) }
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        val response = streamingHttp.executeStreaming(request)
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            log.warn("kubernetes-controller log stream returned {} for {}/{}", code, namespace, pod)
            error("kubernetes-controller returned $code")
        }
        val body = response.body
        if (body == null) {
            response.close()
            return@channelFlow
        }

        val readJob = launch(Dispatchers.IO) {
            try {
                body.charStream().buffered().use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val record = runCatching { json.decodeFromString(LogLine.serializer(), line) }
                            .getOrElse {
                                log.debug("skipping unparseable log line ({} bytes): {}", line.length, it.message)
                                continue
                            }
                        send(record)
                    }
                }
            } finally {
                runCatching { response.close() }
                close()
            }
        }

        awaitClose {
            runCatching { response.close() }
            readJob.cancel()
        }
    }

    /**
     * Scales the named [DEPLOYMENT][WorkloadKind.DEPLOYMENT] /
     * [STATEFUL_SET][WorkloadKind.STATEFUL_SET] /
     * [REPLICA_SET][WorkloadKind.REPLICA_SET] to [replicas] pods.
     * Returns the freshly-mapped workload row — caller can render the
     * updated ready/desired delta without a follow-up list call.
     */
    suspend fun scaleWorkload(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String,
        kind: WorkloadKind,
        name: String,
        replicas: Int,
    ): Workload {
        require(replicas >= 0) { "replicas must be >= 0" }
        return post(
            authentication = authentication,
            pathSegments = listOf("clusters", clusterId.toString(), "workloads", kind.name, namespace, name, "scale"),
            body = ScaleWorkloadRequest(replicas),
            bodySerializer = ScaleWorkloadRequest.serializer(),
            responseSerializer = Workload.serializer(),
        ) ?: error("kubernetes-controller returned empty body for scaleWorkload")
    }

    /**
     * Triggers a rolling restart by stamping
     * `kubectl.kubernetes.io/restartedAt` on the pod template. Returns
     * `true` once the patch is committed; the actual rollout proceeds
     * asynchronously on the cluster.
     */
    suspend fun restartWorkload(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String,
        kind: WorkloadKind,
        name: String,
    ): Boolean = post(
        authentication = authentication,
        pathSegments = listOf("clusters", clusterId.toString(), "workloads", kind.name, namespace, name, "restart"),
        body = Unit,
        bodySerializer = null,
        responseSerializer = Boolean.serializer(),
    ) ?: false

    /**
     * Deletes a resource by `(group, version, kind, [namespace,] name)`.
     * `version` defaults to `v1` and `group` to the empty string (core
     * API). Returns the controller-side delete report.
     */
    suspend fun deleteResource(
        authentication: AuthenticationContext,
        clusterId: UUID,
        kind: String,
        name: String,
        namespace: String? = null,
        group: String? = null,
        version: String? = null,
    ): DeleteResponse {
        val token = mintBearerToken(authentication)
        val url = buildUrl(listOf("clusters", clusterId.toString(), "delete")) {
            addQueryParameter("kind", kind)
            addQueryParameter("name", name)
            namespace?.let { addQueryParameter("namespace", it) }
            group?.let { addQueryParameter("group", it) }
            version?.let { addQueryParameter("version", it) }
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(EMPTY_BODY)
            .build()
        return executeAndDecode(request, "clusters/$clusterId/delete", DeleteResponse.serializer())
            ?: DeleteResponse(deleted = false, details = "empty response")
    }

    /**
     * Creates a namespace with optional labels (forwarded to
     * `metadata.labels`). Returns the new wire-shape namespace.
     */
    suspend fun createNamespace(
        authentication: AuthenticationContext,
        clusterId: UUID,
        name: String,
        labels: JsonElement? = null,
    ): Namespace = post(
        authentication = authentication,
        pathSegments = listOf("clusters", clusterId.toString(), "namespaces"),
        body = CreateNamespaceRequest(name = name, labels = labels),
        bodySerializer = CreateNamespaceRequest.serializer(),
        responseSerializer = Namespace.serializer(),
    ) ?: error("kubernetes-controller returned empty body for createNamespace")

    /**
     * Applies a multi-doc YAML manifest via server-side apply, with
     * optional dry-run. Returns the controller's per-resource report.
     */
    suspend fun applyManifest(
        authentication: AuthenticationContext,
        clusterId: UUID,
        manifest: String,
        dryRun: Boolean = false,
    ): ApplyResult = post(
        authentication = authentication,
        pathSegments = listOf("clusters", clusterId.toString(), "apply"),
        body = ApplyManifestRequest(manifest = manifest, dryRun = dryRun),
        bodySerializer = ApplyManifestRequest.serializer(),
        responseSerializer = ApplyResult.serializer(),
    ) ?: ApplyResult(succeeded = false, applied = emptyList(), failed = emptyList(), dryRun = null)

    /**
     * Generic POST helper. Builds the URL, mints a fresh JWT, encodes
     * [body] as JSON (or empty when [bodySerializer] is null), and
     * decodes the response with [responseSerializer].
     */
    private suspend fun <Req : Any, Resp : Any> post(
        authentication: AuthenticationContext,
        pathSegments: List<String>,
        body: Req,
        bodySerializer: KSerializer<Req>?,
        responseSerializer: KSerializer<Resp>,
        configureQuery: okhttp3.HttpUrl.Builder.() -> Unit = {},
    ): Resp? {
        val token = mintBearerToken(authentication)
        val url = buildUrl(pathSegments, configureQuery)
        val requestBody = if (bodySerializer != null) {
            json.encodeToString(bodySerializer, body).toRequestBody(JSON_MEDIA_TYPE)
        } else {
            EMPTY_BODY
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(requestBody)
            .build()
        return executeAndDecode(request, pathSegments.joinToString("/"), responseSerializer)
    }

    /**
     * Starts a streaming call on the IO pool and returns its open response. A response the caller is
     * cancelled before receiving (withContext discards a finished result then) is closed, on the IO
     * pool, instead of leaking its connection.
     */
    private suspend fun OkHttpClient.executeStreaming(request: Request): Response {
        var opened: Response? = null
        try {
            return withContext(Dispatchers.IO) { newCall(request).execute().also { opened = it } }
        } catch (e: CancellationException) {
            opened?.let { response -> withContext(NonCancellable + Dispatchers.IO) { response.close() } }
            throw e
        }
    }

    private suspend fun <T : Any> executeAndDecode(
        request: Request,
        path: String,
        serializer: KSerializer<T>,
    ): T? {
        http.newCall(request).await().use { response ->
            if (response.code == 401 || response.code == 403) {
                log.warn("kubernetes-controller refused request to $path with ${response.code}")
                error("kubernetes-controller denied the request (${response.code})")
            }
            val text = withContext(Dispatchers.IO) { response.body.string() }
            if (!response.isSuccessful) {
                log.warn("kubernetes-controller returned ${response.code} for $path: ${text.take(200)}")
                error("kubernetes-controller returned ${response.code}")
            }
            return if (text.isBlank()) null else json.decodeFromString(serializer, text)
        }
    }

    /**
     * Streams events from [clusterId] as they're observed, narrowed
     * to [namespace] when supplied. Lifecycle and reconnection
     * mirror [streamPodLogs] — flow completes on upstream close, the
     * GraphQL subscription completes, the studio reconnects.
     */
    fun streamEvents(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
    ): Flow<K8sEvent> = streamNdjson(
        authentication = authentication,
        pathSegments = listOf("clusters", clusterId.toString(), "events", "watch"),
        recordSerializer = K8sEvent.serializer(),
    ) {
        namespace?.let { addQueryParameter("namespace", it) }
    }

    /**
     * Streams workload status changes for a single workload. Emits a
     * fresh [Workload] snapshot on every modification observed by the
     * controller's fabric8 watch.
     */
    fun streamWorkloadStatus(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String,
        kind: WorkloadKind,
        name: String,
    ): Flow<Workload> = streamNdjson(
        authentication = authentication,
        pathSegments = listOf("clusters", clusterId.toString(), "workloads", kind.name, namespace, name, "watch"),
        recordSerializer = Workload.serializer(),
    ) { }

    /**
     * Streams helm release status changes for a single release. Emits
     * a fresh [K8sHelmRelease] snapshot every time the underlying
     * `owner=helm` release Secret is added or modified — that covers
     * install / upgrade / rollback lifecycle transitions (pending →
     * deployed → superseded) without needing helm-side polling.
     */
    fun streamHelmReleaseStatus(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String,
        name: String,
    ): Flow<K8sHelmRelease> = streamNdjson(
        authentication = authentication,
        pathSegments = listOf("clusters", clusterId.toString(), "helm", "releases", namespace, name, "watch"),
        recordSerializer = K8sHelmRelease.serializer(),
    ) { }

    /**
     * Streams periodic pod CPU + memory samples. The controller
     * polls `metrics.k8s.io` at `intervalSec` (default 5s, clamped
     * server-side) and emits one [PodMetricsSample] per tick.
     */
    fun streamPodMetrics(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String,
        pod: String,
        intervalSec: Int? = null,
    ): Flow<bosca.kubernetes.model.PodMetricsSample> = streamNdjson(
        authentication = authentication,
        pathSegments = listOf("clusters", clusterId.toString(), "pods", namespace, pod, "metrics", "stream"),
        recordSerializer = bosca.kubernetes.model.PodMetricsSample.serializer(),
    ) {
        intervalSec?.let { addQueryParameter("intervalSec", it.toString()) }
    }

    /** Streams periodic node CPU + memory samples for a single node. */
    fun streamNodeMetrics(
        authentication: AuthenticationContext,
        clusterId: UUID,
        node: String,
        intervalSec: Int? = null,
    ): Flow<bosca.kubernetes.model.NodeMetricsSample> = streamNdjson(
        authentication = authentication,
        pathSegments = listOf("clusters", clusterId.toString(), "nodes", node, "metrics", "stream"),
        recordSerializer = bosca.kubernetes.model.NodeMetricsSample.serializer(),
    ) {
        intervalSec?.let { addQueryParameter("intervalSec", it.toString()) }
    }

    /** Streams periodic cluster-wide aggregate metric samples. */
    fun streamClusterMetrics(
        authentication: AuthenticationContext,
        clusterId: UUID,
        intervalSec: Int? = null,
    ): Flow<bosca.kubernetes.model.ClusterMetricsSample> = streamNdjson(
        authentication = authentication,
        pathSegments = listOf("clusters", clusterId.toString(), "metrics", "stream"),
        recordSerializer = bosca.kubernetes.model.ClusterMetricsSample.serializer(),
    ) {
        intervalSec?.let { addQueryParameter("intervalSec", it.toString()) }
    }

    /**
     * Streams per-pod cpu/memory snapshots for an entire cluster (or
     * one namespace). Each tick carries the complete set so the studio
     * merges by pod UID without delta reconciliation.
     */
    fun streamPodsListMetrics(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
        intervalSec: Int? = null,
    ): Flow<bosca.kubernetes.model.PodsMetricsListSample> = streamNdjson(
        authentication = authentication,
        pathSegments = listOf("clusters", clusterId.toString(), "pods", "metrics", "list-stream"),
        recordSerializer = bosca.kubernetes.model.PodsMetricsListSample.serializer(),
    ) {
        namespace?.let { addQueryParameter("namespace", it) }
        intervalSec?.let { addQueryParameter("intervalSec", it.toString()) }
    }

    /** Streams a per-node cpu/memory snapshot per tick. */
    fun streamNodesListMetrics(
        authentication: AuthenticationContext,
        clusterId: UUID,
        intervalSec: Int? = null,
    ): Flow<bosca.kubernetes.model.NodesMetricsListSample> = streamNdjson(
        authentication = authentication,
        pathSegments = listOf("clusters", clusterId.toString(), "nodes", "metrics", "list-stream"),
        recordSerializer = bosca.kubernetes.model.NodesMetricsListSample.serializer(),
    ) {
        intervalSec?.let { addQueryParameter("intervalSec", it.toString()) }
    }

    /**
     * Streams per-workload cpu/memory aggregates (ownership-walked
     * across ReplicaSet→Deployment and Job→CronJob).
     */
    fun streamWorkloadsListMetrics(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
        intervalSec: Int? = null,
    ): Flow<bosca.kubernetes.model.WorkloadsMetricsListSample> = streamNdjson(
        authentication = authentication,
        pathSegments = listOf("clusters", clusterId.toString(), "workloads", "metrics", "list-stream"),
        recordSerializer = bosca.kubernetes.model.WorkloadsMetricsListSample.serializer(),
    ) {
        namespace?.let { addQueryParameter("namespace", it) }
        intervalSec?.let { addQueryParameter("intervalSec", it.toString()) }
    }

    /**
     * Streams per-workload status snapshots — built from the workload
     * objects themselves, so scaled-to-zero / failed workloads still
     * appear (unlike the metrics list, whose rows are derived from pods
     * that have usage).
     */
    fun streamWorkloadsListStatus(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String? = null,
        intervalSec: Int? = null,
    ): Flow<bosca.kubernetes.model.WorkloadsStatusListSample> = streamNdjson(
        authentication = authentication,
        pathSegments = listOf("clusters", clusterId.toString(), "workloads", "status", "list-stream"),
        recordSerializer = bosca.kubernetes.model.WorkloadsStatusListSample.serializer(),
    ) {
        namespace?.let { addQueryParameter("namespace", it) }
        intervalSec?.let { addQueryParameter("intervalSec", it.toString()) }
    }

    /**
     * Streams a merged log tail across every pod that belongs to a
     * workload (selector-based). Each NDJSON [LogLine] carries the
     * source pod's name so the studio can colour-by-pod in one view.
     */
    fun streamWorkloadLogs(
        authentication: AuthenticationContext,
        clusterId: UUID,
        namespace: String,
        kind: WorkloadKind,
        name: String,
        tailLines: Int? = null,
        follow: Boolean? = null,
    ): Flow<LogLine> = streamNdjson(
        authentication = authentication,
        pathSegments = listOf("clusters", clusterId.toString(), "workloads", namespace, kind.name, name, "logs"),
        recordSerializer = LogLine.serializer(),
    ) {
        tailLines?.let { addQueryParameter("tailLines", it.toString()) }
        follow?.let { addQueryParameter("follow", it.toString()) }
    }

    /**
     * Streams metadata-only change notifications for a set of resource
     * kinds — one watch stream per studio page, regardless of how many
     * kinds the page renders. Each [bosca.kubernetes.model.ResourceChangeEvent]
     * is a refresh trigger; the studio re-runs its list query rather
     * than reconciling deltas.
     */
    fun streamResourceChanges(
        authentication: AuthenticationContext,
        clusterId: UUID,
        kinds: List<String>,
        namespace: String? = null,
    ): Flow<bosca.kubernetes.model.ResourceChangeEvent> = streamNdjson(
        authentication = authentication,
        pathSegments = listOf("clusters", clusterId.toString(), "resources", "watch"),
        recordSerializer = bosca.kubernetes.model.ResourceChangeEvent.serializer(),
    ) {
        addQueryParameter("kinds", kinds.joinToString(","))
        namespace?.let { addQueryParameter("namespace", it) }
    }

    /**
     * Generic NDJSON streamer — used by every subscription that rides
     * the controller's chunked-HTTP transport. Refactored out of
     * `streamPodLogs` so events / workload-status / future helm-status
     * streams share the same lifecycle and cancellation handling.
     */
    private fun <T : Any> streamNdjson(
        authentication: AuthenticationContext,
        pathSegments: List<String>,
        recordSerializer: KSerializer<T>,
        configureQuery: okhttp3.HttpUrl.Builder.() -> Unit,
    ): Flow<T> = channelFlow {
        val token = mintBearerToken(authentication)
        val url = buildUrl(pathSegments, configureQuery)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val path = pathSegments.joinToString("/")
        val response = streamingHttp.executeStreaming(request)
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            log.warn("kubernetes-controller stream returned {} for {}", code, path)
            error("kubernetes-controller returned $code")
        }
        val body = response.body
        if (body == null) {
            response.close()
            return@channelFlow
        }

        val readJob = launch(Dispatchers.IO) {
            try {
                body.charStream().buffered().use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val record = runCatching { json.decodeFromString(recordSerializer, line) }
                            .getOrElse {
                                log.debug("skipping unparseable NDJSON line on {} ({} bytes): {}", path, line.length, it.message)
                                continue
                            }
                        send(record)
                    }
                }
            } finally {
                runCatching { response.close() }
                close()
            }
        }

        awaitClose {
            runCatching { response.close() }
            readJob.cancel()
        }
    }

    /**
     * Signals the controller that the cached fabric8 client for
     * [clusterId] should be dropped — used after the kubeconfig is
     * rotated or the cluster is removed so the next read rebuilds
     * with fresh credentials (or fails fast on a removed cluster).
     *
     * Failures here are logged but not surfaced: the in-process cache
     * on the controller has a finite TTL once a credential becomes
     * unreachable, so a missed invalidation is annoying but not
     * unsafe. The mutation flow on bosca-server has already committed
     * by the time we call this.
     */
    suspend fun invalidate(authentication: AuthenticationContext, clusterId: UUID) {
        val token = mintBearerToken(authentication)
        val url = buildUrl(listOf("clusters", clusterId.toString(), "invalidate"))
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post("".toRequestBody())
            .build()
        // The dispatcher switch sits outside runCatching so cancellation still propagates.
        withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        log.warn(
                            "kubernetes-controller invalidate returned {} for cluster {}",
                            response.code, clusterId,
                        )
                    }
                }
            }.onFailure {
                log.warn("kubernetes-controller invalidate failed for cluster {}: {}", clusterId, it.message)
            }
        }
    }

    /**
     * Common GET request shape. Builds the URL, mints a fresh JWT for
     * the caller, applies the supplied query-parameter block, decodes
     * the response with [serializer], and surfaces non-2xx as a thrown
     * error so the resolver can map it to a GraphQL field error.
     *
     * A missing principal is treated as a programming error (the
     * resolver layer is supposed to reject anonymous callers long
     * before we reach this method); we throw an
     * [IllegalStateException] rather than silently calling without
     * auth, so a regression in the resolver code surfaces as a 500
     * to the client rather than a privileged backend call.
     */
    private suspend fun <T : Any> get(
        authentication: AuthenticationContext,
        pathSegments: List<String>,
        serializer: KSerializer<T>,
        configureQuery: okhttp3.HttpUrl.Builder.() -> Unit,
    ): T? {
        val token = mintBearerToken(authentication)
        val url = buildUrl(pathSegments, configureQuery)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val pathForLogging = pathSegments.joinToString("/")
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    log.warn("kubernetes-controller refused request to $pathForLogging with ${response.code}")
                    error("kubernetes-controller denied the request (${response.code})")
                }
                if (!response.isSuccessful) {
                    val snippet = response.body.string().take(200)
                    log.warn("kubernetes-controller returned ${response.code} for $pathForLogging: $snippet")
                    error("kubernetes-controller returned ${response.code}")
                }
                val body = response.body.string()
                json.decodeFromString(serializer, body)
            }
        }
    }

    /**
     * Builds an `HttpUrl` from [pathSegments] by feeding each segment
     * through [HttpUrl.Builder.addPathSegment]. Every segment — even the
     * "literal" ones from caller code — is percent-encoded, so a
     * user-controlled value like `name = "foo/delete?cluster=x"`
     * lands as a single opaque path segment rather than mutating the
     * URL shape or smuggling in extra query parameters.
     *
     * `.` / `..` segments are rejected outright. OkHttp's `addPathSegment`
     * always interprets them as path navigation (skip / pop-previous),
     * regardless of percent-encoding — `..` would silently drop the
     * preceding segment and let a caller route the request to a
     * different controller endpoint. No legitimate k8s resource name,
     * helm release name, namespace, repo name, or UUID equals `.` or
     * `..`, so the strict rejection is safe.
     *
     * Resolver-layer admin gating prevents anonymous callers from
     * reaching this method, but defense in depth says we should never
     * trust upstream input to be URL-safe regardless.
     */
    private fun buildUrl(
        pathSegments: List<String>,
        configureQuery: okhttp3.HttpUrl.Builder.() -> Unit = {},
    ): okhttp3.HttpUrl {
        val builder = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
        for (segment in pathSegments) {
            require(segment.isNotEmpty()) { "URL path segment must not be empty" }
            require(!isDotOrDotDot(segment)) {
                "URL path segment must not be '.' or '..'"
            }
            builder.addPathSegment(segment)
        }
        builder.configureQuery()
        return builder.build()
    }

    /**
     * Matches `.` and `..` along with their percent-encoded variants
     * (`%2e`, `%2e%2e`, mixed case). OkHttp's builder canonicalises
     * those into the same dot/dot-dot navigation behaviour, so the
     * check has to cover both literal and pre-encoded forms.
     */
    private fun isDotOrDotDot(segment: String): Boolean {
        val lower = segment.lowercase()
        return lower == "." || lower == ".." ||
            lower == "%2e" || lower == "%2e%2e" ||
            lower == ".%2e" || lower == "%2e."
    }

    /**
     * Mints a fresh short-lived JWT representing the calling user. The
     * token is signed with the shared `JWT_SECRET` so kubernetes-
     * controller validates it natively and resolves the same
     * `AuthenticatedPrincipal` we had here.
     *
     * Throws when called without an authenticated principal — see the
     * note on [get] for the design rationale.
     */
    private suspend fun mintBearerToken(authentication: AuthenticationContext): String {
        val principal = authentication.principal()
            ?: error("kubernetes-controller call attempted without an authenticated principal")
        return securityService.createJwtToken(principal.asPrincipal(), emptyMap()).token
    }

    companion object {
        private val log = LoggerFactory.getLogger(KubernetesControllerClient::class.java)
        const val DEFAULT_BASE_URL = "http://kubernetes-controller:8082"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val EMPTY_BODY = "".toRequestBody(JSON_MEDIA_TYPE)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // await() (enqueue) calls go through OkHttp's dispatcher, which allows only 5 at once
            // per host by default; every call here targets the one controller host, so a Studio page
            // resolving more fields than that would queue behind the slowest. The controller is
            // internal, so allow far more in flight.
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = maxRequests })
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(30))
            .build()

        /**
         * Streaming client derived from [base]. Identical connect /
         * write settings, but with zero read timeout so a quiet log
         * stream (no new lines for minutes) does not collapse.
         */
        fun streamingClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
            .readTimeout(Duration.ZERO)
            .callTimeout(Duration.ZERO)
            .build()
    }
}

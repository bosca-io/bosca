package bosca.kubernetes.service

import bosca.kubernetes.model.EventLevel
import bosca.kubernetes.model.WorkloadKind
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import com.auth0.jwt.interfaces.DecodedJWT
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.ExperimentalUuidApi

/**
 * Exhaustive endpoint coverage for [KubernetesControllerClient]. The
 * studio's read-side reaches the controller through this client, so the
 * URL paths and query-parameter spelling here are part of the system's
 * contract. Each test enqueues a minimum-viable JSON response and then
 * asserts the *request* shape — that's where the regression risk lives.
 *
 * The helpers `enqueueEmptyList()` / `enqueueOk(json)` keep the
 * arrange-act steps tight so the focus stays on the request URL.
 */
@OptIn(ExperimentalUuidApi::class)
class KubernetesControllerClientEndpointsTest {

    private val mockServer = MockWebServer().apply { start() }
    private val baseUrl = mockServer.url("").toString().trimEnd('/')
    private val clusterId = UUID.random()

    private val security = mockk<SecurityService>()
    private val ctx = mockk<AuthenticationContext>()
    private val principal = mockk<AuthenticatedPrincipal>()
    private val decoded = mockk<DecodedJWT>()

    init {
        every { decoded.token } returns "jwt-token"
        every { principal.asPrincipal() } returns Principal()
        every { ctx.principal() } returns principal
        coEvery { security.createJwtToken(any(), any()) } returns decoded
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(Duration.ofMillis(500))
        .readTimeout(Duration.ofMillis(500))
        .build()

    private val client = KubernetesControllerClient(
        baseUrl = baseUrl,
        securityService = security,
        http = http,
        streamingHttp = http,
    )

    @AfterTest
    fun tearDown() {
        mockServer.close()
        io.mockk.unmockkAll()
    }

    private fun enqueueEmptyList() {
        mockServer.enqueue(MockResponse.Builder().code(200).body("""{"items":[]}""").build())
    }

    private fun enqueueOk(json: String) {
        mockServer.enqueue(MockResponse.Builder().code(200).body(json).build())
    }

    private fun nextRequestTarget(): String {
        val req = mockServer.takeRequest()
        return req.target ?: fail("no request observed")
    }

    // ===== Simple list endpoints with optional namespace =====

    @Test
    fun `services GET path and namespace query`() = runTest {
        enqueueEmptyList()
        client.services(ctx, clusterId, namespace = "prod")
        val t = nextRequestTarget()
        assertTrue(t.startsWith("/clusters/$clusterId/services"))
        assertTrue(t.contains("namespace=prod"))
    }

    @Test
    fun `services GET without namespace omits the query parameter`() = runTest {
        enqueueEmptyList()
        client.services(ctx, clusterId)
        val t = nextRequestTarget()
        assertEquals("/clusters/$clusterId/services", t)
    }

    @Test
    fun `ingresses GET path`() = runTest {
        enqueueEmptyList()
        client.ingresses(ctx, clusterId, namespace = "ns")
        val t = nextRequestTarget()
        assertTrue(t.startsWith("/clusters/$clusterId/ingresses"))
        assertTrue(t.contains("namespace=ns"))
    }

    @Test
    fun `networkPolicies GET path`() = runTest {
        enqueueEmptyList()
        client.networkPolicies(ctx, clusterId, namespace = "ns")
        assertTrue(nextRequestTarget().contains("/networkpolicies"))
    }

    @Test
    fun `storageClasses GET path`() = runTest {
        enqueueEmptyList()
        client.storageClasses(ctx, clusterId)
        assertEquals("/clusters/$clusterId/storageclasses", nextRequestTarget())
    }

    @Test
    fun `pvcs GET path`() = runTest {
        enqueueEmptyList()
        client.pvcs(ctx, clusterId, namespace = "ns")
        val t = nextRequestTarget()
        assertTrue(t.contains("/pvcs"))
        assertTrue(t.contains("namespace=ns"))
    }

    @Test
    fun `roles GET path with kind filter`() = runTest {
        enqueueEmptyList()
        client.roles(ctx, clusterId, kind = "ClusterRole")
        val t = nextRequestTarget()
        assertTrue(t.contains("/roles"))
        assertTrue(t.contains("kind=ClusterRole"))
    }

    @Test
    fun `roleBindings GET path with kind filter`() = runTest {
        enqueueEmptyList()
        client.roleBindings(ctx, clusterId, kind = "ClusterRoleBinding")
        val t = nextRequestTarget()
        assertTrue(t.contains("/rolebindings"))
        assertTrue(t.contains("kind=ClusterRoleBinding"))
    }

    @Test
    fun `serviceAccounts GET path`() = runTest {
        enqueueEmptyList()
        client.serviceAccounts(ctx, clusterId, namespace = "ns")
        assertTrue(nextRequestTarget().contains("/serviceaccounts"))
    }

    @Test
    fun `operators GET path`() = runTest {
        enqueueEmptyList()
        client.operators(ctx, clusterId)
        assertEquals("/clusters/$clusterId/operators", nextRequestTarget())
    }

    @Test
    fun `customResources GET path with group and namespace`() = runTest {
        enqueueEmptyList()
        client.customResources(ctx, clusterId, group = "cert-manager.io", namespace = "ns")
        val t = nextRequestTarget()
        assertTrue(t.contains("/customresources"))
        assertTrue(t.contains("group=cert-manager.io"))
        assertTrue(t.contains("namespace=ns"))
    }

    // ===== Gateway API list endpoints =====

    @Test
    fun `gatewayClasses GET path`() = runTest {
        enqueueEmptyList()
        client.gatewayClasses(ctx, clusterId)
        assertEquals("/clusters/$clusterId/gatewayclasses", nextRequestTarget())
    }

    @Test
    fun `gateways GET path`() = runTest {
        enqueueEmptyList()
        client.gateways(ctx, clusterId, namespace = "net")
        assertTrue(nextRequestTarget().contains("/gateways"))
    }

    @Test
    fun `httpRoutes GET path`() = runTest {
        enqueueEmptyList()
        client.httpRoutes(ctx, clusterId, namespace = "net")
        assertTrue(nextRequestTarget().contains("/httproutes"))
    }

    // ===== cert-manager / Cilium / CNPG =====

    @Test
    fun `certManagerCertificates GET path`() = runTest {
        enqueueEmptyList()
        client.certManagerCertificates(ctx, clusterId, namespace = "ns")
        assertTrue(nextRequestTarget().contains("/certmanager/certificates"))
    }

    @Test
    fun `certManagerIssuers GET path`() = runTest {
        enqueueEmptyList()
        client.certManagerIssuers(ctx, clusterId, namespace = "ns")
        assertTrue(nextRequestTarget().contains("/certmanager/issuers"))
    }

    @Test
    fun `cnpgClusters GET path`() = runTest {
        enqueueEmptyList()
        client.cnpgClusters(ctx, clusterId, namespace = "data")
        assertTrue(nextRequestTarget().contains("/cnpg/clusters"))
    }

    @Test
    fun `cnpgClusterDetail GET path includes namespace and name segments`() = runTest {
        enqueueOk("{}")
        client.cnpgClusterDetail(ctx, clusterId, namespace = "data", name = "pg")
        assertTrue(nextRequestTarget().contains("/cnpg/clusters/data/pg"))
    }

    // ===== Helm catalog (cluster-scoped vs not) =====

    @Test
    fun `helmRepos GET path is cluster-independent`() = runTest {
        enqueueEmptyList()
        client.helmRepos(ctx)
        assertEquals("/helm/repos", nextRequestTarget())
    }

    @Test
    fun `helmCharts GET path with repo and search`() = runTest {
        enqueueEmptyList()
        client.helmCharts(ctx, repo = "bitnami", search = "redis")
        val t = nextRequestTarget()
        assertTrue(t.startsWith("/helm/charts"))
        assertTrue(t.contains("repo=bitnami"))
        assertTrue(t.contains("search=redis"))
    }

    @Test
    fun `helmChartVersions GET path encodes repo and chart in the path`() = runTest {
        enqueueEmptyList()
        client.helmChartVersions(ctx, repo = "bitnami", chart = "redis")
        assertEquals("/helm/repos/bitnami/charts/redis/versions", nextRequestTarget())
    }

    @Test
    fun `helmChartValues GET path encodes version in the path`() = runTest {
        enqueueOk("""{"defaultValues":"","schema":null}""")
        client.helmChartValues(ctx, repo = "bitnami", chart = "redis", version = "18.0.0")
        assertEquals("/helm/repos/bitnami/charts/redis/versions/18.0.0/values", nextRequestTarget())
    }

    @Test
    fun `helmReleases GET path is cluster-scoped`() = runTest {
        enqueueEmptyList()
        client.helmReleases(ctx, clusterId, namespace = "prod")
        val t = nextRequestTarget()
        assertTrue(t.startsWith("/clusters/$clusterId/helm/releases"))
        assertTrue(t.contains("namespace=prod"))
    }

    @Test
    fun `helmReleaseHistory GET path encodes namespace and name`() = runTest {
        enqueueEmptyList()
        client.helmReleaseHistory(ctx, clusterId, namespace = "prod", name = "podinfo")
        assertEquals("/clusters/$clusterId/helm/releases/prod/podinfo/history", nextRequestTarget())
    }

    @Test
    fun `helmReleaseValues passes revision query when set`() = runTest {
        enqueueOk("""{"yaml":"replicaCount: 3"}""")
        val out = client.helmReleaseValues(ctx, clusterId, "prod", "podinfo", revision = 4)
        assertEquals("replicaCount: 3", out)
        val t = nextRequestTarget()
        assertTrue(t.startsWith("/clusters/$clusterId/helm/releases/prod/podinfo/values"))
        assertTrue(t.contains("revision=4"))
    }

    @Test
    fun `helmReleaseValues omits revision when null`() = runTest {
        enqueueOk("""{"yaml":""}""")
        client.helmReleaseValues(ctx, clusterId, "prod", "podinfo")
        val t = nextRequestTarget()
        assertTrue(t.startsWith("/clusters/$clusterId/helm/releases/prod/podinfo/values"))
        assertTrue(!t.contains("revision="))
    }

    @Test
    fun `helmReleaseManifest passes revision query when set`() = runTest {
        enqueueOk("""{"yaml":"---\nkind: Deployment"}""")
        val out = client.helmReleaseManifest(ctx, clusterId, "prod", "podinfo", revision = 7)
        assertTrue(out.contains("Deployment"))
        val t = nextRequestTarget()
        assertTrue(t.contains("/manifest"))
        assertTrue(t.contains("revision=7"))
    }

    // ===== Helm mutations =====

    @Test
    fun `helmRepoAdd posts to helm slash repos with the body`() = runTest {
        enqueueOk("""{"name":"bitnami","url":"https://charts.bitnami.com/bitnami","type":"https","lastUpdate":"-","charts":0}""")
        client.helmRepoAdd(ctx, name = "bitnami", url = "https://charts.bitnami.com/bitnami")
        val req = mockServer.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/helm/repos", req.target)
        val body = req.body?.utf8().orEmpty()
        assertTrue(body.contains("\"name\":\"bitnami\""))
        assertTrue(body.contains("https://charts.bitnami.com/bitnami"))
    }

    @Test
    fun `helmRepoUpdate posts an empty body to helm slash repos slash refresh`() = runTest {
        enqueueEmptyList()
        client.helmRepoUpdate(ctx)
        val req = mockServer.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/helm/repos/refresh", req.target)
        assertEquals("", req.body?.utf8().orEmpty())
    }

    @Test
    fun `helmInstall POST path with install body`() = runTest {
        enqueueOk(K8S_HELM_RELEASE_JSON)
        client.helmInstall(
            ctx, clusterId,
            name = "podinfo", namespace = "prod", createNamespace = true,
            repo = "podinfo", chart = "podinfo", version = "6.11.2",
        )
        val req = mockServer.takeRequest()
        assertEquals("/clusters/$clusterId/helm/install", req.target)
        val body = req.body?.utf8().orEmpty()
        assertTrue(body.contains("\"createNamespace\":true"))
        assertTrue(body.contains("\"version\":\"6.11.2\""))
    }

    @Test
    fun `helmUpgrade POST appends repo and chart as query parameters`() = runTest {
        enqueueOk(K8S_HELM_RELEASE_JSON)
        client.helmUpgrade(
            ctx, clusterId,
            name = "podinfo", namespace = "prod", version = "6.11.3",
            repo = "podinfo", chart = "podinfo",
            resetValues = true,
        )
        val req = mockServer.takeRequest()
        val t = req.target!!
        assertTrue(t.startsWith("/clusters/$clusterId/helm/upgrade"))
        assertTrue(t.contains("repo=podinfo"))
        assertTrue(t.contains("chart=podinfo"))
        val body = req.body?.utf8().orEmpty()
        assertTrue(body.contains("\"resetValues\":true"))
        assertTrue(body.contains("\"version\":\"6.11.3\""))
    }

    @Test
    fun `helmRollback POST sends the toRevision in the body`() = runTest {
        enqueueOk(K8S_HELM_RELEASE_JSON)
        client.helmRollback(ctx, clusterId, "prod", "podinfo", toRevision = 2)
        val req = mockServer.takeRequest()
        assertEquals("/clusters/$clusterId/helm/rollback", req.target)
        assertTrue(req.body?.utf8().orEmpty().contains("\"toRevision\":2"))
    }

    @Test
    fun `helmUninstall sends DELETE with keepHistory query when true`() = runTest {
        enqueueOk("true")
        client.helmUninstall(ctx, clusterId, "prod", "podinfo", keepHistory = true)
        val req = mockServer.takeRequest()
        assertEquals("DELETE", req.method)
        val t = req.target!!
        assertTrue(t.startsWith("/clusters/$clusterId/helm/releases/prod/podinfo"))
        assertTrue(t.contains("keepHistory=true"))
    }

    @Test
    fun `helmUninstall omits keepHistory query when false`() = runTest {
        enqueueOk("true")
        client.helmUninstall(ctx, clusterId, "prod", "podinfo")
        val req = mockServer.takeRequest()
        assertEquals("DELETE", req.method)
        val t = req.target!!
        assertTrue(!t.contains("keepHistory"))
    }

    @Test
    fun `helmRepoRemove sends DELETE to helm slash repos slash name`() = runTest {
        enqueueOk("true")
        client.helmRepoRemove(ctx, name = "bitnami")
        val req = mockServer.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/helm/repos/bitnami", req.target)
    }

    // ===== Workloads / pods / nodes / events =====

    @Test
    fun `workloads GET path with both filters`() = runTest {
        enqueueEmptyList()
        client.workloads(ctx, clusterId, namespace = "ns", kind = WorkloadKind.DEPLOYMENT)
        val t = nextRequestTarget()
        assertTrue(t.contains("namespace=ns"))
        assertTrue(t.contains("kind=DEPLOYMENT"))
    }

    @Test
    fun `pods GET path with pagination query parameters`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(200).body("""{"total":0,"items":[]}""").build())
        client.pods(
            ctx, clusterId,
            namespace = "ns", workloadId = "w1", search = "foo",
            limit = 50, offset = 100,
        )
        val t = nextRequestTarget()
        assertTrue(t.contains("namespace=ns"))
        assertTrue(t.contains("workloadId=w1"))
        assertTrue(t.contains("search=foo"))
        assertTrue(t.contains("limit=50"))
        assertTrue(t.contains("offset=100"))
    }

    @Test
    fun `nodes GET path is cluster-scoped with no query`() = runTest {
        enqueueEmptyList()
        client.nodes(ctx, clusterId)
        assertEquals("/clusters/$clusterId/nodes", nextRequestTarget())
    }

    @Test
    fun `events GET path with level filter`() = runTest {
        enqueueEmptyList()
        client.events(ctx, clusterId, level = EventLevel.WARN, limit = 100)
        val t = nextRequestTarget()
        assertTrue(t.contains("level=WARN"))
        assertTrue(t.contains("limit=100"))
    }

    // ===== Resource ops =====

    @Test
    fun `scaleWorkload POST path encodes kind namespace and name`() = runTest {
        enqueueOk(WORKLOAD_JSON)
        client.scaleWorkload(ctx, clusterId, "ns", WorkloadKind.STATEFUL_SET, "db", replicas = 3)
        val req = mockServer.takeRequest()
        assertEquals("/clusters/$clusterId/workloads/STATEFUL_SET/ns/db/scale", req.target)
        assertTrue(req.body?.utf8().orEmpty().contains("\"replicas\":3"))
    }

    @Test
    fun `scaleWorkload rejects negative replicas before issuing a request`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            client.scaleWorkload(ctx, clusterId, "ns", WorkloadKind.DEPLOYMENT, "api", replicas = -1)
        }
        // No request should have been issued.
        assertEquals(0, mockServer.requestCount)
    }

    @Test
    fun `restartWorkload POST path encodes kind namespace and name`() = runTest {
        enqueueOk("true")
        client.restartWorkload(ctx, clusterId, "ns", WorkloadKind.DEPLOYMENT, "api")
        val req = mockServer.takeRequest()
        assertEquals("/clusters/$clusterId/workloads/DEPLOYMENT/ns/api/restart", req.target)
    }

    @Test
    fun `deleteResource POST encodes all selectors as query parameters`() = runTest {
        enqueueOk("""{"deleted":true,"details":"removed"}""")
        client.deleteResource(
            ctx, clusterId,
            kind = "Certificate", name = "tls",
            namespace = "prod", group = "cert-manager.io", version = "v1",
        )
        val req = mockServer.takeRequest()
        val t = req.target!!
        assertEquals("POST", req.method)
        assertTrue(t.startsWith("/clusters/$clusterId/delete"))
        assertTrue(t.contains("kind=Certificate"))
        assertTrue(t.contains("name=tls"))
        assertTrue(t.contains("namespace=prod"))
        assertTrue(t.contains("group=cert-manager.io"))
        assertTrue(t.contains("version=v1"))
    }

    @Test
    fun `createNamespace POST sends the name in the body`() = runTest {
        enqueueOk("""{"name":"new","status":"Active","age":"0s"}""")
        client.createNamespace(ctx, clusterId, name = "new")
        val req = mockServer.takeRequest()
        assertEquals("/clusters/$clusterId/namespaces", req.target)
        assertTrue(req.body?.utf8().orEmpty().contains("\"name\":\"new\""))
    }

    // ===== Streams =====

    @Test
    fun `streamPodLogs builds URL with container tailLines and follow query parameters`() = runTest {
        // Empty body — flow completes immediately.
        mockServer.enqueue(MockResponse.Builder().code(200).body("").build())
        val flow = client.streamPodLogs(ctx, clusterId, "ns", "p", container = "c", tailLines = 50, follow = true)
        flow.collect { /* drain */ }
        val req = mockServer.takeRequest()
        val t = req.target!!
        assertTrue(t.startsWith("/clusters/$clusterId/pods/ns/p/logs"))
        assertTrue(t.contains("container=c"))
        assertTrue(t.contains("tailLines=50"))
        assertTrue(t.contains("follow=true"))
    }

    @Test
    fun `streamPodLogs surfaces non-2xx as an error inside the flow`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(500).body("").build())
        val flow = client.streamPodLogs(ctx, clusterId, "ns", "p")
        val ex = assertFailsWith<IllegalStateException> {
            flow.collect { /* drain */ }
        }
        assertTrue(ex.message!!.contains("500"))
    }

    @Test
    fun `streamEvents builds URL with optional namespace`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(200).body("").build())
        val flow = client.streamEvents(ctx, clusterId, namespace = "ns")
        flow.collect { /* drain */ }
        val req = mockServer.takeRequest()
        assertTrue(req.target!!.startsWith("/clusters/$clusterId/events/watch"))
        assertTrue(req.target!!.contains("namespace=ns"))
    }

    @Test
    fun `streamWorkloadStatus builds URL with kind namespace name`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(200).body("").build())
        val flow = client.streamWorkloadStatus(ctx, clusterId, "ns", WorkloadKind.DEPLOYMENT, "api")
        flow.collect { /* drain */ }
        val req = mockServer.takeRequest()
        assertEquals("/clusters/$clusterId/workloads/DEPLOYMENT/ns/api/watch", req.target)
    }

    @Test
    fun `streamHelmReleaseStatus builds URL with namespace and name`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(200).body("").build())
        val flow = client.streamHelmReleaseStatus(ctx, clusterId, "prod", "podinfo")
        flow.collect { /* drain */ }
        val req = mockServer.takeRequest()
        assertEquals("/clusters/$clusterId/helm/releases/prod/podinfo/watch", req.target)
    }

    @Test
    fun `yaml GET request encodes all selectors as query parameters`() = runTest {
        enqueueOk("""{"yaml":"kind: Pod\n"}""")
        client.yaml(
            ctx, clusterId,
            kind = "Pod", name = "p", namespace = "ns", group = "", version = "v1",
        )
        val t = nextRequestTarget()
        assertTrue(t.startsWith("/clusters/$clusterId/yaml"))
        assertTrue(t.contains("kind=Pod"))
        assertTrue(t.contains("name=p"))
        assertTrue(t.contains("namespace=ns"))
        assertTrue(t.contains("version=v1"))
    }

    @Test
    fun `configEntries GET request encodes kind namespace and name in the path`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(200).body("""{"items":[]}""").build())
        client.configEntries(
            ctx, clusterId,
            kind = bosca.kubernetes.model.ConfigKind.SECRET,
            namespace = "ns", name = "tls",
        )
        assertEquals("/clusters/$clusterId/config/SECRET/ns/tls/entries", nextRequestTarget())
    }

    // ===== Path-segment encoding: malicious values must not mutate the URL =====
    //
    // Every user-controlled string forwarded as a path segment (namespace,
    // pod, helm release name, repo name, etc.) is run through
    // `HttpUrl.Builder.addPathSegment`, which percent-encodes `/`, `?`,
    // `#`, and anything else with URL semantics. These tests pin that
    // contract — without it, a malicious `name = "foo?keepHistory=true"`
    // could smuggle in a query parameter, or `"../delete"` could fold
    // into a wrong route after OkHttp's URL normalisation.

    @Test
    fun `helmRepoRemove encodes a slash-bearing name as a single path segment`() = runTest {
        enqueueOk("true")
        client.helmRepoRemove(ctx, name = "evil/charts")
        val t = nextRequestTarget()
        assertEquals("/helm/repos/evil%2Fcharts", t)
    }

    @Test
    fun `helmRepoRemove encodes a question mark in the name without injecting a query parameter`() = runTest {
        enqueueOk("true")
        client.helmRepoRemove(ctx, name = "foo?keepHistory=true")
        val t = nextRequestTarget()
        // The whole `foo?keepHistory=true` value has to land inside the
        // path — the raw '?' must never become a query separator, or
        // an attacker would be able to inject arbitrary query params.
        assertTrue(t.startsWith("/helm/repos/"))
        assertTrue(!t.contains("?keepHistory=true"), "raw '?' must not survive into the URL: $t")
    }

    @Test
    fun `helmUninstall encodes namespace and name segments`() = runTest {
        enqueueOk("true")
        client.helmUninstall(ctx, clusterId, namespace = "evil/ns", name = "foo?keepHistory=true")
        val t = nextRequestTarget()
        // No literal '/' should appear inside the namespace segment, and
        // no raw '?' should escape into a query parameter — the only
        // legitimate query keys are the ones the helper itself sets.
        assertTrue(t.startsWith("/clusters/$clusterId/helm/releases/"))
        assertTrue(!t.contains("/evil/ns/"), "raw '/' in namespace must be encoded: $t")
        assertTrue(!t.contains("?keepHistory=true"), "raw '?' must not survive into the URL: $t")
    }

    @Test
    fun `configEntries rejects a dot-dot namespace segment`() = runTest {
        // No response needs to be enqueued because the request must
        // never reach the wire — the URL builder rejects '..' first.
        assertFailsWith<IllegalArgumentException> {
            client.configEntries(
                ctx, clusterId,
                kind = bosca.kubernetes.model.ConfigKind.SECRET,
                namespace = "..", name = "tls",
            )
        }
    }

    @Test
    fun `configEntries encodes a slash inside a name segment`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(200).body("""{"items":[]}""").build())
        client.configEntries(
            ctx, clusterId,
            kind = bosca.kubernetes.model.ConfigKind.SECRET,
            namespace = "ns", name = "/etc/passwd",
        )
        assertEquals(
            "/clusters/$clusterId/config/SECRET/ns/%2Fetc%2Fpasswd/entries",
            nextRequestTarget(),
        )
    }

    @Test
    fun `helmRepoRemove rejects a percent-encoded dot-dot name`() = runTest {
        // OkHttp normalises '%2E%2E' (any case) into the same '..' pop
        // behaviour, so the rejection has to cover the encoded form too.
        assertFailsWith<IllegalArgumentException> {
            client.helmRepoRemove(ctx, name = "%2E%2E")
        }
    }

    @Test
    fun `streamPodLogs encodes namespace and pod segments`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(200).body("").build())
        val flow = client.streamPodLogs(ctx, clusterId, namespace = "evil/ns", pod = "p?x=y")
        flow.collect { /* drain */ }
        val t = mockServer.takeRequest().target ?: fail("no request observed")
        assertTrue(t.startsWith("/clusters/$clusterId/pods/"))
        assertTrue(!t.contains("/evil/ns/"), "raw '/' in namespace must be encoded: $t")
        assertTrue(!t.contains("?x=y"), "raw '?' must not survive into the URL: $t")
    }

    companion object {
        // A minimal K8sHelmRelease body that matches the wire shape — used as the
        // happy-path response for every helm mutation.
        private const val K8S_HELM_RELEASE_JSON = """
            {"id":"a","name":"podinfo","namespace":"prod","chart":"podinfo","chartVersion":"6.11.2",
             "appVersion":"6.11.2","status":"DEPLOYED","revision":1,"updated":"2026-05-15T00:00:00Z",
             "installed":"2026-05-15T00:00:00Z","repo":"podinfo","repoUrl":"https://stefanprodan.github.io/podinfo",
             "description":""}
        """

        // Minimal Workload wire shape for scaleWorkload's response.
        private const val WORKLOAD_JSON = """
            {"id":"w","kind":"STATEFUL_SET","name":"db","namespace":"ns","ready":3,"desired":3,
             "status":"OK","image":"db:1","age":"-","cpu":0.0,"memory":0.0,"restarts":0,
             "strategy":"RollingUpdate"}
        """
    }
}

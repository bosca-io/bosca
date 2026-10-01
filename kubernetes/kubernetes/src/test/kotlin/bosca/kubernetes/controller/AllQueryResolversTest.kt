package bosca.kubernetes.controller

import bosca.kubernetes.model.CertificatesResponse
import bosca.kubernetes.model.Cluster
import bosca.kubernetes.model.ClusterEnvironment
import bosca.kubernetes.model.CnpgClusterDetailResponse
import bosca.kubernetes.model.CnpgClustersResponse
import bosca.kubernetes.model.ConfigKind
import bosca.kubernetes.model.ConfigResource
import bosca.kubernetes.model.ConfigResourceEntriesResponse
import bosca.kubernetes.model.ConfigResourceEntry
import bosca.kubernetes.model.ConfigResourcesResponse
import bosca.kubernetes.model.CustomResourcesResponse
import bosca.kubernetes.model.EventLevel
import bosca.kubernetes.model.EventsResponse
import bosca.kubernetes.model.GatewayClassesResponse
import bosca.kubernetes.model.GatewaysResponse
import bosca.kubernetes.model.HelmChartVersionsResponse
import bosca.kubernetes.model.HelmChartsResponse
import bosca.kubernetes.model.HelmReleaseHistoryResponse
import bosca.kubernetes.model.HelmReleasesResponse
import bosca.kubernetes.model.HelmReposResponse
import bosca.kubernetes.model.HelmStatus
import bosca.kubernetes.model.HttpRoutesResponse
import bosca.kubernetes.model.IngressesResponse
import bosca.kubernetes.model.IssuersResponse
import bosca.kubernetes.model.K8sCertificate
import bosca.kubernetes.model.K8sCnpgCluster
import bosca.kubernetes.model.K8sCnpgClusterDetail
import bosca.kubernetes.model.K8sCustomResource
import bosca.kubernetes.model.K8sEvent
import bosca.kubernetes.model.K8sGateway
import bosca.kubernetes.model.K8sGatewayClass
import bosca.kubernetes.model.K8sHelmChart
import bosca.kubernetes.model.K8sHelmChartValues
import bosca.kubernetes.model.K8sHelmChartVersion
import bosca.kubernetes.model.K8sHelmRelease
import bosca.kubernetes.model.K8sHelmRepo
import bosca.kubernetes.model.K8sHelmRevision
import bosca.kubernetes.model.K8sHttpRoute
import bosca.kubernetes.model.K8sIngress
import bosca.kubernetes.model.K8sIssuer
import bosca.kubernetes.model.K8sNetworkPolicy
import bosca.kubernetes.model.K8sNode
import bosca.kubernetes.model.K8sOperator
import bosca.kubernetes.model.K8sPvc
import bosca.kubernetes.model.K8sRole
import bosca.kubernetes.model.K8sRoleBinding
import bosca.kubernetes.model.K8sService
import bosca.kubernetes.model.K8sServiceAccount
import bosca.kubernetes.model.K8sStorageClass
import bosca.kubernetes.model.NamespacesResponse
import bosca.kubernetes.model.NetworkPoliciesResponse
import bosca.kubernetes.model.OperatorsResponse
import bosca.kubernetes.model.Pod
import bosca.kubernetes.model.PodsResponse
import bosca.kubernetes.model.PvcsResponse
import bosca.kubernetes.model.RoleBindingsResponse
import bosca.kubernetes.model.RolesResponse
import bosca.kubernetes.model.ServiceAccountsResponse
import bosca.kubernetes.model.ServicesResponse
import bosca.kubernetes.model.StorageClassesResponse
import bosca.kubernetes.model.WorkloadKind
import bosca.kubernetes.model.WorkloadStatus
import bosca.kubernetes.model.WorkloadsResponse
import bosca.kubernetes.model.YamlResponse
import bosca.kubernetes.service.ClusterService
import bosca.kubernetes.service.KubernetesControllerClient
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.uuid.ExperimentalUuidApi

/**
 * Exhaustive resolver-layer tests for [KubernetesQueriesController].
 *
 * Every @Field method is exercised on three axes:
 *   * Happy path — admin OK, cluster registered, controller responds → resolver returns items
 *   * Authorization — admin check throws, no downstream call
 *   * Existence — cluster not registered → fail-fast (where applicable; helm catalog reads skip this)
 *
 * The shape mirrors the resolver itself: `groups.verifyHasAdminGroup`
 * then `clusters.getById` then `controller.<method>`. Mocking those
 * three collaborators is sufficient to pin the entire pipeline.
 */
@OptIn(ExperimentalUuidApi::class)
class AllQueryResolversTest {

    private val clusters = mockk<ClusterService>()
    private val controller = mockk<KubernetesControllerClient>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val clusterId = UUID.random()

    private lateinit var ctrl: KubernetesQueriesController

    @BeforeTest
    fun setup() {
        ctrl = KubernetesQueriesController(clusters, controller, groups)
        coEvery { clusters.getById(clusterId) } returns fakeCluster()
    }

    @AfterTest
    fun teardown() {
        io.mockk.unmockkAll()
    }

    private fun fakeCluster() = Cluster(
        id = clusterId, name = "test", provider = "kind", region = "local",
        environment = ClusterEnvironment.DEVELOPMENT,
    )

    // ---------- clusters / cluster ----------

    @Test
    fun `clusters delegates to ClusterService list after admin check`() = runTest {
        val want = listOf(fakeCluster(), fakeCluster())
        coEvery { clusters.list() } returns want

        val got = ctrl.clusters(auth)

        assertSame(want, got)
        verify { groups.verifyHasAdminGroup(auth) }
    }

    @Test
    fun `clusters admin failure shortcircuits before repo call`() = runTest {
        every { groups.verifyHasAdminGroup(auth) } throws SecurityException("not admin")
        assertFailsWith<SecurityException> { ctrl.clusters(auth) }
        coVerify(exactly = 0) { clusters.list() }
    }

    @Test
    fun `cluster returns null when the id is not registered`() = runTest {
        val unknown = UUID.random()
        coEvery { clusters.getById(unknown) } returns null
        assertEquals(null, ctrl.cluster(auth, unknown))
    }

    @Test
    fun `cluster returns the registered cluster when known`() = runTest {
        val want = fakeCluster()
        coEvery { clusters.getById(clusterId) } returns want
        assertSame(want, ctrl.cluster(auth, clusterId))
    }

    // ---------- workloads ----------

    @Test
    fun `workloads delegates with all filter args propagated`() = runTest {
        val items = listOf(workload("nginx"))
        coEvery { controller.workloads(auth, clusterId, "default", WorkloadKind.DEPLOYMENT) } returns
            WorkloadsResponse(items)
        val got = ctrl.workloads(auth, clusterId, "default", WorkloadKind.DEPLOYMENT)
        assertEquals(items, got)
    }

    @Test
    fun `workloads filters can be null for all-namespaces all-kinds query`() = runTest {
        coEvery { controller.workloads(auth, clusterId, null, null) } returns WorkloadsResponse(emptyList())
        assertEquals(emptyList(), ctrl.workloads(auth, clusterId))
    }

    @Test
    fun `workloads fail-fast when cluster not registered`() = runTest {
        coEvery { clusters.getById(clusterId) } returns null
        assertFailsWith<IllegalStateException> { ctrl.workloads(auth, clusterId) }
    }

    // ---------- pods ----------

    @Test
    fun `pods returns the controller's PodsResponse with paging fields intact`() = runTest {
        val resp = PodsResponse(total = 42, items = listOf(pod("p-1")))
        coEvery { controller.pods(auth, clusterId, "default", "wl-1", "ngin", 10, 0) } returns resp
        val got = ctrl.pods(auth, clusterId, "default", "wl-1", "ngin", 10, 0)
        assertSame(resp, got)
    }

    @Test
    fun `pods all-null args is a valid all-pods-everywhere query`() = runTest {
        coEvery { controller.pods(auth, clusterId, null, null, null, null, null) } returns
            PodsResponse(total = 0, items = emptyList())
        assertEquals(0, ctrl.pods(auth, clusterId).total)
    }

    // ---------- nodes ----------

    @Test
    fun `nodes returns the controller items`() = runTest {
        val nodes = listOf(K8sNode("n", "worker", "i", "z", "Ready", 0, 0, 0, "1h", "v1"))
        coEvery { controller.nodes(auth, clusterId) } returns bosca.kubernetes.model.NodesResponse(nodes)
        assertEquals(nodes, ctrl.nodes(auth, clusterId))
    }

    // ---------- events ----------

    @Test
    fun `events delegates with namespace level and limit`() = runTest {
        val want = listOf(event())
        coEvery { controller.events(auth, clusterId, "default", EventLevel.WARN, 50) } returns EventsResponse(want)
        assertEquals(want, ctrl.events(auth, clusterId, "default", EventLevel.WARN, 50))
    }

    @Test
    fun `events with no filters returns the unfiltered controller list`() = runTest {
        coEvery { controller.events(auth, clusterId, null, null, null) } returns EventsResponse(emptyList())
        assertEquals(emptyList(), ctrl.events(auth, clusterId))
    }

    // ---------- configResources / configEntries ----------

    @Test
    fun `configResources propagates namespace and kind filters`() = runTest {
        val items = listOf(configResource("settings"))
        coEvery { controller.configResources(auth, clusterId, "default", ConfigKind.CONFIG_MAP) } returns
            ConfigResourcesResponse(items)
        assertEquals(items, ctrl.configResources(auth, clusterId, "default", ConfigKind.CONFIG_MAP))
    }

    @Test
    fun `configEntries reads keys for a single ConfigMap`() = runTest {
        val entries = listOf(ConfigResourceEntry("k", "v"))
        coEvery { controller.configEntries(auth, clusterId, ConfigKind.CONFIG_MAP, "default", "settings") } returns
            ConfigResourceEntriesResponse(entries)
        assertEquals(entries, ctrl.configEntries(auth, clusterId, "default", ConfigKind.CONFIG_MAP, "settings"))
    }

    // ---------- services / ingresses / networkPolicies ----------

    @Test
    fun `services delegates with namespace filter`() = runTest {
        val want = listOf(K8sService("s","x","default","ClusterIP","1.2.3.4","<none>", emptyList(),"app=x","1h",1))
        coEvery { controller.services(auth, clusterId, "default") } returns ServicesResponse(want)
        assertEquals(want, ctrl.services(auth, clusterId, "default"))
    }

    @Test
    fun `services null namespace returns all-namespaces`() = runTest {
        coEvery { controller.services(auth, clusterId, null) } returns ServicesResponse(emptyList())
        assertEquals(emptyList(), ctrl.services(auth, clusterId))
    }

    @Test
    fun `ingresses returns the response items`() = runTest {
        val ing = listOf(K8sIngress("i","x","default","nginx", emptyList(), emptyList(), emptyList(), false, "5m"))
        coEvery { controller.ingresses(auth, clusterId, "default") } returns IngressesResponse(ing)
        assertEquals(ing, ctrl.ingresses(auth, clusterId, "default"))
    }

    @Test
    fun `networkPolicies returns the response items`() = runTest {
        val np = listOf(K8sNetworkPolicy("p","x","default","<all>","deny","deny","1d"))
        coEvery { controller.networkPolicies(auth, clusterId, null) } returns NetworkPoliciesResponse(np)
        assertEquals(np, ctrl.networkPolicies(auth, clusterId))
    }

    // ---------- storage ----------

    @Test
    fun `storageClasses returns the response items`() = runTest {
        val sc = listOf(K8sStorageClass("standard","kubernetes.io/aws-ebs","Delete","Immediate", true,"1d","type=gp3"))
        coEvery { controller.storageClasses(auth, clusterId) } returns StorageClassesResponse(sc)
        assertEquals(sc, ctrl.storageClasses(auth, clusterId))
    }

    @Test
    fun `pvcs returns the response items`() = runTest {
        val pvcs = listOf(K8sPvc("p","data","default","Bound","pv","10Gi","RWO","standard","1d"))
        coEvery { controller.pvcs(auth, clusterId, "default") } returns PvcsResponse(pvcs)
        assertEquals(pvcs, ctrl.pvcs(auth, clusterId, "default"))
    }

    // ---------- RBAC ----------

    @Test
    fun `roles propagates the kind filter`() = runTest {
        val want = listOf(K8sRole("r","Role","reader", false,1,3,"1h",namespace = "default"))
        coEvery { controller.roles(auth, clusterId, "Role") } returns RolesResponse(want)
        assertEquals(want, ctrl.roles(auth, clusterId, "Role"))
    }

    @Test
    fun `roleBindings propagates the kind filter`() = runTest {
        val rb = listOf(K8sRoleBinding("rb","RoleBinding","b","reader", emptyList(),namespace="default", age="1h"))
        coEvery { controller.roleBindings(auth, clusterId, "RoleBinding") } returns RoleBindingsResponse(rb)
        assertEquals(rb, ctrl.roleBindings(auth, clusterId, "RoleBinding"))
    }

    @Test
    fun `serviceAccounts propagates namespace`() = runTest {
        val sa = listOf(K8sServiceAccount("sa","default","default",0,0,0,"1y"))
        coEvery { controller.serviceAccounts(auth, clusterId, "default") } returns ServiceAccountsResponse(sa)
        assertEquals(sa, ctrl.serviceAccounts(auth, clusterId, "default"))
    }

    // ---------- yaml ----------

    @Test
    fun `yaml returns the YamlResponse yaml field unwrapped`() = runTest {
        coEvery { controller.yaml(auth, clusterId, "Pod", "p", "default", null) } returns
            YamlResponse(yaml = "apiVersion: v1\nkind: Pod\n")
        assertEquals("apiVersion: v1\nkind: Pod\n", ctrl.yaml(auth, clusterId, "Pod", "p", "default"))
    }

    @Test
    fun `yaml passes the group arg for CRD instances`() = runTest {
        coEvery {
            controller.yaml(auth, clusterId, "Cluster", "primary", "data", "postgresql.cnpg.io")
        } returns YamlResponse(yaml = "kind: Cluster\n")
        val got = ctrl.yaml(auth, clusterId, "Cluster", "primary", "data", "postgresql.cnpg.io")
        assertEquals("kind: Cluster\n", got)
    }

    // ---------- operators / customResources ----------

    @Test
    fun `operators returns the response items`() = runTest {
        val ops = listOf(K8sOperator("o","x","1.0","g.io","ns", WorkloadStatus.OK,1, emptyList(),""))
        coEvery { controller.operators(auth, clusterId) } returns OperatorsResponse(ops)
        assertEquals(ops, ctrl.operators(auth, clusterId))
    }

    @Test
    fun `customResources propagates group and namespace filters`() = runTest {
        val crs = listOf(K8sCustomResource("c","Cluster","g","v","ns","name","1h", WorkloadStatus.OK,""))
        coEvery { controller.customResources(auth, clusterId, "g", "ns") } returns CustomResourcesResponse(crs)
        assertEquals(crs, ctrl.customResources(auth, clusterId, "g", "ns"))
    }

    // ---------- Gateway API ----------

    @Test
    fun `gatewayClasses returns the response items`() = runTest {
        val gc = listOf(K8sGatewayClass("istio","istio.io","accepted".let { true },"1d"))
        coEvery { controller.gatewayClasses(auth, clusterId) } returns GatewayClassesResponse(gc)
        assertEquals(gc, ctrl.gatewayClasses(auth, clusterId))
    }

    @Test
    fun `gateways returns the response items`() = runTest {
        val gw = listOf(K8sGateway("g","x","ns","istio", emptyList(),1,0, WorkloadStatus.OK,"1h"))
        coEvery { controller.gateways(auth, clusterId, "ns") } returns GatewaysResponse(gw)
        assertEquals(gw, ctrl.gateways(auth, clusterId, "ns"))
    }

    @Test
    fun `httpRoutes returns the response items`() = runTest {
        val r = listOf(K8sHttpRoute("r","x","ns", emptyList(), emptyList(), emptyList(), emptyList(),1,"1h", WorkloadStatus.OK))
        coEvery { controller.httpRoutes(auth, clusterId, null) } returns HttpRoutesResponse(r)
        assertEquals(r, ctrl.httpRoutes(auth, clusterId))
    }

    // ---------- cert-manager ----------

    @Test
    fun `certManagerCertificates returns the response items`() = runTest {
        val certs = listOf(K8sCertificate("c","tls","default", emptyList(),"i","Ready","",30,15,"s"))
        coEvery { controller.certManagerCertificates(auth, clusterId, "default") } returns CertificatesResponse(certs)
        assertEquals(certs, ctrl.certManagerCertificates(auth, clusterId, "default"))
    }

    @Test
    fun `certManagerIssuers returns the response items`() = runTest {
        val issuers = listOf(K8sIssuer("i","Issuer","x","acme","https://acme","Ready","1d",1))
        coEvery { controller.certManagerIssuers(auth, clusterId, null) } returns IssuersResponse(issuers)
        assertEquals(issuers, ctrl.certManagerIssuers(auth, clusterId))
    }

    // ---------- CNPG ----------

    @Test
    fun `cnpgClusters returns the response items`() = runTest {
        val cnpg = listOf(K8sCnpgCluster("primary","data",3,"primary-1","16.3","img","Healthy", WorkloadStatus.OK,"1h","","-"))
        coEvery { controller.cnpgClusters(auth, clusterId, "data") } returns CnpgClustersResponse(cnpg)
        assertEquals(cnpg, ctrl.cnpgClusters(auth, clusterId, "data"))
    }

    @Test
    fun `cnpgCluster returns the detail payload`() = runTest {
        val summary = K8sCnpgCluster("pg","data",2,"pg-1","16.4","img","Cluster in healthy state", WorkloadStatus.OK,"1h","0 2 * * *","2h ago")
        val detail = K8sCnpgClusterDetail(summary, "100Gi", "gp3", "s3://b/pg", "30d", emptyList(), emptyList(), emptyList())
        coEvery { controller.cnpgClusterDetail(auth, clusterId, "data", "pg") } returns CnpgClusterDetailResponse(detail)
        assertEquals(detail, ctrl.cnpgCluster(auth, clusterId, "data", "pg"))
    }

    @Test
    fun `cnpgCluster returns null when the cluster is absent`() = runTest {
        coEvery { controller.cnpgClusterDetail(auth, clusterId, "data", "missing") } returns CnpgClusterDetailResponse(detail = null)
        assertNull(ctrl.cnpgCluster(auth, clusterId, "data", "missing"))
    }

    // ---------- Helm catalog (cluster-independent) ----------

    @Test
    fun `helmRepos skips the cluster lookup as it is catalog-wide`() = runTest {
        val repos = listOf(K8sHelmRepo("podinfo","https://stefanprodan.github.io/podinfo","http",1,"just now"))
        coEvery { controller.helmRepos(auth) } returns HelmReposResponse(repos)
        assertEquals(repos, ctrl.helmRepos(auth))
        coVerify(exactly = 0) { clusters.getById(any()) }
    }

    @Test
    fun `helmCharts propagates repo and search filters`() = runTest {
        val charts = listOf(K8sHelmChart("podinfo/podinfo","podinfo","podinfo","6.11.2","6.11.2","fast",null))
        coEvery { controller.helmCharts(auth, "podinfo", "info") } returns HelmChartsResponse(charts)
        assertEquals(charts, ctrl.helmCharts(auth, "podinfo", "info"))
    }

    @Test
    fun `helmCharts null filters returns the unfiltered catalog`() = runTest {
        coEvery { controller.helmCharts(auth, null, null) } returns HelmChartsResponse(emptyList())
        assertEquals(emptyList(), ctrl.helmCharts(auth))
    }

    @Test
    fun `helmChartVersions returns the version list`() = runTest {
        val versions = listOf(K8sHelmChartVersion("6.11.2","6.11.2","2024-01-01", true))
        coEvery { controller.helmChartVersions(auth, "podinfo", "podinfo") } returns HelmChartVersionsResponse(versions)
        assertEquals(versions, ctrl.helmChartVersions(auth, "podinfo", "podinfo"))
    }

    @Test
    fun `helmChartValues returns the values payload`() = runTest {
        val values = K8sHelmChartValues(defaultValues = "replicaCount: 1\n")
        coEvery { controller.helmChartValues(auth, "podinfo", "podinfo", "6.11.2") } returns values
        assertSame(values, ctrl.helmChartValues(auth, "podinfo", "podinfo", "6.11.2"))
    }

    // ---------- Helm releases (cluster-scoped) ----------

    @Test
    fun `helmReleases returns the controller items`() = runTest {
        val rels = listOf(release("podinfo-smoke"))
        coEvery { controller.helmReleases(auth, clusterId, null) } returns HelmReleasesResponse(rels)
        assertEquals(rels, ctrl.helmReleases(auth, clusterId))
    }

    @Test
    fun `helmReleaseHistory returns the revision list`() = runTest {
        val revs = listOf(K8sHelmRevision(1,"1d ago", HelmStatus.DEPLOYED,"podinfo","6.11.2",""))
        coEvery { controller.helmReleaseHistory(auth, clusterId, "default", "podinfo-smoke") } returns
            HelmReleaseHistoryResponse(revs)
        assertEquals(revs, ctrl.helmReleaseHistory(auth, clusterId, "default", "podinfo-smoke"))
    }

    // ---------- Authorization shortcut applies to every resolver ----------

    @Test
    fun `admin failure shortcuts all major resolvers without downstream calls`() = runTest {
        every { groups.verifyHasAdminGroup(auth) } throws SecurityException("not admin")
        for (action in listOf<suspend () -> Any?>(
            { ctrl.namespaces(auth, clusterId) },
            { ctrl.workloads(auth, clusterId) },
            { ctrl.pods(auth, clusterId) },
            { ctrl.nodes(auth, clusterId) },
            { ctrl.events(auth, clusterId) },
            { ctrl.services(auth, clusterId) },
            { ctrl.ingresses(auth, clusterId) },
            { ctrl.networkPolicies(auth, clusterId) },
            { ctrl.storageClasses(auth, clusterId) },
            { ctrl.pvcs(auth, clusterId) },
            { ctrl.roles(auth, clusterId) },
            { ctrl.roleBindings(auth, clusterId) },
            { ctrl.serviceAccounts(auth, clusterId) },
            { ctrl.operators(auth, clusterId) },
            { ctrl.customResources(auth, clusterId) },
            { ctrl.gatewayClasses(auth, clusterId) },
            { ctrl.gateways(auth, clusterId) },
            { ctrl.httpRoutes(auth, clusterId) },
            { ctrl.certManagerCertificates(auth, clusterId) },
            { ctrl.certManagerIssuers(auth, clusterId) },
            { ctrl.cnpgClusters(auth, clusterId) },
            { ctrl.cnpgCluster(auth, clusterId, "data", "pg") },
            { ctrl.helmRepos(auth) },
            { ctrl.helmCharts(auth) },
            { ctrl.helmReleases(auth, clusterId) },
            { ctrl.helmReleaseHistory(auth, clusterId, "default", "x") },
            { ctrl.helmReleaseValues(auth, clusterId, "default", "x") },
            { ctrl.helmReleaseManifest(auth, clusterId, "default", "x") },
            { ctrl.yaml(auth, clusterId, "Pod", "x") },
            { ctrl.configResources(auth, clusterId) },
            { ctrl.configEntries(auth, clusterId, "ns", ConfigKind.CONFIG_MAP, "x") },
            { ctrl.clusters(auth) },
            { ctrl.cluster(auth, clusterId) },
        )) {
            assertFailsWith<SecurityException> { action() }
        }
        // The controller client must not have been touched.
        coVerify(exactly = 0) { controller.namespaces(any(), any()) }
        coVerify(exactly = 0) { controller.helmRepos(any()) }
    }

    @Test
    fun `cluster-not-found fails fast for every cluster-scoped resolver`() = runTest {
        coEvery { clusters.getById(clusterId) } returns null
        for (action in listOf<suspend () -> Any?>(
            { ctrl.namespaces(auth, clusterId) },
            { ctrl.workloads(auth, clusterId) },
            { ctrl.pods(auth, clusterId) },
            { ctrl.nodes(auth, clusterId) },
            { ctrl.events(auth, clusterId) },
            { ctrl.services(auth, clusterId) },
            { ctrl.ingresses(auth, clusterId) },
            { ctrl.networkPolicies(auth, clusterId) },
            { ctrl.storageClasses(auth, clusterId) },
            { ctrl.pvcs(auth, clusterId) },
            { ctrl.roles(auth, clusterId) },
            { ctrl.roleBindings(auth, clusterId) },
            { ctrl.serviceAccounts(auth, clusterId) },
            { ctrl.operators(auth, clusterId) },
            { ctrl.customResources(auth, clusterId) },
            { ctrl.gatewayClasses(auth, clusterId) },
            { ctrl.gateways(auth, clusterId) },
            { ctrl.httpRoutes(auth, clusterId) },
            { ctrl.certManagerCertificates(auth, clusterId) },
            { ctrl.certManagerIssuers(auth, clusterId) },
            { ctrl.cnpgClusters(auth, clusterId) },
            { ctrl.cnpgCluster(auth, clusterId, "data", "pg") },
            { ctrl.helmReleases(auth, clusterId) },
            { ctrl.helmReleaseHistory(auth, clusterId, "default", "x") },
            { ctrl.helmReleaseValues(auth, clusterId, "default", "x") },
            { ctrl.helmReleaseManifest(auth, clusterId, "default", "x") },
            { ctrl.yaml(auth, clusterId, "Pod", "x") },
            { ctrl.configResources(auth, clusterId) },
            { ctrl.configEntries(auth, clusterId, "ns", ConfigKind.CONFIG_MAP, "x") },
        )) {
            val ex = assertFailsWith<IllegalStateException> { action() }
            assertNotNull(ex.message, "expected an error message naming the cluster")
        }
    }

    // ---------- Fixture builders ----------

    private fun workload(name: String) = bosca.kubernetes.model.Workload(
        id = "wl-$name", kind = WorkloadKind.DEPLOYMENT, name = name, namespace = "default",
        ready = 1, desired = 1, status = WorkloadStatus.OK, image = "img", age = "1h",
        cpu = 0.0, memory = 0.0, restarts = 0, strategy = "RollingUpdate",
    )

    private fun pod(name: String) = Pod(
        id = "p-$name", name = name, namespace = "default", node = "n",
        status = "Running", ready = "1/1", restarts = 0, age = "5m",
        cpu = 0, memory = 0,
    )

    private fun event() = K8sEvent(
        id = "e-1", level = EventLevel.WARN, `when` = "1m ago",
        timestamp = bosca.serialization.OffsetDateTime.parse("2026-05-15T15:44:42Z"),
        namespace = "default", involvedObject = "Pod/x", message = "m", reason = "Unhealthy",
    )

    private fun configResource(name: String) = ConfigResource(
        id = "cm-1", kind = ConfigKind.CONFIG_MAP, name = name, namespace = "default",
        keys = listOf("k"), size = "100 B", age = "1h",
    )

    private fun release(name: String) = K8sHelmRelease(
        id = "rel-$name", name = name, namespace = "default",
        chart = "podinfo", chartVersion = "6.11.2", appVersion = "6.11.2",
        revision = 1, status = HelmStatus.DEPLOYED,
        updated = "just now", installed = "just now",
        repo = "podinfo", repoUrl = "https://stefanprodan.github.io/podinfo",
        description = "Install complete",
    )
}

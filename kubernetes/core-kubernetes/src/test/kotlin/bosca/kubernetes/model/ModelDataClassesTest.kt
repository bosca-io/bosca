package bosca.kubernetes.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Coverage for every data class in the kubernetes wire model — primary
 * constructors, defaults, `copy`, `equals`, `hashCode`, and `toString`.
 *
 * These are pure record shapes serialized to/from JSON over the
 * controller HTTP transport. They're regression-prone in one specific
 * way: kotlinx.serialization defaults silently change behaviour when a
 * field gains a `= default`. The constructor-call asserts here lock
 * the *default values* in place — adding or changing a default
 * requires changing the test, which surfaces the schema impact at
 * review time.
 *
 * Coverage scope: every data class in `bosca.kubernetes.model.*`.
 * Each test exercises a representative subset of the property surface
 * plus `copy` and `==` to drag the synthetic data-class methods into
 * coverage.
 */
@OptIn(ExperimentalUuidApi::class)
class ModelDataClassesTest {

    // ===== Cluster / inputs =====

    @Test
    fun `Cluster default values cover the optional fields`() {
        val id = UUID.random()
        val c = Cluster(
            id = id, name = "test", provider = "kind", region = "local",
            environment = ClusterEnvironment.DEVELOPMENT,
        )
        assertEquals("", c.serverVersion)
        assertEquals(ClusterHealth.OK, c.health)
        assertEquals(0, c.nodes)
        assertEquals(0, c.pods)
        assertNotNull(c.registeredAt)
        assertNull(c.lastSeenAt)
        assertNull(c.deletedAt)
        assertNotNull(c.modifiedAt)

        // copy() — synthetic data-class method
        val renamed = c.copy(name = "renamed")
        assertEquals("renamed", renamed.name)
        assertEquals(c.provider, renamed.provider)
        // equality / hashing
        assertEquals(c, c.copy())
        assertNotEquals(c, renamed)
        assertEquals(c.hashCode(), c.copy().hashCode())
        assertTrue(c.toString().contains("test"))
    }

    @Test
    fun `ClusterEnvironment enum exposes every documented value`() {
        assertEquals(setOf("PRODUCTION", "STAGING", "DEVELOPMENT"),
            ClusterEnvironment.entries.map { it.name }.toSet())
    }

    @Test
    fun `ClusterHealth enum exposes every documented value`() {
        assertEquals(setOf("OK", "WARN", "ERROR"),
            ClusterHealth.entries.map { it.name }.toSet())
    }

    @Test
    fun `RegisterClusterInput requires every field`() {
        val input = RegisterClusterInput(
            name = "x", provider = "EKS", region = "us-east-1",
            environment = ClusterEnvironment.PRODUCTION, kubeconfig = "apiVersion: v1\n",
        )
        assertEquals("x", input.name)
        assertEquals("apiVersion: v1\n", input.kubeconfig)
        assertEquals(input, input.copy())
    }

    @Test
    fun `UpdateClusterInput allows partial updates via null fields`() {
        val empty = UpdateClusterInput()
        assertNull(empty.name)
        assertNull(empty.environment)

        val withName = UpdateClusterInput(name = "renamed")
        assertEquals("renamed", withName.name)
        assertNull(withName.environment)
    }

    // ===== Workloads / Pods / Nodes / Events =====

    @Test
    fun `Workload primary constructor and copy round-trip`() {
        val labels = buildJsonObject { put("app", JsonPrimitive("x")) }
        val w = Workload(
            id = "w", kind = WorkloadKind.DEPLOYMENT, name = "x", namespace = "default",
            ready = 2, desired = 2, status = WorkloadStatus.OK, image = "img", age = "1h",
            cpu = 0.5, memory = 0.25, restarts = 0, strategy = "RollingUpdate",
            labels = labels,
        )
        assertEquals(WorkloadKind.DEPLOYMENT, w.kind)
        assertEquals(labels, w.labels)
        val scaled = w.copy(ready = 3, desired = 3)
        assertEquals(3, scaled.ready)
        assertNotEquals(w, scaled)
        assertEquals(w, w.copy())
    }

    @Test
    fun `WorkloadKind enum has the six in-scope kinds`() {
        assertEquals(
            setOf("DEPLOYMENT", "STATEFUL_SET", "DAEMON_SET", "REPLICA_SET", "JOB", "CRON_JOB"),
            WorkloadKind.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `WorkloadStatus enum covers ok pending warn error`() {
        assertEquals(setOf("OK", "PENDING", "WARN", "ERROR"), WorkloadStatus.entries.map { it.name }.toSet())
    }

    @Test
    fun `WorkloadsResponse wraps a list`() {
        val w = Workload(
            id = "x", kind = WorkloadKind.JOB, name = "j", namespace = "default",
            ready = 0, desired = 1, status = WorkloadStatus.WARN, image = "i", age = "1m",
            cpu = 0.0, memory = 0.0, restarts = 0, strategy = "",
        )
        val resp = WorkloadsResponse(listOf(w))
        assertEquals(1, resp.items.size)
        assertEquals(resp, resp.copy())
    }

    @Test
    fun `Pod default values for podIP hostIP and workloadId`() {
        val p = Pod(
            id = "p", name = "p-1", namespace = "default", node = "n",
            status = "Running", ready = "1/1", restarts = 0, age = "5m",
            cpu = 0, memory = 0,
        )
        assertNull(p.workloadId)
        assertNull(p.podIP)
        assertNull(p.hostIP)
        assertEquals(p, p.copy())
        // copy with non-null
        assertEquals("10.0.0.1", p.copy(podIP = "10.0.0.1").podIP)
    }

    @Test
    fun `PodsResponse exposes total and items`() {
        val pods = listOf(
            Pod(id="p1", name="x", namespace="ns", node="n", status="Running", ready="1/1",
                restarts=0, age="1m", cpu=0, memory=0),
        )
        val resp = PodsResponse(total = 100, items = pods)
        assertEquals(100, resp.total)
        assertEquals(pods, resp.items)
    }

    @Test
    fun `K8sNode defaults empty taints and null labels`() {
        val n = K8sNode(
            name="n", role="worker", instance="i", zone="z",
            status="Ready", cpu=0, memory=0, pods=0,
            age="1h", version="v1",
        )
        assertEquals(emptyList(), n.taints)
        assertNull(n.labels)
        val tainted = n.copy(taints = listOf("x:NoSchedule"))
        assertEquals(listOf("x:NoSchedule"), tainted.taints)
    }

    @Test
    fun `NodesResponse wraps a list`() {
        val nodes = listOf(K8sNode("n","worker","i","z","Ready",0,0,0,"1h","v1"))
        assertEquals(nodes, NodesResponse(nodes).items)
    }

    @Test
    fun `K8sEvent exposes timestamp and reserved-keyword when field`() {
        val ts = OffsetDateTime.parse("2026-05-15T15:44:42Z")
        val e = K8sEvent(
            id="e", level=EventLevel.INFO, `when`="1m ago", timestamp=ts,
            namespace="default", involvedObject="Pod/x", message="m", reason="r",
        )
        assertEquals(EventLevel.INFO, e.level)
        assertEquals(ts, e.timestamp)
        // The reserved keyword field is accessible via backticks
        assertEquals("1m ago", e.`when`)
        assertEquals(e, e.copy())
    }

    @Test
    fun `EventLevel enum covers info warn error`() {
        assertEquals(setOf("INFO", "WARN", "ERROR"), EventLevel.entries.map { it.name }.toSet())
    }

    @Test
    fun `EventsResponse wraps a list`() {
        val ts = OffsetDateTime.parse("2026-05-15T15:44:42Z")
        val e = K8sEvent(id="e",level=EventLevel.INFO,`when`="x",timestamp=ts,
            namespace="x",involvedObject="x",message="x",reason="x")
        assertEquals(listOf(e), EventsResponse(listOf(e)).items)
    }

    // ===== Networking =====

    @Test
    fun `K8sService roundtrips its full surface`() {
        val s = K8sService("s","api","ns","ClusterIP","10.0.0.1","<none>",
            listOf("80/TCP"),"app=api","1h",3)
        assertEquals(3, s.endpoints)
        assertEquals(s, s.copy())
    }

    @Test
    fun `K8sIngress and IngressesResponse roundtrip`() {
        val i = K8sIngress("i","x","ns","nginx", listOf("h"), listOf("/"), listOf("b:80"), true, "1h")
        assertTrue(i.tls)
        assertEquals(listOf(i), IngressesResponse(listOf(i)).items)
    }

    @Test
    fun `K8sNetworkPolicy and NetworkPoliciesResponse roundtrip`() {
        val n = K8sNetworkPolicy("n","x","ns","<all>","deny","deny","1h")
        assertEquals(n, NetworkPoliciesResponse(listOf(n)).items.single())
    }

    @Test
    fun `ServicesResponse wraps a list`() {
        val s = K8sService("s","x","ns","ClusterIP","ip","ext", emptyList(),"sel","1h",0)
        assertEquals(listOf(s), ServicesResponse(listOf(s)).items)
    }

    // ===== Storage =====

    @Test
    fun `K8sStorageClass exposes isDefault and parameters`() {
        val sc = K8sStorageClass("fast","p","Delete","Immediate", true,"1h","type=gp3")
        assertTrue(sc.isDefault)
        assertEquals("type=gp3", sc.parameters)
        assertEquals(sc, sc.copy())
    }

    @Test
    fun `K8sPvc usedPercent and workload default to null`() {
        val p = K8sPvc("p","data","ns","Bound","v","10Gi","RWO","fast","1d")
        assertNull(p.usedPercent)
        assertNull(p.workload)
        assertEquals(80, p.copy(usedPercent = 80).usedPercent)
    }

    @Test
    fun `StorageClassesResponse and PvcsResponse wrap lists`() {
        val sc = K8sStorageClass("s","p","Delete","Immediate", false,"1h","")
        assertEquals(listOf(sc), StorageClassesResponse(listOf(sc)).items)
        val pvc = K8sPvc("p","x","ns","Bound","v","1Gi","RWO","s","1h")
        assertEquals(listOf(pvc), PvcsResponse(listOf(pvc)).items)
    }

    // ===== Config =====

    @Test
    fun `ConfigKind enum covers ConfigMap and Secret`() {
        assertEquals(setOf("CONFIG_MAP", "SECRET"), ConfigKind.entries.map { it.name }.toSet())
    }

    @Test
    fun `ConfigResource defaults null secretType and managedBy`() {
        val cm = ConfigResource("c", ConfigKind.CONFIG_MAP,"x","ns", listOf("k"),"1KB","1h")
        assertNull(cm.secretType)
        assertNull(cm.managedBy)
        val sec = ConfigResource("s", ConfigKind.SECRET,"creds","ns", listOf("p"),"60B","1h",
            secretType = "kubernetes.io/dockerconfigjson", managedBy = "Helm")
        assertEquals("kubernetes.io/dockerconfigjson", sec.secretType)
    }

    @Test
    fun `ConfigResourceEntry exposes key and value`() {
        val e = ConfigResourceEntry("k", "v")
        assertEquals("k", e.key)
        assertEquals("v", e.value)
        assertEquals(e, e.copy())
    }

    @Test
    fun `Config response envelopes wrap lists`() {
        val cm = ConfigResource("c", ConfigKind.CONFIG_MAP,"x","ns", emptyList(),"0","1h")
        assertEquals(listOf(cm), ConfigResourcesResponse(listOf(cm)).items)
        val entry = ConfigResourceEntry("k","v")
        assertEquals(listOf(entry), ConfigResourceEntriesResponse(listOf(entry)).items)
    }

    // ===== RBAC =====

    @Test
    fun `K8sRole defaults empty description and null namespace for cluster-scoped`() {
        val r = K8sRole("r","ClusterRole","cluster-admin", true, 1, 1, "1d")
        assertEquals("", r.description)
        assertNull(r.namespace)
    }

    @Test
    fun `K8sRoleSubject namespace null for cluster-scoped subjects`() {
        assertNull(K8sRoleSubject("User","alice").namespace)
        assertEquals("default", K8sRoleSubject("ServiceAccount","sa","default").namespace)
    }

    @Test
    fun `K8sRoleBinding wraps subjects list`() {
        val s = K8sRoleSubject("Group","admins")
        val b = K8sRoleBinding("b","ClusterRoleBinding","x","cluster-admin", listOf(s),namespace=null, age="1d")
        assertEquals(listOf(s), b.subjects)
        assertNull(b.namespace)
    }

    @Test
    fun `K8sServiceAccount iamRole defaults null`() {
        val sa = K8sServiceAccount("sa","x","ns",1,0,0,"1h")
        assertNull(sa.iamRole)
        assertEquals("arn:aws", sa.copy(iamRole = "arn:aws").iamRole)
    }

    @Test
    fun `RBAC response envelopes wrap lists`() {
        val r = K8sRole("r","Role","x", false,0,0,"1h")
        assertEquals(listOf(r), RolesResponse(listOf(r)).items)
        val b = K8sRoleBinding("b","RoleBinding","x","r", emptyList(), age="1h")
        assertEquals(listOf(b), RoleBindingsResponse(listOf(b)).items)
        val sa = K8sServiceAccount("sa","x","ns",0,0,0,"1h")
        assertEquals(listOf(sa), ServiceAccountsResponse(listOf(sa)).items)
    }

    // ===== Helm =====

    @Test
    fun `HelmStatus enum covers every documented status`() {
        assertEquals(
            setOf("DEPLOYED", "PENDING", "FAILED", "SUPERSEDED", "UNINSTALLED"),
            HelmStatus.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `K8sHelmRepo full constructor`() {
        val repo = K8sHelmRepo("podinfo","https://x","http",1,"now")
        assertEquals("podinfo", repo.name)
        assertEquals(repo, repo.copy())
    }

    @Test
    fun `K8sHelmChart icon defaults null`() {
        val c = K8sHelmChart("id","n","r","1.0","1.0","")
        assertNull(c.icon)
        assertEquals("https://x/icon.png", c.copy(icon = "https://x/icon.png").icon)
    }

    @Test
    fun `K8sHelmChartVersion full constructor`() {
        val v = K8sHelmChartVersion("1.0","1.0","2024-01-01", true)
        assertTrue(v.current)
        assertFalse(v.copy(current = false).current)
    }

    @Test
    fun `K8sHelmChartValues schema defaults null`() {
        val v = K8sHelmChartValues(defaultValues = "x: 1\n")
        assertNull(v.schema)
        val schema = buildJsonObject { put("type", JsonPrimitive("object")) }
        assertEquals(schema, v.copy(schema = schema).schema)
    }

    @Test
    fun `K8sHelmRelease and K8sHelmRevision full constructors`() {
        val rel = K8sHelmRelease(
            id="id", name="n", namespace="ns",
            chart="c", chartVersion="1", appVersion="1",
            revision=1, status=HelmStatus.DEPLOYED,
            updated="u", installed="i", repo="r", repoUrl="ru", description="d",
        )
        assertEquals(HelmStatus.DEPLOYED, rel.status)
        assertEquals(rel, rel.copy())
        val rev = K8sHelmRevision(1, "u", HelmStatus.SUPERSEDED, "c", "1", "d")
        assertEquals(HelmStatus.SUPERSEDED, rev.status)
    }

    @Test
    fun `HelmReleaseTextResponse wraps the yaml payload`() {
        val resp = HelmReleaseTextResponse(yaml = "a: 1\n")
        assertEquals("a: 1\n", resp.yaml)
    }

    @Test
    fun `HelmRepoAddRequest default null credentials`() {
        val req = HelmRepoAddRequest(name="podinfo", url="https://x")
        assertNull(req.username)
        assertNull(req.password)
        assertNull(req.credentials())
        val authed = req.copy(username = "u", password = "p")
        assertEquals("u", authed.username)
        assertEquals("u", authed.credentials()?.username)
        assertEquals("p", authed.credentials()?.password)
    }

    @Test
    fun `Helm response envelopes wrap lists`() {
        val r = K8sHelmRepo("podinfo","https://x","http",1,"now")
        assertEquals(listOf(r), HelmReposResponse(listOf(r)).items)
        val c = K8sHelmChart("id","n","r","1.0","1.0","")
        assertEquals(listOf(c), HelmChartsResponse(listOf(c)).items)
        val v = K8sHelmChartVersion("1.0","1.0","x", true)
        assertEquals(listOf(v), HelmChartVersionsResponse(listOf(v)).items)
        val rel = K8sHelmRelease(id="i",name="n",namespace="ns",chart="c",chartVersion="1",
            appVersion="1",revision=1, status=HelmStatus.DEPLOYED,
            updated="u",installed="i",repo="r",repoUrl="ru",description="d")
        assertEquals(listOf(rel), HelmReleasesResponse(listOf(rel)).items)
        val rev = K8sHelmRevision(1,"u", HelmStatus.DEPLOYED,"c","1","d")
        assertEquals(listOf(rev), HelmReleaseHistoryResponse(listOf(rev)).items)
    }

    @Test
    fun `Helm input data classes default expected flags`() {
        val install = HelmInstallInput(
            cluster = UUID.random(), name = "rel", namespace = "ns",
            repo = "podinfo", chart = "podinfo", version = "1.0",
        )
        assertFalse(install.createNamespace)
        assertNull(install.values)
        assertFalse(install.dryRun)

        val upgrade = HelmUpgradeInput(
            cluster = UUID.random(), name = "rel", namespace = "ns",
            repo = "podinfo", chart = "podinfo", version = "1.0",
        )
        assertFalse(upgrade.dryRun)
        assertFalse(upgrade.resetValues)
        assertNull(upgrade.values)
    }

    @Test
    fun `Helm wire request data classes defaults match input defaults`() {
        val install = HelmInstallRequest(name = "x", namespace = "ns", repo = "r", chart = "c", version = "1")
        assertFalse(install.createNamespace)
        assertFalse(install.dryRun)
        assertNull(install.values)
        val upgrade = HelmUpgradeRequest(name = "x", namespace = "ns", version = "1")
        assertFalse(upgrade.dryRun)
        assertFalse(upgrade.resetValues)
        val rollback = HelmRollbackRequest(name = "x", namespace = "ns", toRevision = 3)
        assertEquals(3, rollback.toRevision)
    }

    // ===== Gateway API =====

    @Test
    fun `K8sGatewayClass exposes accepted flag`() {
        val gc = K8sGatewayClass("istio","istio.io",true,"1d")
        assertTrue(gc.accepted)
        assertFalse(gc.copy(accepted = false).accepted)
    }

    @Test
    fun `K8sGateway exposes addresses list and listener and route counts`() {
        val g = K8sGateway("g","p","ns","istio", listOf("10.0.0.1","10.0.0.2"), 2, 5, WorkloadStatus.OK, "1h")
        assertEquals(listOf("10.0.0.1","10.0.0.2"), g.addresses)
        assertEquals(2, g.listeners)
        assertEquals(5, g.routes)
    }

    @Test
    fun `K8sHttpRoute exposes every collection field`() {
        val r = K8sHttpRoute("r","api","ns",
            parents = listOf("public"), hosts = listOf("api.example.com"),
            paths = listOf("/api/v1"), backends = listOf("api:80"),
            rules = 1, age = "1h", status = WorkloadStatus.OK)
        assertEquals(listOf("public"), r.parents)
        assertEquals(listOf("/api/v1"), r.paths)
    }

    @Test
    fun `Gateway API response envelopes wrap lists`() {
        val gc = K8sGatewayClass("istio","x", true,"1h")
        assertEquals(listOf(gc), GatewayClassesResponse(listOf(gc)).items)
        val gw = K8sGateway("g","x","ns","istio", emptyList(),1,0, WorkloadStatus.OK,"1h")
        assertEquals(listOf(gw), GatewaysResponse(listOf(gw)).items)
        val r = K8sHttpRoute("r","x","ns", emptyList(), emptyList(), emptyList(), emptyList(),0,"1h", WorkloadStatus.OK)
        assertEquals(listOf(r), HttpRoutesResponse(listOf(r)).items)
    }

    // ===== cert-manager =====

    @Test
    fun `K8sCertificate defaults null error`() {
        val c = K8sCertificate("c","tls","ns", emptyList(),"i","Ready","",30,15,"s")
        assertNull(c.error)
        assertEquals("rate limited", c.copy(error = "rate limited").error)
    }

    @Test
    fun `K8sIssuer defaults null namespace for cluster-scoped`() {
        val cluster = K8sIssuer("i","ClusterIssuer","x","acme","https://x","Ready","1d",10)
        assertNull(cluster.namespace)
        val ns = K8sIssuer("i","Issuer","x","ca","","Ready","1d",1, namespace = "internal")
        assertEquals("internal", ns.namespace)
    }

    @Test
    fun `cert-manager response envelopes wrap lists`() {
        val c = K8sCertificate("c","tls","ns", emptyList(),"i","Ready","",0,0,"s")
        assertEquals(listOf(c), CertificatesResponse(listOf(c)).items)
        val i = K8sIssuer("i","Issuer","x","ca","","Ready","1h",0)
        assertEquals(listOf(i), IssuersResponse(listOf(i)).items)
    }

    // ===== CNPG =====

    @Test
    fun `K8sCnpgCluster round-trips`() {
        val c = K8sCnpgCluster("primary","data",3,"primary-1","16.3","img","Healthy", WorkloadStatus.OK,
            "10d","0 2 * * *","2h ago")
        assertEquals(c, c.copy())
        assertEquals(3, c.instances)
    }

    @Test
    fun `CnpgClustersResponse wraps a list`() {
        val c = K8sCnpgCluster("p","data",1,"p","16.3","img","Healthy", WorkloadStatus.OK,"1h","","")
        assertEquals(listOf(c), CnpgClustersResponse(listOf(c)).items)
    }

    // ===== Operators / CustomResource =====

    @Test
    fun `K8sOperator round-trips`() {
        val op = K8sOperator("o","cnpg","1.0","postgresql.cnpg.io","ns", WorkloadStatus.OK,1,
            listOf("Cluster","Backup"),"desc")
        assertEquals(2, op.kinds.size)
        assertEquals(op, op.copy())
    }

    @Test
    fun `K8sCustomResource round-trips`() {
        val cr = K8sCustomResource("c","Cluster","g","v","ns","name","1h", WorkloadStatus.OK,"detail")
        assertEquals(cr, cr.copy())
    }

    @Test
    fun `Operators and CustomResources response envelopes wrap lists`() {
        val op = K8sOperator("o","x","1","g","ns", WorkloadStatus.OK,1, emptyList(),"")
        assertEquals(listOf(op), OperatorsResponse(listOf(op)).items)
        val cr = K8sCustomResource("c","K","g","v","ns","n","1h", WorkloadStatus.OK,"")
        assertEquals(listOf(cr), CustomResourcesResponse(listOf(cr)).items)
    }

    // ===== Mutations =====

    @Test
    fun `ApplyResult defaults null dryRun`() {
        val r = ApplyResult(true, listOf("Pod/default/x"), emptyList())
        assertNull(r.dryRun)
        val dryRun = r.copy(dryRun = "# would apply")
        assertEquals("# would apply", dryRun.dryRun)
    }

    @Test
    fun `ApplyFailure round-trips resource and error`() {
        val f = ApplyFailure(resource = "Pod/default/x", error = "validation failed")
        assertEquals("validation failed", f.error)
        assertEquals(f, f.copy())
    }

    @Test
    fun `DeleteResponse defaults null details`() {
        val ok = DeleteResponse(deleted = true)
        assertNull(ok.details)
        val partial = DeleteResponse(deleted = false, details = "finalizer still attached")
        assertEquals("finalizer still attached", partial.details)
    }

    @Test
    fun `ApplyManifestRequest exposes manifest and dryRun`() {
        val req = ApplyManifestRequest(manifest = "apiVersion: v1\n", dryRun = true)
        assertEquals("apiVersion: v1\n", req.manifest)
        assertTrue(req.dryRun)
    }

    @Test
    fun `ScaleWorkloadRequest carries the replica count`() {
        val req = ScaleWorkloadRequest(replicas = 5)
        assertEquals(5, req.replicas)
    }

    @Test
    fun `CreateNamespaceRequest defaults null labels`() {
        val req = CreateNamespaceRequest(name = "ns")
        assertNull(req.labels)
        val labels = buildJsonObject { put("env", JsonPrimitive("dev")) }
        assertEquals(labels, req.copy(labels = labels).labels)
    }

    // ===== Namespace + LogLine =====

    @Test
    fun `Namespace round-trips`() {
        val n = Namespace(name="default",status="Active",workloads=2,pods=2,services=1,age="1d")
        assertEquals(n, n.copy())
        assertEquals(2, n.workloads)
    }

    @Test
    fun `NamespacesResponse wraps a list`() {
        val n = Namespace(name="default",status="Active",workloads=0,pods=0,services=0,age="0s")
        assertEquals(listOf(n), NamespacesResponse(listOf(n)).items)
    }

    @Test
    fun `LogLine carries every component of a streamed log record`() {
        val l = LogLine(pod="p", container="c", timestamp="2026-05-15T10:00:00Z",
            level=EventLevel.WARN, message="deprecation notice")
        assertEquals(EventLevel.WARN, l.level)
        assertEquals(l, l.copy())
    }

    @Test
    fun `YamlResponse wraps yaml payload`() {
        val y = YamlResponse(yaml = "apiVersion: v1\nkind: Pod\n")
        assertEquals("apiVersion: v1\nkind: Pod\n", y.yaml)
    }

    @Test
    fun `LogLevelInference still covered from its dedicated test`() {
        // Sanity check — the object is reachable and the contract is the
        // documented INFO fall-through.
        assertEquals(EventLevel.INFO, LogLevelInference.classify("ordinary line"))
    }
}

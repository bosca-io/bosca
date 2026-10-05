package bosca.kubernetes.controller

import bosca.kubernetes.model.CertificatesResponse
import bosca.kubernetes.model.ConfigKind
import bosca.kubernetes.model.ConfigResource
import bosca.kubernetes.model.ConfigResourceEntry
import bosca.kubernetes.model.HelmStatus
import bosca.kubernetes.model.K8sCertificate
import bosca.kubernetes.model.K8sCnpgBackup
import bosca.kubernetes.model.K8sCnpgCluster
import bosca.kubernetes.model.K8sCnpgClusterDetail
import bosca.kubernetes.model.K8sCnpgInstance
import bosca.kubernetes.model.K8sCnpgParameter
import bosca.kubernetes.model.K8sCustomResource
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
import bosca.kubernetes.model.K8sOperator
import bosca.kubernetes.model.K8sPvc
import bosca.kubernetes.model.K8sRole
import bosca.kubernetes.model.K8sRoleBinding
import bosca.kubernetes.model.K8sRoleSubject
import bosca.kubernetes.model.K8sService
import bosca.kubernetes.model.K8sServiceAccount
import bosca.kubernetes.model.K8sStorageClass
import bosca.kubernetes.model.WorkloadStatus
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Coverage for the Helm, Gateway API, cert-manager, Cilium, CNPG, and
 * "read path" type controllers — all per-field projection tests in one
 * file because every controller is shaped identically (record → field).
 *
 * These guard against the same regression class that bit `LogLine`
 * (#14 in K8S-17): an `@Field` accessor that's accidentally dropped
 * causes graphql-java's non-null validator to bubble null at
 * serialization time. Test asserts that the value comes through verbatim.
 */
class AdditionalTypeControllersTest {

    // ===== Helm =====

    @Test
    fun `HelmRepoTypeController exposes every helm repo field`() {
        val ctrl = HelmRepoTypeController()
        val repo = K8sHelmRepo(name = "bitnami", url = "https://charts.bitnami.com/bitnami", type = "http", charts = 240, lastUpdate = "just now")
        assertEquals("bitnami", ctrl.name(repo))
        assertEquals("https://charts.bitnami.com/bitnami", ctrl.url(repo))
        assertEquals("http", ctrl.type(repo))
        assertEquals(240, ctrl.charts(repo))
        assertEquals("just now", ctrl.lastUpdate(repo))
    }

    @Test
    fun `HelmChartTypeController exposes every chart field and optional icon`() {
        val ctrl = HelmChartTypeController()
        val chart = K8sHelmChart(
            id = "bitnami/nginx",
            name = "nginx",
            repo = "bitnami",
            version = "16.0.0",
            appVersion = "1.27.0",
            description = "NGINX Open Source",
            icon = "https://example.com/nginx.png",
        )
        assertEquals("bitnami/nginx", ctrl.id(chart))
        assertEquals("nginx", ctrl.name(chart))
        assertEquals("bitnami", ctrl.repo(chart))
        assertEquals("16.0.0", ctrl.version(chart))
        assertEquals("1.27.0", ctrl.appVersion(chart))
        assertEquals("NGINX Open Source", ctrl.description(chart))
        assertEquals("https://example.com/nginx.png", ctrl.icon(chart))
    }

    @Test
    fun `HelmChartTypeController icon is null when not provided`() {
        val ctrl = HelmChartTypeController()
        val chart = K8sHelmChart(id = "x/y", name = "y", repo = "x", version = "1.0", appVersion = "1.0", description = "")
        assertNull(ctrl.icon(chart))
    }

    @Test
    fun `HelmChartVersionTypeController exposes chart-version fields`() {
        val ctrl = HelmChartVersionTypeController()
        val v = K8sHelmChartVersion(version = "6.11.2", appVersion = "6.11.2", released = "2024-01-01", current = true)
        assertEquals("6.11.2", ctrl.version(v))
        assertEquals("6.11.2", ctrl.appVersion(v))
        assertEquals("2024-01-01", ctrl.released(v))
        assertEquals(true, ctrl.current(v))
    }

    @Test
    fun `HelmChartValuesTypeController exposes default values and optional schema`() {
        val ctrl = HelmChartValuesTypeController()
        val schema = buildJsonObject { put("type", JsonPrimitive("object")) }
        val v = K8sHelmChartValues(defaultValues = "replicaCount: 1\n", schema = schema)
        assertEquals("replicaCount: 1\n", ctrl.defaultValues(v))
        assertEquals(schema, ctrl.schema(v))
    }

    @Test
    fun `HelmChartValuesTypeController schema is null when chart omits it`() {
        val ctrl = HelmChartValuesTypeController()
        val v = K8sHelmChartValues(defaultValues = "")
        assertNull(ctrl.schema(v))
    }

    @Test
    fun `HelmReleaseTypeController exposes every release field`() {
        val ctrl = HelmReleaseTypeController()
        val release = K8sHelmRelease(
            id = "uid-1", name = "podinfo", namespace = "default",
            chart = "podinfo", chartVersion = "6.11.2", appVersion = "6.11.2",
            revision = 1, status = HelmStatus.DEPLOYED,
            updated = "just now", installed = "just now",
            repo = "podinfo", repoUrl = "https://stefanprodan.github.io/podinfo",
            description = "Install complete",
        )
        assertEquals("uid-1", ctrl.id(release))
        assertEquals("podinfo", ctrl.name(release))
        assertEquals("default", ctrl.namespace(release))
        assertEquals("podinfo", ctrl.chart(release))
        assertEquals("6.11.2", ctrl.chartVersion(release))
        assertEquals("6.11.2", ctrl.appVersion(release))
        assertEquals(1, ctrl.revision(release))
        assertEquals(HelmStatus.DEPLOYED, ctrl.status(release))
        assertEquals("just now", ctrl.updated(release))
        assertEquals("just now", ctrl.installed(release))
        assertEquals("podinfo", ctrl.repo(release))
        assertEquals("https://stefanprodan.github.io/podinfo", ctrl.repoUrl(release))
        assertEquals("Install complete", ctrl.description(release))
    }

    @Test
    fun `HelmRevisionTypeController exposes every revision field`() {
        val ctrl = HelmRevisionTypeController()
        val rev = K8sHelmRevision(
            revision = 3, updated = "1d ago", status = HelmStatus.SUPERSEDED,
            chart = "podinfo", appVersion = "6.5.0", description = "Upgrade complete",
        )
        assertEquals(3, ctrl.revision(rev))
        assertEquals("1d ago", ctrl.updated(rev))
        assertEquals(HelmStatus.SUPERSEDED, ctrl.status(rev))
        assertEquals("podinfo", ctrl.chart(rev))
        assertEquals("6.5.0", ctrl.appVersion(rev))
        assertEquals("Upgrade complete", ctrl.description(rev))
    }

    // ===== Gateway API =====

    @Test
    fun `GatewayClassTypeController exposes every gatewayclass field`() {
        val ctrl = GatewayClassTypeController()
        val gc = K8sGatewayClass(name = "istio", controller = "istio.io/gateway-controller", accepted = true, age = "5d")
        assertEquals("istio", ctrl.name(gc))
        assertEquals("istio.io/gateway-controller", ctrl.controller(gc))
        assertEquals(true, ctrl.accepted(gc))
        assertEquals("5d", ctrl.age(gc))
    }

    @Test
    fun `GatewayTypeController exposes every gateway field`() {
        val ctrl = GatewayTypeController()
        val g = K8sGateway(
            id = "gw-1", name = "public", namespace = "istio-system", gatewayClass = "istio",
            addresses = listOf("10.0.0.1"), listeners = 2, routes = 5, status = WorkloadStatus.OK, age = "1h",
        )
        assertEquals("gw-1", ctrl.id(g))
        assertEquals("public", ctrl.name(g))
        assertEquals("istio-system", ctrl.namespace(g))
        assertEquals("istio", ctrl.gatewayClass(g))
        assertEquals(listOf("10.0.0.1"), ctrl.addresses(g))
        assertEquals(2, ctrl.listeners(g))
        assertEquals(5, ctrl.routes(g))
        assertEquals(WorkloadStatus.OK, ctrl.status(g))
        assertEquals("1h", ctrl.age(g))
    }

    @Test
    fun `HttpRouteTypeController exposes every httproute field`() {
        val ctrl = HttpRouteTypeController()
        val r = K8sHttpRoute(
            id = "r-1", name = "api", namespace = "default",
            parents = listOf("public"), hosts = listOf("api.example.com"), paths = listOf("/api"),
            backends = listOf("api-service:8080"), rules = 1, age = "30m", status = WorkloadStatus.OK,
        )
        assertEquals("r-1", ctrl.id(r))
        assertEquals("api", ctrl.name(r))
        assertEquals("default", ctrl.namespace(r))
        assertEquals(listOf("public"), ctrl.parents(r))
        assertEquals(listOf("api.example.com"), ctrl.hosts(r))
        assertEquals(listOf("/api"), ctrl.paths(r))
        assertEquals(listOf("api-service:8080"), ctrl.backends(r))
        assertEquals(1, ctrl.rules(r))
        assertEquals("30m", ctrl.age(r))
        assertEquals(WorkloadStatus.OK, ctrl.status(r))
    }

    // ===== cert-manager =====

    @Test
    fun `CertificateResourceTypeController exposes every certificate field`() {
        val ctrl = CertificateResourceTypeController()
        val cert = K8sCertificate(
            id = "cert-1", name = "api-tls", namespace = "default",
            dns = listOf("api.example.com"), issuer = "letsencrypt-prod",
            status = "Ready", readySince = "2h ago",
            expiresInDays = 60, renewsInDays = 30, secretName = "api-tls",
            error = null,
        )
        assertEquals("cert-1", ctrl.id(cert))
        assertEquals("api-tls", ctrl.name(cert))
        assertEquals("default", ctrl.namespace(cert))
        assertEquals(listOf("api.example.com"), ctrl.dns(cert))
        assertEquals("letsencrypt-prod", ctrl.issuer(cert))
        assertEquals("Ready", ctrl.status(cert))
        assertEquals("2h ago", ctrl.readySince(cert))
        assertEquals(60, ctrl.expiresInDays(cert))
        assertEquals(30, ctrl.renewsInDays(cert))
        assertEquals("api-tls", ctrl.secretName(cert))
        assertNull(ctrl.error(cert))
    }

    @Test
    fun `CertificateResourceTypeController surfaces issuance error when present`() {
        val ctrl = CertificateResourceTypeController()
        val cert = K8sCertificate(
            id = "x", name = "x", namespace = "default", dns = emptyList(),
            issuer = "x", status = "Failed", readySince = "",
            expiresInDays = 0, renewsInDays = -1, secretName = "",
            error = "rate limited by ACME server",
        )
        assertEquals("rate limited by ACME server", ctrl.error(cert))
    }

    @Test
    fun `IssuerResourceTypeController exposes every issuer field`() {
        val ctrl = IssuerResourceTypeController()
        val issuer = K8sIssuer(
            id = "i-1", kind = "ClusterIssuer", name = "letsencrypt-prod",
            type = "acme", server = "https://acme-v02.api.letsencrypt.org/directory",
            status = "Ready", age = "30d", certs = 12, namespace = null,
        )
        assertEquals("i-1", ctrl.id(issuer))
        assertEquals("ClusterIssuer", ctrl.kind(issuer))
        assertEquals("letsencrypt-prod", ctrl.name(issuer))
        assertEquals("acme", ctrl.type(issuer))
        assertEquals("https://acme-v02.api.letsencrypt.org/directory", ctrl.server(issuer))
        assertEquals("Ready", ctrl.status(issuer))
        assertEquals("30d", ctrl.age(issuer))
        assertEquals(12, ctrl.certs(issuer))
        assertNull(ctrl.namespace(issuer))
    }

    @Test
    fun `IssuerResourceTypeController namespace is set for namespaced Issuers`() {
        val ctrl = IssuerResourceTypeController()
        val issuer = K8sIssuer(
            id = "i-2", kind = "Issuer", name = "internal-ca", type = "ca", server = "",
            status = "Ready", age = "5d", certs = 0, namespace = "internal",
        )
        assertEquals("internal", ctrl.namespace(issuer))
    }

    // ===== CNPG =====

    @Test
    fun `CnpgClusterTypeController exposes every cluster field`() {
        val ctrl = CnpgClusterTypeController()
        val c = K8sCnpgCluster(
            name = "primary", namespace = "data",
            instances = 3, primary = "primary-1",
            postgresVersion = "16.3", image = "ghcr.io/cloudnative-pg/postgresql:16.3",
            status = "Cluster in healthy state", statusKind = WorkloadStatus.OK,
            age = "10d",
            backupSchedule = "0 2 * * *",
            lastBackup = "2h ago",
        )
        assertEquals("primary", ctrl.name(c))
        assertEquals("data", ctrl.namespace(c))
        assertEquals(3, ctrl.instances(c))
        assertEquals("primary-1", ctrl.primary(c))
        assertEquals("16.3", ctrl.postgresVersion(c))
        assertEquals("ghcr.io/cloudnative-pg/postgresql:16.3", ctrl.image(c))
        assertEquals("Cluster in healthy state", ctrl.status(c))
        assertEquals(WorkloadStatus.OK, ctrl.statusKind(c))
        assertEquals("10d", ctrl.age(c))
        assertEquals("0 2 * * *", ctrl.backupSchedule(c))
        assertEquals("2h ago", ctrl.lastBackup(c))
    }

    @Test
    fun `CnpgInstanceTypeController exposes every instance field`() {
        val ctrl = CnpgInstanceTypeController()
        val i = K8sCnpgInstance(
            name = "primary-1", role = "primary", status = "Ready", ready = true,
            node = "node-a", zone = "us-east-1a", pvcSize = "100Gi", restarts = 0, age = "10d",
        )
        assertEquals("primary-1", ctrl.name(i))
        assertEquals("primary", ctrl.role(i))
        assertEquals("Ready", ctrl.status(i))
        assertEquals(true, ctrl.ready(i))
        assertEquals("node-a", ctrl.node(i))
        assertEquals("us-east-1a", ctrl.zone(i))
        assertEquals("100Gi", ctrl.pvcSize(i))
        assertEquals(0, ctrl.restarts(i))
        assertEquals("10d", ctrl.age(i))
    }

    @Test
    fun `CnpgBackupTypeController exposes every backup field`() {
        val ctrl = CnpgBackupTypeController()
        val b = K8sCnpgBackup(
            name = "backup-1", method = "barmanObjectStore", phase = "completed",
            statusKind = WorkloadStatus.OK, started = "2h ago", completed = "1h ago", duration = "12m",
        )
        assertEquals("backup-1", ctrl.name(b))
        assertEquals("barmanObjectStore", ctrl.method(b))
        assertEquals("completed", ctrl.phase(b))
        assertEquals(WorkloadStatus.OK, ctrl.statusKind(b))
        assertEquals("2h ago", ctrl.started(b))
        assertEquals("1h ago", ctrl.completed(b))
        assertEquals("12m", ctrl.duration(b))
    }

    @Test
    fun `CnpgParameterTypeController exposes key and value`() {
        val ctrl = CnpgParameterTypeController()
        val p = K8sCnpgParameter(key = "max_connections", value = "200")
        assertEquals("max_connections", ctrl.key(p))
        assertEquals("200", ctrl.value(p))
    }

    @Test
    fun `CnpgClusterDetailTypeController exposes summary, spec, and nested lists`() {
        val ctrl = CnpgClusterDetailTypeController()
        val cluster = K8sCnpgCluster(
            name = "primary", namespace = "data", instances = 3, primary = "primary-1",
            postgresVersion = "16.3", image = "ghcr.io/cloudnative-pg/postgresql:16.3",
            status = "Cluster in healthy state", statusKind = WorkloadStatus.OK, age = "10d",
            backupSchedule = "0 2 * * *", lastBackup = "2h ago",
        )
        val instance = K8sCnpgInstance(
            name = "primary-1", role = "primary", status = "Ready", ready = true,
            node = "node-a", zone = "us-east-1a", pvcSize = "100Gi", restarts = 0, age = "10d",
        )
        val backup = K8sCnpgBackup(
            name = "backup-1", method = "barmanObjectStore", phase = "completed",
            statusKind = WorkloadStatus.OK, started = "2h ago", completed = "1h ago", duration = "12m",
        )
        val parameter = K8sCnpgParameter(key = "max_connections", value = "200")
        val d = K8sCnpgClusterDetail(
            cluster = cluster, storageSize = "100Gi", storageClass = "gp3",
            backupDestinationPath = "s3://backups/primary", backupRetention = "30d",
            instances = listOf(instance), backups = listOf(backup), parameters = listOf(parameter),
        )
        assertEquals(cluster, ctrl.cluster(d))
        assertEquals("100Gi", ctrl.storageSize(d))
        assertEquals("gp3", ctrl.storageClass(d))
        assertEquals("s3://backups/primary", ctrl.backupDestinationPath(d))
        assertEquals("30d", ctrl.backupRetention(d))
        assertEquals(listOf(instance), ctrl.instances(d))
        assertEquals(listOf(backup), ctrl.backups(d))
        assertEquals(listOf(parameter), ctrl.parameters(d))
    }

    // ===== ConfigResource / Entry =====

    @Test
    fun `ConfigResourceTypeController exposes every config field`() {
        val ctrl = ConfigResourceTypeController()
        val cm = ConfigResource(
            id = "cm-1", kind = ConfigKind.CONFIG_MAP, name = "settings", namespace = "default",
            keys = listOf("LOG_LEVEL", "FEATURE_FLAG_X"), size = "1.2 KiB", age = "5h",
            secretType = null, managedBy = "Helm",
        )
        assertEquals("cm-1", ctrl.id(cm))
        assertEquals(ConfigKind.CONFIG_MAP, ctrl.kind(cm))
        assertEquals("settings", ctrl.name(cm))
        assertEquals("default", ctrl.namespace(cm))
        assertEquals(listOf("LOG_LEVEL", "FEATURE_FLAG_X"), ctrl.keys(cm))
        assertEquals("1.2 KiB", ctrl.size(cm))
        assertEquals("5h", ctrl.age(cm))
        assertNull(ctrl.secretType(cm))
        assertEquals("Helm", ctrl.managedBy(cm))
    }

    @Test
    fun `ConfigResourceTypeController surfaces secretType when Secret`() {
        val ctrl = ConfigResourceTypeController()
        val sec = ConfigResource(
            id = "s-1", kind = ConfigKind.SECRET, name = "db-creds", namespace = "default",
            keys = listOf("password"), size = "60 B", age = "1d",
            secretType = "kubernetes.io/dockerconfigjson", managedBy = null,
        )
        assertEquals("kubernetes.io/dockerconfigjson", ctrl.secretType(sec))
        assertNull(ctrl.managedBy(sec))
    }

    @Test
    fun `ConfigResourceEntryTypeController exposes key and value`() {
        val ctrl = ConfigResourceEntryTypeController()
        val entry = ConfigResourceEntry(key = "LOG_LEVEL", value = "info")
        assertEquals("LOG_LEVEL", ctrl.key(entry))
        assertEquals("info", ctrl.value(entry))
    }

    // ===== Service / Ingress / NetworkPolicy =====

    @Test
    fun `ServiceTypeController exposes every service field`() {
        val ctrl = ServiceTypeController()
        val s = K8sService(
            id = "svc-1", name = "api", namespace = "default", type = "ClusterIP",
            clusterIP = "10.0.0.5", externalIP = "<none>",
            ports = listOf("80/TCP", "443/TCP"), selector = "app=api",
            age = "1d", endpoints = 3,
        )
        assertEquals("svc-1", ctrl.id(s))
        assertEquals("api", ctrl.name(s))
        assertEquals("default", ctrl.namespace(s))
        assertEquals("ClusterIP", ctrl.type(s))
        assertEquals("10.0.0.5", ctrl.clusterIP(s))
        assertEquals("<none>", ctrl.externalIP(s))
        assertEquals(listOf("80/TCP", "443/TCP"), ctrl.ports(s))
        assertEquals("app=api", ctrl.selector(s))
        assertEquals("1d", ctrl.age(s))
        assertEquals(3, ctrl.endpoints(s))
    }

    @Test
    fun `IngressTypeController exposes every ingress field`() {
        val ctrl = IngressTypeController()
        val i = K8sIngress(
            id = "ing-1", name = "main", namespace = "default", ingressClass = "nginx",
            hosts = listOf("example.com"), paths = listOf("/api"),
            backends = listOf("api:80"), tls = true, age = "1d",
        )
        assertEquals("ing-1", ctrl.id(i))
        assertEquals("main", ctrl.name(i))
        assertEquals("default", ctrl.namespace(i))
        assertEquals("nginx", ctrl.ingressClass(i))
        assertEquals(listOf("example.com"), ctrl.hosts(i))
        assertEquals(listOf("/api"), ctrl.paths(i))
        assertEquals(listOf("api:80"), ctrl.backends(i))
        assertEquals(true, ctrl.tls(i))
        assertEquals("1d", ctrl.age(i))
    }

    @Test
    fun `NetworkPolicyTypeController exposes every netpol field`() {
        val ctrl = NetworkPolicyTypeController()
        val n = K8sNetworkPolicy(
            id = "np-1", name = "default-deny", namespace = "default",
            podSelector = "<all>", ingress = "deny all", egress = "deny all",
            age = "5d",
        )
        assertEquals("np-1", ctrl.id(n))
        assertEquals("default-deny", ctrl.name(n))
        assertEquals("default", ctrl.namespace(n))
        assertEquals("<all>", ctrl.podSelector(n))
        assertEquals("deny all", ctrl.ingress(n))
        assertEquals("deny all", ctrl.egress(n))
        assertEquals("5d", ctrl.age(n))
    }

    // ===== Storage =====

    @Test
    fun `StorageClassTypeController exposes every storageclass field`() {
        val ctrl = StorageClassTypeController()
        val sc = K8sStorageClass(
            name = "fast-ssd", provisioner = "kubernetes.io/aws-ebs",
            reclaim = "Delete", binding = "WaitForFirstConsumer",
            isDefault = true, age = "30d", parameters = "type=gp3",
        )
        assertEquals("fast-ssd", ctrl.name(sc))
        assertEquals("kubernetes.io/aws-ebs", ctrl.provisioner(sc))
        assertEquals("Delete", ctrl.reclaim(sc))
        assertEquals("WaitForFirstConsumer", ctrl.binding(sc))
        assertEquals(true, ctrl.isDefault(sc))
        assertEquals("30d", ctrl.age(sc))
        assertEquals("type=gp3", ctrl.parameters(sc))
    }

    @Test
    fun `PvcTypeController exposes every pvc field with metrics present`() {
        val ctrl = PvcTypeController()
        val p = K8sPvc(
            id = "pvc-1", name = "data-postgres-0", namespace = "data",
            status = "Bound", volume = "pvc-abc123",
            capacity = "10Gi", accessMode = "RWO", storageClass = "fast-ssd",
            age = "5d", usedPercent = 47, workload = "StatefulSet/postgres",
        )
        assertEquals("pvc-1", ctrl.id(p))
        assertEquals("data-postgres-0", ctrl.name(p))
        assertEquals("data", ctrl.namespace(p))
        assertEquals("Bound", ctrl.status(p))
        assertEquals("pvc-abc123", ctrl.volume(p))
        assertEquals("10Gi", ctrl.capacity(p))
        assertEquals("RWO", ctrl.accessMode(p))
        assertEquals("fast-ssd", ctrl.storageClass(p))
        assertEquals("5d", ctrl.age(p))
        assertEquals(47, ctrl.usedPercent(p))
        assertEquals("StatefulSet/postgres", ctrl.workload(p))
    }

    @Test
    fun `PvcTypeController usedPercent null when metrics not collected`() {
        // The documented contract: null usedPercent renders an empty
        // bar rather than misleading with a fake 0%.
        val ctrl = PvcTypeController()
        val p = K8sPvc(
            id = "pvc-2", name = "data-x", namespace = "default",
            status = "Bound", volume = "pvc-x", capacity = "1Gi",
            accessMode = "RWO", storageClass = "standard", age = "1h",
        )
        assertNull(ctrl.usedPercent(p))
        assertNull(ctrl.workload(p))
    }

    // ===== RBAC =====

    @Test
    fun `RoleTypeController exposes every role field`() {
        val ctrl = RoleTypeController()
        val r = K8sRole(
            id = "r-1", kind = "ClusterRole", name = "cluster-admin", builtin = true,
            bindings = 12, rules = 5, age = "100d", namespace = null,
            description = "Allow super-user access",
        )
        assertEquals("r-1", ctrl.id(r))
        assertEquals("ClusterRole", ctrl.kind(r))
        assertEquals("cluster-admin", ctrl.name(r))
        assertEquals(true, ctrl.builtin(r))
        assertEquals(12, ctrl.bindings(r))
        assertEquals(5, ctrl.rules(r))
        assertEquals("100d", ctrl.age(r))
        assertNull(ctrl.namespace(r))
        assertEquals("Allow super-user access", ctrl.description(r))
    }

    @Test
    fun `RoleSubjectTypeController exposes subject fields`() {
        val ctrl = RoleSubjectTypeController()
        val s = K8sRoleSubject(kind = "ServiceAccount", name = "default", namespace = "default")
        assertEquals("ServiceAccount", ctrl.kind(s))
        assertEquals("default", ctrl.name(s))
        assertEquals("default", ctrl.namespace(s))
    }

    @Test
    fun `RoleSubjectTypeController namespace is null for cluster-scoped subjects`() {
        val ctrl = RoleSubjectTypeController()
        val s = K8sRoleSubject(kind = "User", name = "alice@example.com")
        assertNull(ctrl.namespace(s))
    }

    @Test
    fun `RoleBindingTypeController exposes every binding field`() {
        val ctrl = RoleBindingTypeController()
        val subjects = listOf(K8sRoleSubject(kind = "Group", name = "admins"))
        val b = K8sRoleBinding(
            id = "rb-1", kind = "ClusterRoleBinding", name = "admin-binding",
            role = "cluster-admin", subjects = subjects, namespace = null, age = "100d",
        )
        assertEquals("rb-1", ctrl.id(b))
        assertEquals("ClusterRoleBinding", ctrl.kind(b))
        assertEquals("admin-binding", ctrl.name(b))
        assertEquals("cluster-admin", ctrl.role(b))
        assertEquals(subjects, ctrl.subjects(b))
        assertNull(ctrl.namespace(b))
        assertEquals("100d", ctrl.age(b))
    }

    @Test
    fun `ServiceAccountTypeController exposes every sa field`() {
        val ctrl = ServiceAccountTypeController()
        val sa = K8sServiceAccount(
            id = "sa-1", name = "default", namespace = "default",
            pods = 4, secrets = 1, bindings = 0, age = "1y",
            iamRole = "arn:aws:iam::123:role/eks-pod",
        )
        assertEquals("sa-1", ctrl.id(sa))
        assertEquals("default", ctrl.name(sa))
        assertEquals("default", ctrl.namespace(sa))
        assertEquals(4, ctrl.pods(sa))
        assertEquals(1, ctrl.secrets(sa))
        assertEquals(0, ctrl.bindings(sa))
        assertEquals("1y", ctrl.age(sa))
        assertEquals("arn:aws:iam::123:role/eks-pod", ctrl.iamRole(sa))
    }

    @Test
    fun `ServiceAccountTypeController iamRole null without IRSA`() {
        val ctrl = ServiceAccountTypeController()
        val sa = K8sServiceAccount(id = "x", name = "x", namespace = "x", pods = 0, secrets = 0, bindings = 0, age = "1m")
        assertNull(ctrl.iamRole(sa))
    }

    // ===== Operators / CustomResource =====

    @Test
    fun `OperatorTypeController exposes every operator field`() {
        val ctrl = OperatorTypeController()
        val op = K8sOperator(
            id = "op-1", name = "cnpg-operator", version = "1.23.0",
            group = "postgresql.cnpg.io", namespace = "cnpg-system",
            status = WorkloadStatus.OK, instances = 1,
            kinds = listOf("Cluster", "Backup", "ScheduledBackup"),
            description = "CloudNativePG operator",
        )
        assertEquals("op-1", ctrl.id(op))
        assertEquals("cnpg-operator", ctrl.name(op))
        assertEquals("1.23.0", ctrl.version(op))
        assertEquals("postgresql.cnpg.io", ctrl.group(op))
        assertEquals("cnpg-system", ctrl.namespace(op))
        assertEquals(WorkloadStatus.OK, ctrl.status(op))
        assertEquals(1, ctrl.instances(op))
        assertEquals(listOf("Cluster", "Backup", "ScheduledBackup"), ctrl.kinds(op))
        assertEquals("CloudNativePG operator", ctrl.description(op))
    }

    @Test
    fun `CustomResourceTypeController exposes every CR field`() {
        val ctrl = CustomResourceTypeController()
        val cr = K8sCustomResource(
            id = "cr-1", kind = "Cluster", group = "postgresql.cnpg.io",
            version = "v1", namespace = "data", name = "primary",
            age = "5d", status = WorkloadStatus.OK,
            detail = "Cluster in healthy state",
        )
        assertEquals("cr-1", ctrl.id(cr))
        assertEquals("Cluster", ctrl.kind(cr))
        assertEquals("postgresql.cnpg.io", ctrl.group(cr))
        assertEquals("v1", ctrl.version(cr))
        assertEquals("data", ctrl.namespace(cr))
        assertEquals("primary", ctrl.name(cr))
        assertEquals("5d", ctrl.age(cr))
        assertEquals(WorkloadStatus.OK, ctrl.status(cr))
        assertEquals("Cluster in healthy state", ctrl.detail(cr))
    }

    // Smoke: confirm Response envelopes (List<X>) are referenced
    // in their downstream paths — they're not type-controllers but
    // their construction is part of the wire surface tests rely on.
    @Test
    fun `Response envelopes hold the type-controlled items`() {
        val cert = K8sCertificate(
            id = "c", name = "c", namespace = "d", dns = emptyList(),
            issuer = "i", status = "Ready", readySince = "",
            expiresInDays = 0, renewsInDays = 0, secretName = "s",
        )
        assertEquals(listOf(cert), CertificatesResponse(listOf(cert)).items)
    }
}

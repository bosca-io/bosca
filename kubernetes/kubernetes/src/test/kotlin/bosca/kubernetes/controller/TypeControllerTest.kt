package bosca.kubernetes.controller

import bosca.kubernetes.model.ApplyFailure
import bosca.kubernetes.model.ApplyResult
import bosca.kubernetes.model.Cluster
import bosca.kubernetes.model.ClusterEnvironment
import bosca.kubernetes.model.ClusterHealth
import bosca.kubernetes.model.EventLevel
import bosca.kubernetes.model.K8sEvent
import bosca.kubernetes.model.K8sNode
import bosca.kubernetes.model.LogLine
import bosca.kubernetes.model.Namespace
import bosca.kubernetes.model.Pod
import bosca.kubernetes.model.PodsResponse
import bosca.kubernetes.model.Workload
import bosca.kubernetes.model.WorkloadKind
import bosca.kubernetes.model.WorkloadStatus
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Per-field projection tests for the @TypeController classes wiring
 * domain records to GraphQL field accessors.
 *
 * Bosca's graphql runtime uses an explicit-only field registry — properties
 * on returned types must have an `@Field` accessor or graphql-java's
 * NonNull validator surfaces `null` for every field at serialization time.
 * These tests pin that every declared property is exposed, and that the
 * exposure preserves the record's value (no incidental coercion / defaulting).
 */
@OptIn(ExperimentalUuidApi::class)
class TypeControllerTest {

    // ---------- LogLine ----------

    @Test
    fun `LogLineTypeController exposes every line field verbatim`() {
        val ctrl = LogLineTypeController()
        val line = LogLine(
            pod = "podinfo-abc",
            container = "podinfod",
            timestamp = "2026-05-15T15:44:42Z",
            level = EventLevel.WARN,
            message = "starting worker",
        )
        assertEquals("podinfo-abc", ctrl.pod(line))
        assertEquals("podinfod", ctrl.container(line))
        assertEquals("2026-05-15T15:44:42Z", ctrl.timestamp(line))
        assertEquals(EventLevel.WARN, ctrl.level(line))
        assertEquals("starting worker", ctrl.message(line))
    }

    // ---------- Namespace ----------

    @Test
    fun `NamespaceTypeController exposes every namespace field verbatim`() {
        val ctrl = NamespaceTypeController()
        val ns = Namespace(
            name = "kube-system",
            status = "Active",
            workloads = 4,
            pods = 8,
            services = 3,
            age = "5d",
        )
        assertEquals("kube-system", ctrl.name(ns))
        assertEquals("Active", ctrl.status(ns))
        assertEquals(4, ctrl.workloads(ns))
        assertEquals(8, ctrl.pods(ns))
        assertEquals(3, ctrl.services(ns))
        assertEquals("5d", ctrl.age(ns))
    }

    // ---------- Pod ----------

    @Test
    fun `PodTypeController exposes every required pod field`() {
        val ctrl = PodTypeController()
        val pod = Pod(
            id = "pod-uid-1",
            name = "podinfo-abc-def",
            namespace = "default",
            node = "control-plane",
            status = "Running",
            ready = "1/1",
            restarts = 2,
            age = "1h",
            cpu = 50,
            memory = 128,
            podIP = "10.244.0.5",
            hostIP = "172.18.0.2",
        )
        assertEquals("pod-uid-1", ctrl.id(pod))
        assertEquals("podinfo-abc-def", ctrl.name(pod))
        assertEquals("default", ctrl.namespace(pod))
        assertEquals("control-plane", ctrl.node(pod))
        assertEquals("Running", ctrl.status(pod))
        assertEquals("1/1", ctrl.ready(pod))
        assertEquals(2, ctrl.restarts(pod))
        assertEquals("1h", ctrl.age(pod))
        assertEquals(50, ctrl.cpu(pod))
        assertEquals(128, ctrl.memory(pod))
        assertEquals("10.244.0.5", ctrl.podIP(pod))
        assertEquals("172.18.0.2", ctrl.hostIP(pod))
    }

    @Test
    fun `PodTypeController propagates null podIP and hostIP`() {
        // Pending pods don't yet have an assigned IP — verify nullable
        // accessors keep null instead of defaulting to empty string.
        val ctrl = PodTypeController()
        val pod = Pod(
            id = "pod-pending",
            name = "podinfo-pending",
            namespace = "default",
            node = "",
            status = "Pending",
            ready = "0/1",
            restarts = 0,
            age = "5s",
            cpu = 0,
            memory = 0,
        )
        assertNull(ctrl.podIP(pod))
        assertNull(ctrl.hostIP(pod))
    }

    @Test
    fun `PodTypeController workload field is always null in v1 (lookup not wired)`() {
        // Workload resolution lives on the server-side cache; the type
        // controller returns null until that path is wired.
        val ctrl = PodTypeController()
        val pod = Pod(
            id = "x", name = "x", namespace = "x", node = "x", status = "x", ready = "x",
            restarts = 0, age = "x", cpu = 0, memory = 0,
        )
        assertNull(ctrl.workload(pod))
    }

    // ---------- Workload ----------

    @Test
    fun `WorkloadTypeController exposes every workload field`() {
        val ctrl = WorkloadTypeController()
        val labels = buildJsonObject { put("app", JsonPrimitive("podinfo")) }
        val w = Workload(
            id = "wl-1",
            kind = WorkloadKind.DEPLOYMENT,
            name = "podinfo",
            namespace = "default",
            ready = 3,
            desired = 3,
            status = WorkloadStatus.OK,
            image = "ghcr.io/stefanprodan/podinfo:6.11.2",
            age = "2h",
            cpu = 0.25,
            memory = 0.5,
            restarts = 1,
            strategy = "RollingUpdate",
            labels = labels,
        )
        assertEquals("wl-1", ctrl.id(w))
        assertEquals(WorkloadKind.DEPLOYMENT, ctrl.kind(w))
        assertEquals("podinfo", ctrl.name(w))
        assertEquals("default", ctrl.namespace(w))
        assertEquals(3, ctrl.ready(w))
        assertEquals(3, ctrl.desired(w))
        assertEquals(WorkloadStatus.OK, ctrl.status(w))
        assertEquals("ghcr.io/stefanprodan/podinfo:6.11.2", ctrl.image(w))
        assertEquals("2h", ctrl.age(w))
        assertEquals(0.25, ctrl.cpu(w))
        assertEquals(0.5, ctrl.memory(w))
        assertEquals(1, ctrl.restarts(w))
        assertEquals("RollingUpdate", ctrl.strategy(w))
        assertEquals(labels, ctrl.labels(w))
    }

    @Test
    fun `WorkloadTypeController labels field is null when not provided`() {
        val ctrl = WorkloadTypeController()
        val w = Workload(
            id = "wl", kind = WorkloadKind.STATEFUL_SET, name = "x", namespace = "default",
            ready = 1, desired = 1, status = WorkloadStatus.OK, image = "img", age = "1m",
            cpu = 0.0, memory = 0.0, restarts = 0, strategy = "",
        )
        assertNull(ctrl.labels(w))
    }

    // ---------- Node ----------

    @Test
    fun `NodeTypeController exposes every node field`() {
        val ctrl = NodeTypeController()
        val labels = buildJsonObject { put("kubernetes.io/role", JsonPrimitive("control-plane")) }
        val node = K8sNode(
            name = "control-plane",
            role = "control-plane",
            instance = "kind",
            zone = "local",
            status = "Ready",
            cpu = 35,
            memory = 60,
            pods = 11,
            age = "3h",
            version = "v1.31.0",
            taints = listOf("node-role.kubernetes.io/control-plane:NoSchedule"),
            labels = labels,
        )
        assertEquals("control-plane", ctrl.name(node))
        assertEquals("control-plane", ctrl.role(node))
        assertEquals("kind", ctrl.instance(node))
        assertEquals("local", ctrl.zone(node))
        assertEquals("Ready", ctrl.status(node))
        assertEquals(35, ctrl.cpu(node))
        assertEquals(60, ctrl.memory(node))
        assertEquals(11, ctrl.pods(node))
        assertEquals("3h", ctrl.age(node))
        assertEquals("v1.31.0", ctrl.version(node))
        assertEquals(listOf("node-role.kubernetes.io/control-plane:NoSchedule"), ctrl.taints(node))
        assertEquals(labels, ctrl.labels(node))
    }

    @Test
    fun `NodeTypeController exposes empty taint list and null labels`() {
        val ctrl = NodeTypeController()
        val node = K8sNode(
            name = "worker-1", role = "worker", instance = "m6i.2xlarge", zone = "us-east-1a",
            status = "Ready", cpu = 12, memory = 30, pods = 7, age = "1d", version = "v1.30.0",
        )
        assertEquals(emptyList(), ctrl.taints(node))
        assertNull(ctrl.labels(node))
    }

    // ---------- Event ----------

    @Test
    fun `EventTypeController exposes every event field`() {
        val ctrl = EventTypeController()
        val ts = OffsetDateTime.parse("2026-05-15T15:44:42Z")
        val event = K8sEvent(
            id = "evt-1",
            level = EventLevel.WARN,
            `when` = "2m ago",
            timestamp = ts,
            namespace = "default",
            involvedObject = "Pod/podinfo-abc",
            message = "Readiness probe failed",
            reason = "Unhealthy",
        )
        assertEquals("evt-1", ctrl.id(event))
        assertEquals(EventLevel.WARN, ctrl.level(event))
        assertEquals("2m ago", ctrl.whenLabel(event))
        assertEquals(ts, ctrl.timestamp(event))
        assertEquals("default", ctrl.namespace(event))
        assertEquals("Pod/podinfo-abc", ctrl.involvedObject(event))
        assertEquals("Readiness probe failed", ctrl.message(event))
        assertEquals("Unhealthy", ctrl.reason(event))
    }

    // ---------- Cluster ----------

    @Test
    fun `ClusterTypeController exposes every cluster field`() {
        val ctrl = ClusterTypeController()
        val id = UUID.random()
        val registered = OffsetDateTime.parse("2026-05-13T00:00:00Z")
        val lastSeen = OffsetDateTime.parse("2026-05-15T12:00:00Z")
        val cluster = Cluster(
            id = id,
            name = "prod-us-east-1",
            provider = "EKS",
            region = "us-east-1",
            environment = ClusterEnvironment.PRODUCTION,
            serverVersion = "v1.31.0",
            health = ClusterHealth.OK,
            nodes = 12,
            pods = 320,
            registeredAt = registered,
            lastSeenAt = lastSeen,
        )
        assertEquals(id, ctrl.id(cluster))
        assertEquals("prod-us-east-1", ctrl.name(cluster))
        assertEquals("EKS", ctrl.provider(cluster))
        assertEquals("us-east-1", ctrl.region(cluster))
        assertEquals(ClusterEnvironment.PRODUCTION, ctrl.environment(cluster))
        assertEquals("v1.31.0", ctrl.version(cluster))
        assertEquals(ClusterHealth.OK, ctrl.health(cluster))
        assertEquals(12, ctrl.nodes(cluster))
        assertEquals(320, ctrl.pods(cluster))
        assertEquals(registered, ctrl.registeredAt(cluster))
        assertEquals(lastSeen, ctrl.lastSeenAt(cluster))
    }

    @Test
    fun `ClusterTypeController lastSeenAt is null before first probe`() {
        // Freshly-registered clusters haven't been pinged by ClusterHealthProbe
        // yet — null until the first successful probe iteration.
        val ctrl = ClusterTypeController()
        val cluster = Cluster(
            id = UUID.random(), name = "new", provider = "kind", region = "local",
            environment = ClusterEnvironment.DEVELOPMENT,
        )
        assertNull(ctrl.lastSeenAt(cluster))
    }

    // ---------- ApplyResult / ApplyFailure ----------

    @Test
    fun `ApplyResultTypeController exposes succeeded applied failed and dryRun`() {
        val ctrl = ApplyResultTypeController()
        val applied = listOf("ConfigMap/default/smoke-cm")
        val failures = listOf(ApplyFailure(resource = "Service/default/bad", error = "missing port"))
        val res = ApplyResult(succeeded = false, applied = applied, failed = failures, dryRun = "# would apply...")
        assertEquals(false, ctrl.succeeded(res))
        assertEquals(applied, ctrl.applied(res))
        assertEquals(failures, ctrl.failed(res))
        assertEquals("# would apply...", ctrl.dryRun(res))
    }

    @Test
    fun `ApplyResultTypeController dryRun null on real apply`() {
        val ctrl = ApplyResultTypeController()
        val res = ApplyResult(succeeded = true, applied = listOf("Pod/default/x"), failed = emptyList())
        assertNull(ctrl.dryRun(res))
    }

    @Test
    fun `ApplyFailureTypeController exposes resource and error`() {
        val ctrl = ApplyFailureTypeController()
        val failure = ApplyFailure(resource = "Pod/default/x", error = "validation failed")
        assertEquals("Pod/default/x", ctrl.resource(failure))
        assertEquals("validation failed", ctrl.error(failure))
    }

    // ---------- PodPage ----------

    @Test
    fun `PodPageTypeController exposes total and items`() {
        val ctrl = PodPageTypeController()
        val pods = listOf(
            Pod(
                id = "p1", name = "p-1", namespace = "default", node = "n",
                status = "Running", ready = "1/1", restarts = 0, age = "5m",
                cpu = 0, memory = 0,
            ),
        )
        val page = PodsResponse(total = 42, items = pods)
        assertEquals(42, ctrl.total(page))
        assertEquals(pods, ctrl.items(page))
    }

    @Test
    fun `PodPageTypeController exposes empty page consistently`() {
        val ctrl = PodPageTypeController()
        val page = PodsResponse(total = 0, items = emptyList())
        assertEquals(0, ctrl.total(page))
        assertEquals(emptyList(), ctrl.items(page))
    }
}

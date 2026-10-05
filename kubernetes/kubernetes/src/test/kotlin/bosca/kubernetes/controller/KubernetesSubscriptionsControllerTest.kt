package bosca.kubernetes.controller

import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.kubernetes.model.Cluster
import bosca.kubernetes.model.ClusterEnvironment
import bosca.kubernetes.model.EventLevel
import bosca.kubernetes.model.LogLine
import bosca.kubernetes.service.ClusterService
import bosca.kubernetes.service.KubernetesControllerClient
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi

/**
 * Resolver-layer tests for [KubernetesSubscriptionsController].
 *
 * Pins the two structural invariants the kubernetes subscription path
 * depends on:
 *
 * 1. **Flat schema routing**: every resolver is annotated under
 *    `@TypeController(type = "Subscription")` and named `k8s*` — not
 *    nested under a sub-root. graphql-java's
 *    `SubscriptionExecutionStrategy` requires the root subscription
 *    field's fetcher to return a `Publisher`/`Flow`, so nesting an
 *    intermediate object root breaks the entire subscription chain.
 *
 * 2. **`withConnectionManager` wrapping**: the admin check and cluster
 *    lookup happen *inside* a `withConnectionManager { }` block so the
 *    DB connection is present in the coroutine context.
 *    `ClusterRepositoryImpl.getById` reads that context and throws
 *    `IllegalStateException: Connection not found in coroutine context`
 *    if it's missing — which is exactly what the unwrapped resolver
 *    crashed with before the fix.
 */
@OptIn(InternalDI::class, ExperimentalUuidApi::class)
class KubernetesSubscriptionsControllerTest {

    private val clusters = mockk<ClusterService>()
    private val controller = mockk<KubernetesControllerClient>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val connectionPool = mockk<ConnectionPool>()
    private val connectionManager = mockk<ConnectionManager>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()

    private val clusterId = UUID.random()

    private lateinit var ctrl: KubernetesSubscriptionsController

    @BeforeTest
    fun setup() {
        every { connectionPool.connection() } returns connectionManager
        ProviderRegistry.register(
            ConnectionPool::class,
            object : ObjectProvider<ConnectionPool> {
                override val type = ConnectionPool::class
                override suspend fun get() = connectionPool
            },
        )
        ctrl = KubernetesSubscriptionsController(clusters, controller, groups)
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        io.mockk.unmockkAll()
    }

    private fun fakeCluster() = Cluster(
        id = clusterId,
        name = "test",
        provider = "kind",
        region = "local",
        environment = ClusterEnvironment.DEVELOPMENT,
    )

    private fun line(message: String): LogLine = LogLine(
        pod = "smoke-nginx-abc",
        container = "nginx",
        timestamp = "2026-05-15T15:44:42Z",
        level = EventLevel.INFO,
        message = message,
    )

    @Test
    fun `k8sPodLogs emits lines from the upstream stream`() = runTest {
        coEvery { clusters.getById(clusterId) } returns fakeCluster()
        every {
            controller.streamPodLogs(auth, clusterId, "default", "pod", null, null, null)
        } returns flowOf(line("ready"), line("serving"))

        val collected = ctrl.k8sPodLogs(auth, clusterId, "default", "pod").toList()

        assertEquals(2, collected.size)
        assertEquals("ready", collected[0].message)
        assertEquals("serving", collected[1].message)
    }

    @Test
    fun `k8sPodLogs propagates container, tailLines, and follow args`() = runTest {
        coEvery { clusters.getById(clusterId) } returns fakeCluster()
        every {
            controller.streamPodLogs(auth, clusterId, "default", "pod", "sidecar", 50, true)
        } returns flowOf(line("from-sidecar"))

        val collected = ctrl.k8sPodLogs(
            authentication = auth,
            cluster = clusterId,
            namespace = "default",
            pod = "pod",
            container = "sidecar",
            tailLines = 50,
            follow = true,
        ).toList()

        assertEquals("from-sidecar", collected.single().message)
    }

    @Test
    fun `k8sPodLogs throws when the cluster is not registered`() = runTest {
        coEvery { clusters.getById(clusterId) } returns null

        val ex = assertFailsWith<IllegalStateException> {
            ctrl.k8sPodLogs(auth, clusterId, "default", "pod").toList()
        }
        assertEquals("Cluster $clusterId not found", ex.message)
    }

    @Test
    fun `k8sPodLogs admin failure shortcircuits before opening the stream`() = runTest {
        every { groups.verifyHasAdminGroup(auth) } throws SecurityException("not admin")

        assertFailsWith<SecurityException> {
            ctrl.k8sPodLogs(auth, clusterId, "default", "pod").toList()
        }
        io.mockk.verify(exactly = 0) {
            controller.streamPodLogs(any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ===== k8sEvents =====

    @Test
    fun `k8sEvents streams every event from the upstream watch`() = runTest {
        coEvery { clusters.getById(clusterId) } returns fakeCluster()
        val events = listOf(
            bosca.kubernetes.model.K8sEvent(
                id = "e1", level = bosca.kubernetes.model.EventLevel.INFO, `when` = "1m ago",
                timestamp = bosca.serialization.OffsetDateTime.parse("2026-05-15T10:00:00Z"),
                namespace = "default", involvedObject = "Pod/x", message = "Scheduled", reason = "Scheduled",
            ),
            bosca.kubernetes.model.K8sEvent(
                id = "e2", level = bosca.kubernetes.model.EventLevel.WARN, `when` = "30s ago",
                timestamp = bosca.serialization.OffsetDateTime.parse("2026-05-15T10:01:00Z"),
                namespace = "default", involvedObject = "Pod/x", message = "Probe failed", reason = "Unhealthy",
            ),
        )
        every { controller.streamEvents(auth, clusterId, "default") } returns flowOf(events[0], events[1])

        val out = ctrl.k8sEvents(auth, clusterId, "default").toList()

        kotlin.test.assertEquals(2, out.size)
        kotlin.test.assertEquals("Scheduled", out[0].message)
        kotlin.test.assertEquals("Probe failed", out[1].message)
    }

    @Test
    fun `k8sEvents passes null namespace for cluster-wide stream`() = runTest {
        coEvery { clusters.getById(clusterId) } returns fakeCluster()
        every { controller.streamEvents(auth, clusterId, null) } returns flowOf()
        ctrl.k8sEvents(auth, clusterId).toList()
        io.mockk.verify { controller.streamEvents(auth, clusterId, null) }
    }

    @Test
    fun `k8sEvents fails fast when cluster not found`() = runTest {
        coEvery { clusters.getById(clusterId) } returns null
        assertFailsWith<IllegalStateException> { ctrl.k8sEvents(auth, clusterId).toList() }
    }

    @Test
    fun `k8sEvents admin failure shortcuts before any upstream call`() = runTest {
        every { groups.verifyHasAdminGroup(auth) } throws SecurityException("not admin")
        assertFailsWith<SecurityException> { ctrl.k8sEvents(auth, clusterId).toList() }
        io.mockk.verify(exactly = 0) { controller.streamEvents(any(), any(), any()) }
    }

    // ===== k8sWorkloadStatus =====

    @Test
    fun `k8sWorkloadStatus passes namespace kind and name to the controller stream`() = runTest {
        coEvery { clusters.getById(clusterId) } returns fakeCluster()
        val snapshot = bosca.kubernetes.model.Workload(
            id = "wl-1", kind = bosca.kubernetes.model.WorkloadKind.DEPLOYMENT, name = "smoke-nginx",
            namespace = "default", ready = 2, desired = 3,
            status = bosca.kubernetes.model.WorkloadStatus.WARN,
            image = "nginx:alpine", age = "1h",
            cpu = 0.0, memory = 0.0, restarts = 0, strategy = "RollingUpdate",
        )
        every {
            controller.streamWorkloadStatus(auth, clusterId, "default",
                bosca.kubernetes.model.WorkloadKind.DEPLOYMENT, "smoke-nginx")
        } returns flowOf(snapshot)

        val out = ctrl.k8sWorkloadStatus(auth, clusterId, "default",
            bosca.kubernetes.model.WorkloadKind.DEPLOYMENT, "smoke-nginx").toList()

        kotlin.test.assertEquals(1, out.size)
        kotlin.test.assertEquals(2, out[0].ready)
        kotlin.test.assertEquals(3, out[0].desired)
        kotlin.test.assertEquals(bosca.kubernetes.model.WorkloadStatus.WARN, out[0].status)
    }

    @Test
    fun `k8sWorkloadStatus fails fast when cluster not found`() = runTest {
        coEvery { clusters.getById(clusterId) } returns null
        assertFailsWith<IllegalStateException> {
            ctrl.k8sWorkloadStatus(auth, clusterId, "ns",
                bosca.kubernetes.model.WorkloadKind.STATEFUL_SET, "db").toList()
        }
    }

    @Test
    fun `k8sWorkloadStatus admin failure shortcuts upstream`() = runTest {
        every { groups.verifyHasAdminGroup(auth) } throws SecurityException("not admin")
        assertFailsWith<SecurityException> {
            ctrl.k8sWorkloadStatus(auth, clusterId, "ns",
                bosca.kubernetes.model.WorkloadKind.DEPLOYMENT, "x").toList()
        }
        io.mockk.verify(exactly = 0) { controller.streamWorkloadStatus(any(), any(), any(), any(), any()) }
    }

    // ===== k8sHelmReleaseStatus =====

    @Test
    fun `k8sHelmReleaseStatus streams PENDING then DEPLOYED transitions`() = runTest {
        coEvery { clusters.getById(clusterId) } returns fakeCluster()
        val pending = release(bosca.kubernetes.model.HelmStatus.PENDING)
        val deployed = release(bosca.kubernetes.model.HelmStatus.DEPLOYED)
        every {
            controller.streamHelmReleaseStatus(auth, clusterId, "default", "podinfo-smoke")
        } returns flowOf(pending, deployed)

        val out = ctrl.k8sHelmReleaseStatus(auth, clusterId, "default", "podinfo-smoke").toList()

        kotlin.test.assertEquals(2, out.size)
        kotlin.test.assertEquals(bosca.kubernetes.model.HelmStatus.PENDING, out[0].status)
        kotlin.test.assertEquals(bosca.kubernetes.model.HelmStatus.DEPLOYED, out[1].status)
    }

    @Test
    fun `k8sHelmReleaseStatus fails fast when cluster not found`() = runTest {
        coEvery { clusters.getById(clusterId) } returns null
        assertFailsWith<IllegalStateException> {
            ctrl.k8sHelmReleaseStatus(auth, clusterId, "default", "rel").toList()
        }
    }

    @Test
    fun `k8sHelmReleaseStatus admin failure shortcuts upstream`() = runTest {
        every { groups.verifyHasAdminGroup(auth) } throws SecurityException("not admin")
        assertFailsWith<SecurityException> {
            ctrl.k8sHelmReleaseStatus(auth, clusterId, "default", "rel").toList()
        }
        io.mockk.verify(exactly = 0) { controller.streamHelmReleaseStatus(any(), any(), any(), any()) }
    }

    // ===== Mid-stream admin revocation =====

    @Test
    fun `k8sPodLogs closes the stream when admin group is revoked mid-flight`() = runTest {
        coEvery { clusters.getById(clusterId) } returns fakeCluster()
        // First emit lands while admin; the upstream then suspends
        // indefinitely so the watchdog gets a chance to fire on the
        // virtual scheduler.
        every {
            controller.streamPodLogs(auth, clusterId, "default", "pod", null, null, null)
        } returns kotlinx.coroutines.flow.flow {
            emit(line("first"))
            kotlinx.coroutines.awaitCancellation()
        }
        // hasAdminGroup flips to false on the *next* watchdog tick — the
        // first pass (at subscribe time, before the upstream emits)
        // already succeeded via verifyHasAdminGroup with default
        // relaxed behaviour. Mid-stream check returns false, watchdog
        // throws, scope cancels, collector sees an IllegalStateException.
        every { groups.hasAdminGroup(auth) } returns false

        assertFailsWith<IllegalStateException> {
            ctrl.k8sPodLogs(auth, clusterId, "default", "pod").toList()
        }
    }

    @Test
    fun `k8sPodLogs keeps streaming while admin is still in the group`() = runTest {
        coEvery { clusters.getById(clusterId) } returns fakeCluster()
        every {
            controller.streamPodLogs(auth, clusterId, "default", "pod", null, null, null)
        } returns flowOf(line("a"), line("b"))
        // Watchdog re-check returns true on every tick, so the only
        // thing that can finish the stream is the upstream completing
        // — which is exactly what we want to verify.
        every { groups.hasAdminGroup(auth) } returns true

        val collected = ctrl.k8sPodLogs(auth, clusterId, "default", "pod").toList()
        assertEquals(2, collected.size)
    }

    private fun release(status: bosca.kubernetes.model.HelmStatus) = bosca.kubernetes.model.K8sHelmRelease(
        id = "r", name = "podinfo-smoke", namespace = "default",
        chart = "podinfo", chartVersion = "6.11.2", appVersion = "6.11.2",
        revision = 1, status = status,
        updated = "just now", installed = "just now",
        repo = "podinfo", repoUrl = "https://x", description = "",
    )

    // ===== k8sResourcesWatch =====

    private fun change(kind: String, name: String) = bosca.kubernetes.model.ResourceChangeEvent(
        kind = kind, namespace = "default", name = name,
        action = "MODIFIED", timestamp = "2026-06-10T10:00:00Z",
    )

    @Test
    fun `k8sResourcesWatch streams change events and forwards kinds plus namespace`() = runTest {
        coEvery { clusters.getById(clusterId) } returns fakeCluster()
        every {
            controller.streamResourceChanges(auth, clusterId, listOf("Service", "Ingress"), "default")
        } returns flowOf(change("Service", "api"), change("Ingress", "api-ingress"))

        val out = ctrl.k8sResourcesWatch(auth, clusterId, listOf("Service", "Ingress"), "default").toList()

        assertEquals(2, out.size)
        assertEquals("Service", out[0].kind)
        assertEquals("api-ingress", out[1].name)
    }

    @Test
    fun `k8sResourcesWatch passes null namespace for cluster-wide watches`() = runTest {
        coEvery { clusters.getById(clusterId) } returns fakeCluster()
        every {
            controller.streamResourceChanges(auth, clusterId, listOf("Node"), null)
        } returns flowOf()
        ctrl.k8sResourcesWatch(auth, clusterId, listOf("Node")).toList()
        io.mockk.verify { controller.streamResourceChanges(auth, clusterId, listOf("Node"), null) }
    }

    @Test
    fun `k8sResourcesWatch fails fast when cluster not found`() = runTest {
        coEvery { clusters.getById(clusterId) } returns null
        assertFailsWith<IllegalStateException> {
            ctrl.k8sResourcesWatch(auth, clusterId, listOf("Service")).toList()
        }
    }

    @Test
    fun `k8sResourcesWatch admin failure shortcircuits before opening the stream`() = runTest {
        every { groups.verifyHasAdminGroup(auth) } throws SecurityException("not admin")
        assertFailsWith<SecurityException> {
            ctrl.k8sResourcesWatch(auth, clusterId, listOf("Service")).toList()
        }
        io.mockk.verify(exactly = 0) {
            controller.streamResourceChanges(any(), any(), any(), any())
        }
    }
}

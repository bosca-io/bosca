package bosca.kubernetes.controller

import bosca.kubernetes.model.ApplyResult
import bosca.kubernetes.model.Cluster
import bosca.kubernetes.model.ClusterEnvironment
import bosca.kubernetes.model.DeleteResponse
import bosca.kubernetes.model.HelmInstallInput
import bosca.kubernetes.model.HelmStatus
import bosca.kubernetes.model.HelmUpgradeInput
import bosca.kubernetes.model.K8sHelmRelease
import bosca.kubernetes.model.K8sHelmRepo
import bosca.kubernetes.model.HelmReposResponse
import bosca.kubernetes.model.Namespace
import bosca.kubernetes.model.RegisterClusterInput
import bosca.kubernetes.model.UpdateClusterInput
import bosca.kubernetes.model.Workload
import bosca.kubernetes.model.WorkloadKind
import bosca.kubernetes.model.WorkloadStatus
import bosca.kubernetes.service.ClusterService
import bosca.kubernetes.service.KubernetesControllerClient
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Resolver-layer tests for [KubernetesMutationsController]. Every mutation
 * carries the same authorization invariant — admin-only, three layers
 * deep — plus a destructive side effect on the cluster or the
 * credential store. These tests pin:
 *
 *   * `verifyHasAdminGroup` runs first on every method.
 *   * Cluster registration / update / rotate / remove delegate to the
 *     `ClusterService`; the controller-client invalidate is called on
 *     rotate and remove so the cached fabric8 client doesn't outlive
 *     the credential change.
 *   * Workload / config / helm mutations check cluster existence
 *     before delegating to the controller client.
 *   * Helm install / upgrade flow through the `HelmInstallInput` and
 *     `HelmUpgradeInput` fields with the cluster id sourced from the
 *     input rather than a separate arg.
 */
@OptIn(ExperimentalUuidApi::class)
class KubernetesMutationsControllerTest {

    private val clusters = mockk<ClusterService>()
    private val controller = mockk<KubernetesControllerClient>(relaxed = true)
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val clusterId = UUID.random()

    private lateinit var ctrl: KubernetesMutationsController

    @BeforeTest
    fun setup() {
        ctrl = KubernetesMutationsController(clusters, controller, groups)
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

    // ===== Cluster lifecycle =====

    @Test
    fun `registerCluster delegates to ClusterService register after admin check`() = runTest {
        val input = RegisterClusterInput(
            name = "new", provider = "EKS", region = "us-east-1",
            environment = ClusterEnvironment.PRODUCTION, kubeconfig = "apiVersion: v1\n",
        )
        val want = fakeCluster()
        coEvery { clusters.register(input) } returns want

        val got = ctrl.registerCluster(auth, input)

        assertSame(want, got)
        coVerify { clusters.register(input) }
    }

    @Test
    fun `updateCluster passes expectedVersion through for optimistic locking`() = runTest {
        val update = UpdateClusterInput(name = "renamed")
        val want = fakeCluster()
        coEvery { clusters.update(clusterId, update, 7L) } returns want

        assertSame(want, ctrl.updateCluster(auth, clusterId, update, 7L))
    }

    @Test
    fun `rotateKubeconfig updates credential then invalidates cached client`() = runTest {
        val want = fakeCluster()
        coEvery { clusters.rotateKubeconfig(clusterId, "new-kubeconfig") } returns want

        val got = ctrl.rotateKubeconfig(auth, clusterId, "new-kubeconfig")

        assertSame(want, got)
        // Invalidate must run AFTER rotateKubeconfig so the next read uses
        // the new credential. Test verifies both happen (order is enforced
        // by the resolver's sequential code).
        coVerify { clusters.rotateKubeconfig(clusterId, "new-kubeconfig") }
        coVerify { controller.invalidate(auth, clusterId) }
    }

    @Test
    fun `removeCluster invalidates cached client only after successful remove`() = runTest {
        coEvery { clusters.remove(clusterId) } returns true
        assertTrue(ctrl.removeCluster(auth, clusterId))
        coVerify { controller.invalidate(auth, clusterId) }
    }

    @Test
    fun `removeCluster does NOT invalidate when remove returns false`() = runTest {
        // Idempotent path: cluster already gone. No invalidate call —
        // there's nothing to invalidate.
        coEvery { clusters.remove(clusterId) } returns false
        assertFalse(ctrl.removeCluster(auth, clusterId))
        coVerify(exactly = 0) { controller.invalidate(any(), any()) }
    }

    // ===== Workload mutations =====

    @Test
    fun `scaleWorkload returns the workload from the controller client`() = runTest {
        val want = Workload(
            id = "w", kind = WorkloadKind.DEPLOYMENT, name = "x", namespace = "default",
            ready = 3, desired = 3, status = WorkloadStatus.OK, image = "img", age = "1h",
            cpu = 0.0, memory = 0.0, restarts = 0, strategy = "RollingUpdate",
        )
        coEvery { controller.scaleWorkload(auth, clusterId, "default", WorkloadKind.DEPLOYMENT, "x", 3) } returns want

        assertSame(want, ctrl.scaleWorkload(auth, clusterId, "default", WorkloadKind.DEPLOYMENT, "x", 3))
    }

    @Test
    fun `restartWorkload returns true on success`() = runTest {
        coEvery { controller.restartWorkload(auth, clusterId, "default", WorkloadKind.STATEFUL_SET, "db") } returns true
        assertTrue(ctrl.restartWorkload(auth, clusterId, "default", WorkloadKind.STATEFUL_SET, "db"))
    }

    @Test
    fun `deleteResource unwraps the DeleteResponse deleted flag`() = runTest {
        coEvery {
            controller.deleteResource(auth, clusterId, "ConfigMap", "x", "default", null)
        } returns DeleteResponse(deleted = true)
        assertTrue(ctrl.deleteResource(auth, clusterId, "ConfigMap", "x", "default"))
    }

    @Test
    fun `deleteResource returns false when the controller reports not-deleted`() = runTest {
        coEvery {
            controller.deleteResource(auth, clusterId, "Pod", "missing", "default", null)
        } returns DeleteResponse(deleted = false, details = "not found")
        assertFalse(ctrl.deleteResource(auth, clusterId, "Pod", "missing", "default"))
    }

    @Test
    fun `deleteResource passes group arg for CRD deletes`() = runTest {
        coEvery {
            controller.deleteResource(auth, clusterId, "Cluster", "primary", "data", "postgresql.cnpg.io")
        } returns DeleteResponse(deleted = true)
        assertTrue(ctrl.deleteResource(auth, clusterId, "Cluster", "primary", "data", "postgresql.cnpg.io"))
    }

    @Test
    fun `createNamespace propagates labels JSON`() = runTest {
        val labels = buildJsonObject { put("env", JsonPrimitive("dev")) }
        val want = Namespace(name = "x", status = "Active", workloads = 0, pods = 0, services = 0, age = "0s")
        coEvery { controller.createNamespace(auth, clusterId, "x", labels) } returns want

        assertSame(want, ctrl.createNamespace(auth, clusterId, "x", labels))
    }

    @Test
    fun `createNamespace works without labels`() = runTest {
        val want = Namespace(name = "x", status = "Active", workloads = 0, pods = 0, services = 0, age = "0s")
        coEvery { controller.createNamespace(auth, clusterId, "x", null) } returns want
        assertSame(want, ctrl.createNamespace(auth, clusterId, "x"))
    }

    @Test
    fun `applyManifest defaults dryRun to false when caller omits it`() = runTest {
        val want = ApplyResult(succeeded = true, applied = listOf("ConfigMap/default/x"), failed = emptyList())
        coEvery { controller.applyManifest(auth, clusterId, "yaml", false) } returns want

        assertSame(want, ctrl.applyManifest(auth, clusterId, "yaml"))
    }

    @Test
    fun `applyManifest passes dryRun true`() = runTest {
        val want = ApplyResult(
            succeeded = true, applied = listOf("ConfigMap/default/x"), failed = emptyList(),
            dryRun = "# would apply",
        )
        coEvery { controller.applyManifest(auth, clusterId, "yaml", true) } returns want
        assertSame(want, ctrl.applyManifest(auth, clusterId, "yaml", true))
    }

    // ===== Helm mutations =====

    @Test
    fun `helmRepoAdd plumbs every field`() = runTest {
        val repo = K8sHelmRepo("podinfo", "https://x", "http", 1, "now")
        coEvery { controller.helmRepoAdd(auth, "podinfo", "https://x", "u", "p") } returns repo
        assertSame(repo, ctrl.helmRepoAdd(auth, "podinfo", "https://x", "u", "p"))
    }

    @Test
    fun `helmRepoAdd null credentials still works for public repos`() = runTest {
        val repo = K8sHelmRepo("podinfo", "https://x", "http", 1, "now")
        coEvery { controller.helmRepoAdd(auth, "podinfo", "https://x", null, null) } returns repo
        assertSame(repo, ctrl.helmRepoAdd(auth, "podinfo", "https://x"))
    }

    @Test
    fun `helmRepoUpdate returns the refreshed repo list`() = runTest {
        val repos = listOf(K8sHelmRepo("podinfo", "https://x", "http", 1, "now"))
        coEvery { controller.helmRepoUpdate(auth) } returns HelmReposResponse(repos)
        assertEquals(repos, ctrl.helmRepoUpdate(auth))
    }

    @Test
    fun `helmRepoRemove returns the controller boolean`() = runTest {
        coEvery { controller.helmRepoRemove(auth, "podinfo") } returns true
        assertTrue(ctrl.helmRepoRemove(auth, "podinfo"))
    }

    @Test
    fun `helmInstall sources cluster id from the input and propagates every input field`() = runTest {
        val input = HelmInstallInput(
            cluster = clusterId, name = "rel", namespace = "ns", createNamespace = true,
            repo = "podinfo", chart = "podinfo", version = "6.11.2",
            values = "image:\n  tag: x\n", dryRun = false,
        )
        val want = release()
        coEvery {
            controller.helmInstall(auth, clusterId, "rel", "ns", true,
                "podinfo", "podinfo", "6.11.2", "image:\n  tag: x\n", false)
        } returns want

        assertSame(want, ctrl.helmInstall(auth, input))
    }

    @Test
    fun `helmInstall fails fast when the input's cluster is not registered`() = runTest {
        coEvery { clusters.getById(clusterId) } returns null
        val input = HelmInstallInput(
            cluster = clusterId, name = "rel", namespace = "ns",
            repo = "x", chart = "y", version = "1.0",
        )
        assertFailsWith<IllegalStateException> { ctrl.helmInstall(auth, input) }
    }

    @Test
    fun `helmUpgrade sources repo and chart from the input`() = runTest {
        val input = HelmUpgradeInput(
            cluster = clusterId, name = "rel", namespace = "ns",
            repo = "podinfo", chart = "podinfo", version = "6.11.3",
            values = "image:\n  tag: y\n", dryRun = false, resetValues = false,
        )
        val want = release()
        coEvery {
            controller.helmUpgrade(auth, clusterId, "rel", "ns", "6.11.3",
                "image:\n  tag: y\n", false, false, "podinfo", "podinfo")
        } returns want
        assertSame(want, ctrl.helmUpgrade(auth, input))
    }

    @Test
    fun `helmRollback returns the new release after rolling back`() = runTest {
        val want = release()
        coEvery { controller.helmRollback(auth, clusterId, "default", "rel", 2) } returns want
        assertSame(want, ctrl.helmRollback(auth, clusterId, "default", "rel", 2))
    }

    @Test
    fun `helmUninstall defaults keepHistory to false`() = runTest {
        coEvery { controller.helmUninstall(auth, clusterId, "default", "rel", false) } returns true
        assertTrue(ctrl.helmUninstall(auth, clusterId, "default", "rel"))
    }

    @Test
    fun `helmUninstall keepHistory true preserves revisions`() = runTest {
        coEvery { controller.helmUninstall(auth, clusterId, "default", "rel", true) } returns true
        assertTrue(ctrl.helmUninstall(auth, clusterId, "default", "rel", true))
    }

    // ===== Admin gating applies to every mutation =====

    @Test
    fun `admin failure shortcuts every mutation without side effects`() = runTest {
        every { groups.verifyHasAdminGroup(auth) } throws SecurityException("not admin")
        for (action in listOf<suspend () -> Any?>(
            { ctrl.registerCluster(auth, RegisterClusterInput("x","kind","local",ClusterEnvironment.DEVELOPMENT,"kc")) },
            { ctrl.updateCluster(auth, clusterId, UpdateClusterInput(), 0) },
            { ctrl.rotateKubeconfig(auth, clusterId, "kc") },
            { ctrl.removeCluster(auth, clusterId) },
            { ctrl.scaleWorkload(auth, clusterId, "ns", WorkloadKind.DEPLOYMENT, "x", 1) },
            { ctrl.restartWorkload(auth, clusterId, "ns", WorkloadKind.DEPLOYMENT, "x") },
            { ctrl.deleteResource(auth, clusterId, "Pod", "x", "ns", null) },
            { ctrl.createNamespace(auth, clusterId, "x") },
            { ctrl.applyManifest(auth, clusterId, "yaml") },
            { ctrl.helmRepoAdd(auth, "n", "u") },
            { ctrl.helmRepoUpdate(auth) },
            { ctrl.helmRepoRemove(auth, "n") },
            { ctrl.helmInstall(auth, HelmInstallInput(clusterId,"r","n", repo="x", chart="y", version="1")) },
            { ctrl.helmUpgrade(auth, HelmUpgradeInput(clusterId,"r","n",repo="r",chart="c",version="1")) },
            { ctrl.helmRollback(auth, clusterId, "n", "r", 1) },
            { ctrl.helmUninstall(auth, clusterId, "n", "r") },
        )) {
            assertFailsWith<SecurityException> { action() }
        }
        // No service or controller call must have been issued.
        coVerify(exactly = 0) { clusters.register(any()) }
        coVerify(exactly = 0) { clusters.remove(any()) }
        coVerify(exactly = 0) { controller.scaleWorkload(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { controller.helmInstall(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // ===== Input bounds =====
    //
    // The size / replica caps are deliberately conservative so a typo
    // or malicious admin can't accidentally DoS the cluster (huge
    // replica count) or the controller (giant manifest / values blob).

    @Test
    fun `scaleWorkload rejects a negative replica count`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            ctrl.scaleWorkload(auth, clusterId, "ns", WorkloadKind.DEPLOYMENT, "x", -1)
        }
    }

    @Test
    fun `scaleWorkload rejects a replica count above the cap`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            ctrl.scaleWorkload(
                auth, clusterId, "ns", WorkloadKind.DEPLOYMENT, "x",
                KubernetesMutationsController.MAX_REPLICAS + 1,
            )
        }
        coVerify(exactly = 0) { controller.scaleWorkload(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `applyManifest rejects a manifest above the byte cap`() = runTest {
        val oversize = "a".repeat(KubernetesMutationsController.MAX_MANIFEST_BYTES + 1)
        assertFailsWith<IllegalArgumentException> {
            ctrl.applyManifest(auth, clusterId, oversize)
        }
        coVerify(exactly = 0) { controller.applyManifest(any(), any(), any(), any()) }
    }

    @Test
    fun `helmInstall rejects values above the byte cap`() = runTest {
        val oversize = "a".repeat(KubernetesMutationsController.MAX_VALUES_BYTES + 1)
        val input = HelmInstallInput(
            cluster = clusterId, name = "rel", namespace = "ns",
            repo = "x", chart = "y", version = "1.0", values = oversize,
        )
        assertFailsWith<IllegalArgumentException> { ctrl.helmInstall(auth, input) }
        coVerify(exactly = 0) {
            controller.helmInstall(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `helmUpgrade rejects values above the byte cap`() = runTest {
        val oversize = "a".repeat(KubernetesMutationsController.MAX_VALUES_BYTES + 1)
        val input = HelmUpgradeInput(
            cluster = clusterId, name = "rel", namespace = "ns",
            repo = "x", chart = "y", version = "1.0", values = oversize,
        )
        assertFailsWith<IllegalArgumentException> { ctrl.helmUpgrade(auth, input) }
        coVerify(exactly = 0) {
            controller.helmUpgrade(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    private fun release() = K8sHelmRelease(
        id = "rel-1", name = "rel", namespace = "ns",
        chart = "podinfo", chartVersion = "6.11.2", appVersion = "6.11.2",
        revision = 1, status = HelmStatus.DEPLOYED,
        updated = "just now", installed = "just now",
        repo = "podinfo", repoUrl = "https://x", description = "Install complete",
    )
}

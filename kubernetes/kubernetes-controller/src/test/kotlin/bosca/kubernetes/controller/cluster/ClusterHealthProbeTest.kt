package bosca.kubernetes.controller.cluster

import bosca.db.withConnectionManager
import bosca.kubernetes.model.Cluster
import bosca.kubernetes.model.ClusterEnvironment
import bosca.kubernetes.repository.ClusterRepository
import bosca.serialization.UUID
import io.fabric8.kubernetes.api.model.NodeListBuilder
import io.fabric8.kubernetes.api.model.PodListBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.VersionInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [ClusterHealthProbe]'s success/failure ladder.
 *
 *   * Empty cluster list → probeAll returns silently without
 *     touching repo / pool.
 *   * Success path: writes serverVersion / OK / counts via
 *     [ClusterRepository.updateObservedState] and resets the
 *     consecutive-failure counter.
 *   * 1–2 failures → marks `WARN`; 3rd → marks `ERROR`. A success
 *     after errors resets the counter.
 *   * markHealth failures are swallowed so a transient DB issue
 *     doesn't cancel the probe loop.
 */
@OptIn(ExperimentalUuidApi::class)
class ClusterHealthProbeTest {

    private val pool = mockk<ClusterClientPool>(relaxed = true)
    private val informers = mockk<ClusterInformerRegistry>(relaxed = true)
    private val clusters = mockk<ClusterRepository>(relaxed = true)

    private val clusterId = UUID.random()

    private fun stubCluster(id: UUID = clusterId) = Cluster(
        id = id,
        name = "test",
        provider = "kind",
        region = "local",
        environment = ClusterEnvironment.DEVELOPMENT,
    )

    private fun stubHealthyClient(version: String = "v1.30.2", nodes: Int = 3, pods: Int = 42): KubernetesClient {
        val client = mockk<KubernetesClient>(relaxed = true)
        val versionInfo = mockk<VersionInfo>()
        every { versionInfo.gitVersion } returns version
        every { client.kubernetesVersion } returns versionInfo

        val nodeList = NodeListBuilder().apply {
            repeat(nodes) {
                addNewItem().withNewMetadata().withName("n$it").withUid("u$it").endMetadata().endItem()
            }
        }.build()
        every { client.nodes().list() } returns nodeList

        val podList = PodListBuilder().apply {
            repeat(pods) {
                addNewItem().withNewMetadata().withName("p$it").withUid("u$it").endMetadata().endItem()
            }
        }.build()
        every { client.pods().inAnyNamespace().list() } returns podList
        return client
    }

    @BeforeTest
    fun setup() {
        // The probe wraps every repo call in `withConnectionManager { … }` (the
        // background coroutine has no Connection in its context otherwise).
        // Mock the static
        // suspend wrapper as a pass-through so tests stay focused on the
        // probe's success/failure ladder and never reach a real DB.
        mockkStatic("bosca.db.ConnectionPoolKt")
        coEvery { withConnectionManager<Any?>(any()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.db.ConnectionPoolKt")
        io.mockk.unmockkAll()
    }

    @Test
    fun `probeAll on empty cluster list does no work`() = runTest {
        coEvery { clusters.list() } returns emptyList()
        ClusterHealthProbe(pool, informers, clusters).probeAll()
        coVerify(exactly = 0) { pool.get(any()) }
        coVerify(exactly = 0) { clusters.updateObservedState(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { clusters.markHealth(any(), any()) }
    }

    @Test
    fun `successful probe writes serverVersion OK and counts to updateObservedState`() = runTest {
        coEvery { clusters.list() } returns listOf(stubCluster())
        coEvery { pool.get(clusterId) } returns stubHealthyClient()

        ClusterHealthProbe(pool, informers, clusters).probeAll()

        coVerify(exactly = 1) {
            clusters.updateObservedState(
                id = clusterId,
                serverVersion = "v1.30.2",
                health = "OK",
                nodes = 3,
                pods = 42,
            )
        }
        coVerify(exactly = 0) { clusters.markHealth(any(), any()) }
        coVerify(exactly = 1) { informers.get(clusterId) }
    }

    @Test
    fun `failure under threshold marks health WARN`() = runTest {
        coEvery { clusters.list() } returns listOf(stubCluster())
        coEvery { pool.get(clusterId) } throws IllegalStateException("connection refused")

        ClusterHealthProbe(pool, informers, clusters).probeAll()

        coVerify(exactly = 1) { clusters.markHealth(clusterId, "WARN") }
        coVerify(exactly = 0) { clusters.updateObservedState(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `three consecutive failures escalate to ERROR`() = runTest {
        coEvery { clusters.list() } returns listOf(stubCluster())
        coEvery { pool.get(clusterId) } throws IllegalStateException("down")

        val probe = ClusterHealthProbe(pool, informers, clusters)
        probe.probeAll()
        probe.probeAll()
        probe.probeAll()

        coVerify(exactly = 2) { clusters.markHealth(clusterId, "WARN") }
        coVerify(exactly = 1) { clusters.markHealth(clusterId, "ERROR") }
    }

    @Test
    fun `a success after failures resets the counter so the next failure starts at WARN`() = runTest {
        coEvery { clusters.list() } returns listOf(stubCluster())

        // Two failures, then one success, then three more failures.
        coEvery { pool.get(clusterId) } throws IllegalStateException("blip")
        val probe = ClusterHealthProbe(pool, informers, clusters)
        probe.probeAll()
        probe.probeAll()

        coEvery { pool.get(clusterId) } returns stubHealthyClient()
        probe.probeAll()

        coEvery { pool.get(clusterId) } throws IllegalStateException("blip again")
        probe.probeAll()
        probe.probeAll()
        probe.probeAll()

        // Across the run: WARN was marked for failures 1, 2 (first burst) and 1, 2 (second burst).
        coVerify(exactly = 4) { clusters.markHealth(clusterId, "WARN") }
        // ERROR was only ever marked once (third failure of the second burst).
        coVerify(exactly = 1) { clusters.markHealth(clusterId, "ERROR") }
        // The recovery in the middle wrote OK once.
        coVerify(exactly = 1) {
            clusters.updateObservedState(clusterId, any(), "OK", any(), any())
        }
    }

    @Test
    fun `markHealth failure does not propagate out of probeOne`() = runTest {
        coEvery { clusters.list() } returns listOf(stubCluster())
        coEvery { pool.get(clusterId) } throws IllegalStateException("network down")
        coEvery { clusters.markHealth(any(), any()) } throws IllegalStateException("db blip")

        // Should not throw — the probe must keep cycling through the cluster list.
        ClusterHealthProbe(pool, informers, clusters).probeAll()
    }

    @Test
    fun `independent clusters track their own failure counters`() = runTest {
        val a = UUID.random()
        val b = UUID.random()
        coEvery { clusters.list() } returns listOf(stubCluster(a), stubCluster(b))
        coEvery { pool.get(a) } throws IllegalStateException("a-down")
        coEvery { pool.get(b) } returns stubHealthyClient()

        val probe = ClusterHealthProbe(pool, informers, clusters)
        repeat(3) { probe.probeAll() }

        // Cluster a hits ERROR on the third failure.
        coVerify(exactly = 1) { clusters.markHealth(a, "ERROR") }
        // Cluster b stays in the success path.
        coVerify(exactly = 3) {
            clusters.updateObservedState(b, any(), "OK", any(), any())
        }
        coVerify(exactly = 0) { clusters.markHealth(b, any()) }
    }

    @Test
    fun `close cancels the running loop without throwing`() {
        // Just smoke — exercising start + close with no clusters configured so the loop
        // doesn't actually do anything in the limited duration before we cancel it.
        val probe = ClusterHealthProbe(pool, informers, clusters, intervalMillis = 1_000_000L)
        probe.start()
        probe.start()  // idempotent
        probe.close()
        probe.close()  // also idempotent (loop is already null)
    }
}

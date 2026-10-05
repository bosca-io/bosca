package bosca.kubernetes.controller

import bosca.kubernetes.model.ClusterMetricsSample
import bosca.kubernetes.model.NodeMetricsListItem
import bosca.kubernetes.model.NodeMetricsSample
import bosca.kubernetes.model.NodesMetricsListSample
import bosca.kubernetes.model.PodMetricsListItem
import bosca.kubernetes.model.PodMetricsSample
import bosca.kubernetes.model.PodsMetricsListSample
import bosca.kubernetes.model.WorkloadMetricsListItem
import bosca.kubernetes.model.WorkloadStatus
import bosca.kubernetes.model.WorkloadStatusListItem
import bosca.kubernetes.model.WorkloadsMetricsListSample
import bosca.kubernetes.model.WorkloadsStatusListSample
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Per-field projection coverage for every metrics-stream payload type.
 *
 * These guard the regression that produced
 * "`PodsMetricsListSample.items` was declared `[PodMetricsListItem!]!`
 * but wrongly returned a null value": Bosca's explicit-only field
 * registry means a payload type returned from a subscription resolver
 * resolves every field to null unless a `@TypeController` exposes it.
 * Each test asserts the value comes through verbatim — including the
 * list wrappers, whose elements only render because their own item
 * controller exposes the element fields. Same shape as
 * [AdditionalTypeControllersTest].
 */
class MetricsTypeControllersTest {

    @Test
    fun `PodMetricsSampleTypeController exposes every field`() {
        val ctrl = PodMetricsSampleTypeController()
        val s = PodMetricsSample(namespace = "default", pod = "api-0", cpuMillicores = 120L, memoryBytes = 1_048_576L, timestamp = "2026-06-03T00:00:00Z")
        assertEquals("default", ctrl.namespace(s))
        assertEquals("api-0", ctrl.pod(s))
        assertEquals(120L, ctrl.cpuMillicores(s))
        assertEquals(1_048_576L, ctrl.memoryBytes(s))
        assertEquals("2026-06-03T00:00:00Z", ctrl.timestamp(s))
    }

    @Test
    fun `NodeMetricsSampleTypeController exposes every field`() {
        val ctrl = NodeMetricsSampleTypeController()
        val s = NodeMetricsSample(node = "node-1", cpuMillicores = 500L, memoryBytes = 2_097_152L, cpuPercent = 25, memoryPercent = 40, timestamp = "2026-06-03T00:00:01Z")
        assertEquals("node-1", ctrl.node(s))
        assertEquals(500L, ctrl.cpuMillicores(s))
        assertEquals(2_097_152L, ctrl.memoryBytes(s))
        assertEquals(25, ctrl.cpuPercent(s))
        assertEquals(40, ctrl.memoryPercent(s))
        assertEquals("2026-06-03T00:00:01Z", ctrl.timestamp(s))
    }

    @Test
    fun `ClusterMetricsSampleTypeController exposes every field`() {
        val ctrl = ClusterMetricsSampleTypeController()
        val s = ClusterMetricsSample(totalCpuMillicores = 3_200L, totalMemoryBytes = 8_589_934_592L, cpuPercent = 30, memoryPercent = 55, nodeCount = 3, podCount = 42, timestamp = "2026-06-03T00:00:02Z")
        assertEquals(3_200L, ctrl.totalCpuMillicores(s))
        assertEquals(8_589_934_592L, ctrl.totalMemoryBytes(s))
        assertEquals(30, ctrl.cpuPercent(s))
        assertEquals(55, ctrl.memoryPercent(s))
        assertEquals(3, ctrl.nodeCount(s))
        assertEquals(42, ctrl.podCount(s))
        assertEquals("2026-06-03T00:00:02Z", ctrl.timestamp(s))
    }

    @Test
    fun `PodMetricsListItemTypeController exposes every field`() {
        val ctrl = PodMetricsListItemTypeController()
        val i = PodMetricsListItem(id = "uid-1", namespace = "default", name = "api-0", cpuMillicores = 120L, memoryBytes = 1_048_576L)
        assertEquals("uid-1", ctrl.id(i))
        assertEquals("default", ctrl.namespace(i))
        assertEquals("api-0", ctrl.name(i))
        assertEquals(120L, ctrl.cpuMillicores(i))
        assertEquals(1_048_576L, ctrl.memoryBytes(i))
    }

    @Test
    fun `PodsMetricsListSampleTypeController exposes items and timestamp`() {
        val ctrl = PodsMetricsListSampleTypeController()
        val item = PodMetricsListItem(id = "uid-1", namespace = "default", name = "api-0", cpuMillicores = 120L, memoryBytes = 1_048_576L)
        val s = PodsMetricsListSample(items = listOf(item), timestamp = "2026-06-03T00:00:03Z")
        assertEquals(listOf(item), ctrl.items(s))
        assertEquals("2026-06-03T00:00:03Z", ctrl.timestamp(s))
    }

    @Test
    fun `NodeMetricsListItemTypeController exposes every field`() {
        val ctrl = NodeMetricsListItemTypeController()
        val i = NodeMetricsListItem(name = "node-1", cpuMillicores = 500L, memoryBytes = 2_097_152L, cpuPercent = 25, memoryPercent = 40, status = "Ready", role = "worker")
        assertEquals("node-1", ctrl.name(i))
        assertEquals(500L, ctrl.cpuMillicores(i))
        assertEquals(2_097_152L, ctrl.memoryBytes(i))
        assertEquals(25, ctrl.cpuPercent(i))
        assertEquals(40, ctrl.memoryPercent(i))
        assertEquals("Ready", ctrl.status(i))
        assertEquals("worker", ctrl.role(i))
    }

    @Test
    fun `NodesMetricsListSampleTypeController exposes items and timestamp`() {
        val ctrl = NodesMetricsListSampleTypeController()
        val item = NodeMetricsListItem(name = "node-1", cpuMillicores = 500L, memoryBytes = 2_097_152L, cpuPercent = 25, memoryPercent = 40, status = "Ready", role = "worker")
        val s = NodesMetricsListSample(items = listOf(item), timestamp = "2026-06-03T00:00:04Z")
        assertEquals(listOf(item), ctrl.items(s))
        assertEquals("2026-06-03T00:00:04Z", ctrl.timestamp(s))
    }

    @Test
    fun `WorkloadMetricsListItemTypeController exposes every field`() {
        val ctrl = WorkloadMetricsListItemTypeController()
        val i = WorkloadMetricsListItem(id = "wl-uid-1", cpuCores = 1.5, memoryGiB = 2.25, restarts = 3)
        assertEquals("wl-uid-1", ctrl.id(i))
        assertEquals(1.5, ctrl.cpuCores(i))
        assertEquals(2.25, ctrl.memoryGiB(i))
        assertEquals(3, ctrl.restarts(i))
    }

    @Test
    fun `WorkloadsMetricsListSampleTypeController exposes items and timestamp`() {
        val ctrl = WorkloadsMetricsListSampleTypeController()
        val item = WorkloadMetricsListItem(id = "wl-uid-1", cpuCores = 1.5, memoryGiB = 2.25, restarts = 3)
        val s = WorkloadsMetricsListSample(items = listOf(item), timestamp = "2026-06-03T00:00:05Z")
        assertEquals(listOf(item), ctrl.items(s))
        assertEquals("2026-06-03T00:00:05Z", ctrl.timestamp(s))
    }

    @Test
    fun `WorkloadStatusListItemTypeController exposes id and status`() {
        val ctrl = WorkloadStatusListItemTypeController()
        val i = WorkloadStatusListItem(id = "wl-uid-1", status = WorkloadStatus.OK)
        assertEquals("wl-uid-1", ctrl.id(i))
        assertEquals(WorkloadStatus.OK, ctrl.status(i))
    }

    @Test
    fun `WorkloadsStatusListSampleTypeController exposes items and timestamp`() {
        val ctrl = WorkloadsStatusListSampleTypeController()
        val item = WorkloadStatusListItem(id = "wl-uid-1", status = WorkloadStatus.OK)
        val s = WorkloadsStatusListSample(items = listOf(item), timestamp = "2026-06-03T00:00:06Z")
        assertEquals(listOf(item), ctrl.items(s))
        assertEquals("2026-06-03T00:00:06Z", ctrl.timestamp(s))
    }
}

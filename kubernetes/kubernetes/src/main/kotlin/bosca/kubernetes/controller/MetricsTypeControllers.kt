package bosca.kubernetes.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
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

/**
 * Field projections for every metrics-stream payload type emitted by
 * the `k8s*MetricsStream` / `k8s*MetricsListStream` /
 * `k8sWorkloadsStatusListStream` subscriptions in
 * [KubernetesSubscriptionsController].
 *
 * Bosca's graphql wiring uses an explicit-only field registry —
 * properties on a type returned from a resolver are not auto-exposed.
 * Without a `@TypeController` per payload type, every field reads back
 * as null and graphql-java's non-null validator bubbles an error (e.g.
 * `PodsMetricsListSample.items` was declared `[PodMetricsListItem!]!`
 * but "wrongly returned a null value"). The list-wrapper samples need a
 * projection *and* so does each element type, because graphql-java
 * resolves `items` first and then descends into every list element's
 * fields. Mirrors [LogLineTypeController]; authorization is enforced one
 * level up at the subscription resolver.
 *
 * Grouping them in one file keeps the `controller/` directory navigable;
 * each class still owns its own `@TypeController` so KSP wires per-field
 * dispatchers independently.
 */

@TypeController(type = "PodMetricsSample")
class PodMetricsSampleTypeController : GraphQLController<PodMetricsSample> {
    @Field fun namespace(s: PodMetricsSample): String = s.namespace
    @Field fun pod(s: PodMetricsSample): String = s.pod
    @Field fun cpuMillicores(s: PodMetricsSample): Long = s.cpuMillicores
    @Field fun memoryBytes(s: PodMetricsSample): Long = s.memoryBytes
    @Field fun timestamp(s: PodMetricsSample): String = s.timestamp
}

@TypeController(type = "NodeMetricsSample")
class NodeMetricsSampleTypeController : GraphQLController<NodeMetricsSample> {
    @Field fun node(s: NodeMetricsSample): String = s.node
    @Field fun cpuMillicores(s: NodeMetricsSample): Long = s.cpuMillicores
    @Field fun memoryBytes(s: NodeMetricsSample): Long = s.memoryBytes
    @Field fun cpuPercent(s: NodeMetricsSample): Int = s.cpuPercent
    @Field fun memoryPercent(s: NodeMetricsSample): Int = s.memoryPercent
    @Field fun timestamp(s: NodeMetricsSample): String = s.timestamp
}

@TypeController(type = "ClusterMetricsSample")
class ClusterMetricsSampleTypeController : GraphQLController<ClusterMetricsSample> {
    @Field fun totalCpuMillicores(s: ClusterMetricsSample): Long = s.totalCpuMillicores
    @Field fun totalMemoryBytes(s: ClusterMetricsSample): Long = s.totalMemoryBytes
    @Field fun cpuPercent(s: ClusterMetricsSample): Int = s.cpuPercent
    @Field fun memoryPercent(s: ClusterMetricsSample): Int = s.memoryPercent
    @Field fun nodeCount(s: ClusterMetricsSample): Int = s.nodeCount
    @Field fun podCount(s: ClusterMetricsSample): Int = s.podCount
    @Field fun timestamp(s: ClusterMetricsSample): String = s.timestamp
}

@TypeController(type = "PodMetricsListItem")
class PodMetricsListItemTypeController : GraphQLController<PodMetricsListItem> {
    @Field fun id(i: PodMetricsListItem): String = i.id
    @Field fun namespace(i: PodMetricsListItem): String = i.namespace
    @Field fun name(i: PodMetricsListItem): String = i.name
    @Field fun cpuMillicores(i: PodMetricsListItem): Long = i.cpuMillicores
    @Field fun memoryBytes(i: PodMetricsListItem): Long = i.memoryBytes
}

@TypeController(type = "PodsMetricsListSample")
class PodsMetricsListSampleTypeController : GraphQLController<PodsMetricsListSample> {
    @Field fun items(s: PodsMetricsListSample): List<PodMetricsListItem> = s.items
    @Field fun timestamp(s: PodsMetricsListSample): String = s.timestamp
}

@TypeController(type = "NodeMetricsListItem")
class NodeMetricsListItemTypeController : GraphQLController<NodeMetricsListItem> {
    @Field fun name(i: NodeMetricsListItem): String = i.name
    @Field fun cpuMillicores(i: NodeMetricsListItem): Long = i.cpuMillicores
    @Field fun memoryBytes(i: NodeMetricsListItem): Long = i.memoryBytes
    @Field fun cpuPercent(i: NodeMetricsListItem): Int = i.cpuPercent
    @Field fun memoryPercent(i: NodeMetricsListItem): Int = i.memoryPercent
    @Field fun status(i: NodeMetricsListItem): String = i.status
    @Field fun role(i: NodeMetricsListItem): String = i.role
}

@TypeController(type = "NodesMetricsListSample")
class NodesMetricsListSampleTypeController : GraphQLController<NodesMetricsListSample> {
    @Field fun items(s: NodesMetricsListSample): List<NodeMetricsListItem> = s.items
    @Field fun timestamp(s: NodesMetricsListSample): String = s.timestamp
}

@TypeController(type = "WorkloadMetricsListItem")
class WorkloadMetricsListItemTypeController : GraphQLController<WorkloadMetricsListItem> {
    @Field fun id(i: WorkloadMetricsListItem): String = i.id
    @Field fun cpuCores(i: WorkloadMetricsListItem): Double = i.cpuCores
    @Field fun memoryGiB(i: WorkloadMetricsListItem): Double = i.memoryGiB
    @Field fun restarts(i: WorkloadMetricsListItem): Int = i.restarts
}

@TypeController(type = "WorkloadsMetricsListSample")
class WorkloadsMetricsListSampleTypeController : GraphQLController<WorkloadsMetricsListSample> {
    @Field fun items(s: WorkloadsMetricsListSample): List<WorkloadMetricsListItem> = s.items
    @Field fun timestamp(s: WorkloadsMetricsListSample): String = s.timestamp
}

@TypeController(type = "WorkloadStatusListItem")
class WorkloadStatusListItemTypeController : GraphQLController<WorkloadStatusListItem> {
    @Field fun id(i: WorkloadStatusListItem): String = i.id
    @Field fun status(i: WorkloadStatusListItem): WorkloadStatus = i.status
}

@TypeController(type = "WorkloadsStatusListSample")
class WorkloadsStatusListSampleTypeController : GraphQLController<WorkloadsStatusListSample> {
    @Field fun items(s: WorkloadsStatusListSample): List<WorkloadStatusListItem> = s.items
    @Field fun timestamp(s: WorkloadsStatusListSample): String = s.timestamp
}

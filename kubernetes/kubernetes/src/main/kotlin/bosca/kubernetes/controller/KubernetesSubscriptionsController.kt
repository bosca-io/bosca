package bosca.kubernetes.controller

import bosca.db.withConnectionManager
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.kubernetes.model.ClusterMetricsSample
import bosca.kubernetes.model.K8sEvent
import bosca.kubernetes.model.K8sHelmRelease
import bosca.kubernetes.model.LogLine
import bosca.kubernetes.model.NodeMetricsSample
import bosca.kubernetes.model.NodesMetricsListSample
import bosca.kubernetes.model.PodMetricsSample
import bosca.kubernetes.model.PodsMetricsListSample
import bosca.kubernetes.model.ResourceChangeEvent
import bosca.kubernetes.model.Workload
import bosca.kubernetes.model.WorkloadKind
import bosca.kubernetes.model.WorkloadsMetricsListSample
import bosca.kubernetes.model.WorkloadsStatusListSample
import bosca.kubernetes.service.ClusterService
import bosca.kubernetes.service.KubernetesControllerClient
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Subscription resolvers under `Subscription`. The kubernetes streams
 * live flat (`k8sPodLogs`, `k8sEvents`, …) rather than nested under
 * `kubernetes` because graphql-java's `SubscriptionExecutionStrategy`
 * requires the root subscription field's fetcher to return a
 * Publisher/Flow — nesting an intermediate object root breaks that.
 *
 * Every resolver verifies admin group membership before the upstream
 * stream is opened — anonymous subscribers never reach the controller.
 * Event watches, workload status, helm release status, and metrics
 * flow streams follow the same shape as [k8sPodLogs] once the upstream
 * routes land.
 */
@TypeController(type = "Subscription")
class KubernetesSubscriptionsController(
    private val clusters: ClusterService,
    private val controller: KubernetesControllerClient,
    private val groups: GroupEvaluator,
) : GraphQLController<Any> {

    /**
     * Streams container log lines for a specific pod / container.
     *
     * Admin gating happens once at subscribe time — admin reach is
     * checked on the resolver thread before the upstream HTTP stream
     * is opened. The downstream NDJSON connection then re-validates
     * the minted JWT on every request so a credential revocation
     * between subscribe-time and the *next* HTTP read closes the
     * stream from the controller side.
     *
     * The flow completes naturally when the upstream HTTP body
     * closes (either the kubelet has nothing more to send for
     * `follow=false`, or the controller's 5-minute streaming cap
     * fires for `follow=true`). The studio's `useK8sPodLogs`
     * composable detects completion and re-subscribes.
     */
    @Field
    fun k8sPodLogs(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String,
        pod: String,
        container: String? = null,
        tailLines: Int? = null,
        follow: Boolean? = null,
    ): Flow<LogLine> = gatedStream(authentication, cluster) {
        emitAll(
            controller.streamPodLogs(
                authentication = authentication,
                clusterId = cluster,
                namespace = namespace,
                pod = pod,
                container = container,
                tailLines = tailLines,
                follow = follow,
            ),
        )
    }

    /**
     * Streams new events as the controller observes them. Optional
     * namespace narrows the watch. Same admin-only model, same
     * subscribe-time gating as [k8sPodLogs].
     */
    @Field
    fun k8sEvents(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
    ): Flow<K8sEvent> = gatedStream(authentication, cluster) {
        emitAll(
            controller.streamEvents(
                authentication = authentication,
                clusterId = cluster,
                namespace = namespace,
            ),
        )
    }

    /**
     * Streams status changes for a single workload. The studio's
     * workload drawer subscribes when opened and unsubscribes on
     * close — the underlying fabric8 watch is similarly lifecycled.
     */
    @Field
    fun k8sWorkloadStatus(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String,
        kind: WorkloadKind,
        name: String,
    ): Flow<Workload> = gatedStream(authentication, cluster) {
        emitAll(
            controller.streamWorkloadStatus(
                authentication = authentication,
                clusterId = cluster,
                namespace = namespace,
                kind = kind,
                name = name,
            ),
        )
    }

    /**
     * Streams helm release status changes for a single release —
     * mirrors [k8sWorkloadStatus] but watches the underlying
     * `owner=helm` Secrets. The studio's install / upgrade wizard
     * subscribes on the mutation call and drops the subscription once
     * the release reaches a terminal status.
     */
    @Field
    fun k8sHelmReleaseStatus(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String,
        name: String,
    ): Flow<K8sHelmRelease> = gatedStream(authentication, cluster) {
        emitAll(
            controller.streamHelmReleaseStatus(
                authentication = authentication,
                clusterId = cluster,
                namespace = namespace,
                name = name,
            ),
        )
    }

    /**
     * Streams periodic pod metric samples. Pattern matches every
     * other kubernetes subscription — admin gating at subscribe,
     * watchdog re-check during the stream, downstream NDJSON for the
     * controller-side polling loop.
     */
    @Field
    fun k8sPodMetricsStream(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String,
        pod: String,
        intervalSec: Int? = null,
    ): Flow<PodMetricsSample> = gatedStream(authentication, cluster) {
        emitAll(
            controller.streamPodMetrics(
                authentication = authentication,
                clusterId = cluster,
                namespace = namespace,
                pod = pod,
                intervalSec = intervalSec,
            ),
        )
    }

    /** Streams periodic node metric samples for a single node. */
    @Field
    fun k8sNodeMetricsStream(
        authentication: AuthenticationContext,
        cluster: UUID,
        node: String,
        intervalSec: Int? = null,
    ): Flow<NodeMetricsSample> = gatedStream(authentication, cluster) {
        emitAll(
            controller.streamNodeMetrics(
                authentication = authentication,
                clusterId = cluster,
                node = node,
                intervalSec = intervalSec,
            ),
        )
    }

    /** Streams periodic cluster-wide aggregate metric samples. */
    @Field
    fun k8sClusterMetricsStream(
        authentication: AuthenticationContext,
        cluster: UUID,
        intervalSec: Int? = null,
    ): Flow<ClusterMetricsSample> = gatedStream(authentication, cluster) {
        emitAll(
            controller.streamClusterMetrics(
                authentication = authentication,
                clusterId = cluster,
                intervalSec = intervalSec,
            ),
        )
    }

    /**
     * Streams a per-pod cpu/memory snapshot every tick for an entire
     * cluster (or one namespace). The studio's pods / workload-detail
     * pages merge each tick into existing rows by pod UID; one stream
     * powers many rows so the per-page socket count stays at one.
     */
    @Field
    fun k8sPodsMetricsListStream(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
        intervalSec: Int? = null,
    ): Flow<PodsMetricsListSample> = gatedStream(authentication, cluster) {
        emitAll(
            controller.streamPodsListMetrics(
                authentication = authentication,
                clusterId = cluster,
                namespace = namespace,
                intervalSec = intervalSec,
            ),
        )
    }

    /** Streams a per-node cpu/memory snapshot every tick. */
    @Field
    fun k8sNodesMetricsListStream(
        authentication: AuthenticationContext,
        cluster: UUID,
        intervalSec: Int? = null,
    ): Flow<NodesMetricsListSample> = gatedStream(authentication, cluster) {
        emitAll(
            controller.streamNodesListMetrics(
                authentication = authentication,
                clusterId = cluster,
                intervalSec = intervalSec,
            ),
        )
    }

    /**
     * Streams a per-workload cpu/memory aggregate snapshot every tick.
     * Aggregation walks ReplicaSet → Deployment and Job → CronJob so a
     * Deployment row sums every pod across all of its ReplicaSets —
     * same semantics as the on-demand `workloads` query.
     */
    @Field
    fun k8sWorkloadsMetricsListStream(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
        intervalSec: Int? = null,
    ): Flow<WorkloadsMetricsListSample> = gatedStream(authentication, cluster) {
        emitAll(
            controller.streamWorkloadsListMetrics(
                authentication = authentication,
                clusterId = cluster,
                namespace = namespace,
                intervalSec = intervalSec,
            ),
        )
    }

    /**
     * Streams a per-workload status snapshot every tick. Built from the
     * workload objects themselves so scaled-to-zero / failed workloads
     * still surface — the overview's status badges track this without a
     * per-workload watch fan-out.
     */
    @Field
    fun k8sWorkloadsStatusListStream(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
        intervalSec: Int? = null,
    ): Flow<WorkloadsStatusListSample> = gatedStream(authentication, cluster) {
        emitAll(
            controller.streamWorkloadsListStatus(
                authentication = authentication,
                clusterId = cluster,
                namespace = namespace,
                intervalSec = intervalSec,
            ),
        )
    }

    /**
     * Streams a merged log tail across every pod that belongs to a
     * workload. The studio's workload-detail Logs tab consumes this
     * with one socket per page — no per-replica reconnect dance, no
     * fan-out on the studio side.
     */
    @Field
    fun k8sWorkloadLogs(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String,
        kind: WorkloadKind,
        name: String,
        tailLines: Int? = null,
        follow: Boolean? = null,
    ): Flow<LogLine> = gatedStream(authentication, cluster) {
        emitAll(
            controller.streamWorkloadLogs(
                authentication = authentication,
                clusterId = cluster,
                namespace = namespace,
                kind = kind,
                name = name,
                tailLines = tailLines,
                follow = follow,
            ),
        )
    }

    /**
     * Streams metadata-only change notifications for a set of resource
     * kinds. Every studio list view subscribes with the kinds it
     * renders and re-runs its list query when a tick arrives — this is
     * the mechanism that keeps the non-metrics views (networking,
     * config, storage, RBAC, certs, CNPG, helm releases, …) realtime
     * without a per-kind subscription fan-out.
     */
    @Field
    fun k8sResourcesWatch(
        authentication: AuthenticationContext,
        cluster: UUID,
        kinds: List<String>,
        namespace: String? = null,
    ): Flow<ResourceChangeEvent> = gatedStream(authentication, cluster) {
        emitAll(
            controller.streamResourceChanges(
                authentication = authentication,
                clusterId = cluster,
                kinds = kinds,
                namespace = namespace,
            ),
        )
    }

    /**
     * Wraps a subscription [body] with two checks every kubernetes
     * stream needs:
     *
     *  1. **Subscribe-time gate.** Admin group membership and target
     *     cluster existence are verified before the upstream is
     *     opened, so anonymous or non-admin callers never reach the
     *     controller.
     *  2. **Mid-stream watchdog.** A coroutine launched alongside the
     *     stream re-checks admin membership every
     *     [AUTH_RECHECK_INTERVAL]. If a caller is removed from the
     *     `administrators` group while their pod-logs / events /
     *     workload-status / helm-status subscription is open, the
     *     watchdog throws, the surrounding `coroutineScope` cancels
     *     the upstream emit, and the WebSocket closes from the server
     *     side with a security error.
     *
     *  Without the watchdog, the only "natural" close was the
     *  controller's 5-minute streaming cap — a 5-minute window in
     *  which a just-revoked admin could keep receiving sensitive
     *  data. The watchdog cuts the upper bound to
     *  [AUTH_RECHECK_INTERVAL].
     */
    private fun <T> gatedStream(
        authentication: AuthenticationContext,
        cluster: UUID,
        body: suspend FlowCollector<T>.() -> Unit,
    ): Flow<T> = flow {
        withConnectionManager {
            groups.verifyHasAdminGroup(authentication)
            clusters.getById(cluster) ?: error("Cluster $cluster not found")
        }
        coroutineScope {
            val watchdog = launch {
                while (isActive) {
                    delay(AUTH_RECHECK_INTERVAL)
                    val stillAdmin = withConnectionManager {
                        groups.hasAdminGroup(authentication)
                    }
                    check(stillAdmin) {
                        "admin group revoked mid-stream; closing subscription"
                    }
                }
            }
            // body() is the upstream emitAll; when the upstream flow
            // completes naturally (controller cap fires, kubelet
            // closes, etc.) we have to cancel the watchdog explicitly
            // — otherwise coroutineScope would wait forever on its
            // infinite delay loop. A revocation throw from the
            // watchdog cancels the whole scope, which interrupts
            // emitAll the same way.
            try {
                body()
            } finally {
                watchdog.cancel()
            }
        }
    }

    companion object {
        /**
         * How often each subscription re-validates that the caller is
         * still in the `administrators` group. 60 s is the sweet spot:
         * a freshly-revoked admin loses access within a minute, the
         * DB load is one quick group lookup per open stream per
         * minute, and the cadence does not interact pathologically
         * with token-refresh windows on the studio side.
         */
        internal val AUTH_RECHECK_INTERVAL: Duration = 60.seconds
    }
}

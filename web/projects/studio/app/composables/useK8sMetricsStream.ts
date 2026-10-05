import { computed, onMounted, onUnmounted, ref, toValue, watch, type MaybeRefOrGetter } from 'vue'

/**
 * Live CPU + memory samples streamed from `kubernetes-controller` via
 * the `k8sPodMetricsStream` / `k8sNodeMetricsStream` /
 * `k8sClusterMetricsStream` GraphQL subscriptions.
 *
 * Each composable owns its own WebSocket lifecycle (mirroring
 * `useK8sPodLogs`) so it can:
 *
 *  * Re-subscribe automatically when an input ref changes (cluster /
 *    namespace / pod / node).
 *  * Carry a small ring buffer of recent samples for sparkline
 *    rendering — the latest is the "current" reading, the rest power
 *    a trailing 60-tick chart on detail pages.
 *  * Tear the socket down on unmount so navigating away from a
 *    detail page doesn't leak a watch.
 *
 * Auth follows the same pattern as the log stream: the JWT is sent
 * via the `connection_init` payload so the resolver can gate on the
 * admin group before opening the upstream NDJSON.
 */

const BUFFER_SIZE = 60

export interface PodMetricsSample {
  namespace: string
  pod: string
  cpuMillicores: number
  memoryBytes: number
  timestamp: string
}

export interface NodeMetricsSample {
  node: string
  cpuMillicores: number
  memoryBytes: number
  cpuPercent: number
  memoryPercent: number
  timestamp: string
}

export interface ClusterMetricsSample {
  totalCpuMillicores: number
  totalMemoryBytes: number
  cpuPercent: number
  memoryPercent: number
  nodeCount: number
  podCount: number
  timestamp: string
}

export interface PodMetricsListItem {
  id: string
  namespace: string
  name: string
  cpuMillicores: number
  memoryBytes: number
}

export interface NodeMetricsListItem {
  name: string
  cpuMillicores: number
  memoryBytes: number
  cpuPercent: number
  memoryPercent: number
  /** Live readiness text (`Ready` / `NotReady` / a pressure type / `Unknown`). */
  status: string
  /** `control-plane` / `worker`. */
  role: string
}

/** One workload's live status snapshot, keyed by workload id. */
export interface WorkloadStatusListItem {
  id: string
  /** Backend `WorkloadStatus` enum: `OK` / `PENDING` / `WARN` / `ERROR`. */
  status: 'OK' | 'PENDING' | 'WARN' | 'ERROR'
}

/** One live kubernetes event as it is observed by the controller. */
export interface K8sEventStreamItem {
  id: string
  level: string
  when: string
  timestamp: string
  namespace: string
  involvedObject: string
  message: string
  reason: string
}

export interface WorkloadMetricsListItem {
  id: string
  cpuCores: number
  memoryGiB: number
  restarts: number
}

export type K8sStreamStatus = 'idle' | 'connecting' | 'open' | 'closed' | 'error'

type Status = K8sStreamStatus

export interface StreamHandle<T> {
  latest: ReturnType<typeof ref<T | null>>
  samples: ReturnType<typeof ref<T[]>>
  status: ReturnType<typeof ref<Status>>
}

export interface SubscriptionConfig<T> {
  query: string
  variables: () => Record<string, unknown>
  shouldOpen: () => boolean
  pickSample: (data: Record<string, unknown>) => T | null
}

/**
 * Opens a graphql-transport-ws subscription and pumps samples into
 * [handle]. Shared by every kubernetes stream composable (metrics,
 * events, and the resource-change watch in `useK8sResourceWatch`) so
 * the socket lifecycle / auth / reconnect logic lives in one place.
 */
export function bindSubscription<T>(handle: StreamHandle<T>, cfg: SubscriptionConfig<T>) {
  let ws: WebSocket | undefined
  let reconnectTimer: ReturnType<typeof setTimeout> | undefined

  async function open() {
    if (!cfg.shouldOpen()) {
      handle.status.value = 'idle'
      return
    }
    handle.status.value = 'connecting'

    const config = useRuntimeConfig()
    const wsBase = (config.public as { wsUrl?: string }).wsUrl
      || `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}`
    const url = `${wsBase}/graphqlws`

    let connectionParams: Record<string, unknown> = {}
    try {
      const { $auth } = useNuxtApp()
      if ($auth) {
        const headers = await $auth.getAuthHeaders()
        const token = headers?.['Authorization']?.replace('Bearer ', '')
        if (token) connectionParams = { authToken: token }
      }
    } catch { /* auth not ready */ }

    ws = new WebSocket(url, 'graphql-transport-ws')

    ws.onopen = () => {
      ws!.send(JSON.stringify({ type: 'connection_init', payload: connectionParams }))
    }

    ws.onmessage = (event) => {
      const msg = JSON.parse(event.data)
      switch (msg.type) {
        case 'connection_ack':
          ws!.send(JSON.stringify({
            id: '1',
            type: 'subscribe',
            payload: { query: cfg.query, variables: cfg.variables() },
          }))
          handle.status.value = 'open'
          break
        case 'ping':
          ws!.send(JSON.stringify({ type: 'pong' }))
          break
        case 'next': {
          const sample = cfg.pickSample(msg.payload?.data ?? {})
          if (sample) {
            handle.latest.value = sample
            const buf = handle.samples.value as T[]
            buf.push(sample)
            if (buf.length > BUFFER_SIZE) buf.splice(0, buf.length - BUFFER_SIZE)
          }
          break
        }
        case 'complete':
          handle.status.value = 'closed'
          // Server-side teardown (e.g. controller restart, route 5xx).
          // Schedule a single reconnect after a short backoff —
          // simpler than a full exponential and matches what
          // useK8sPodLogs does on its 5-min cap.
          scheduleReconnect()
          break
        case 'error':
          handle.status.value = 'error'
          console.error('k8s metrics subscription error', msg.payload)
          scheduleReconnect()
          break
      }
    }

    ws.onerror = () => {
      handle.status.value = 'error'
    }
    ws.onclose = () => {
      if (handle.status.value !== 'closed') handle.status.value = 'closed'
    }
  }

  function scheduleReconnect() {
    if (reconnectTimer) return
    reconnectTimer = setTimeout(() => {
      reconnectTimer = undefined
      teardown()
      open()
    }, 2000)
  }

  function teardown() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = undefined
    }
    if (ws && ws.readyState <= WebSocket.OPEN) {
      try { ws.send(JSON.stringify({ id: '1', type: 'complete' })) } catch { /* socket already closed */ }
      ws.close()
    }
    ws = undefined
  }

  return { open, teardown }
}

/** Live stream of CPU + memory samples for a single pod. */
export function useK8sPodMetricsStream(opts: {
  cluster: MaybeRefOrGetter<string | null | undefined>
  namespace: MaybeRefOrGetter<string | null | undefined>
  pod: MaybeRefOrGetter<string | null | undefined>
  intervalSec?: MaybeRefOrGetter<number | undefined>
}) {
  const handle: StreamHandle<PodMetricsSample> = {
    latest: ref<PodMetricsSample | null>(null),
    samples: ref<PodMetricsSample[]>([]),
    status: ref<Status>('idle'),
  }

  const query = `
    subscription K8sPodMetricsStream($cluster: UUID!, $namespace: String!, $pod: String!, $intervalSec: Int) {
      k8sPodMetricsStream(cluster: $cluster, namespace: $namespace, pod: $pod, intervalSec: $intervalSec) {
        namespace pod cpuMillicores memoryBytes timestamp
      }
    }
  `

  const { open, teardown } = bindSubscription(handle, {
    query,
    variables: () => ({
      cluster: toValue(opts.cluster),
      namespace: toValue(opts.namespace),
      pod: toValue(opts.pod),
      intervalSec: toValue(opts.intervalSec) ?? null,
    }),
    shouldOpen: () => !!(toValue(opts.cluster) && toValue(opts.namespace) && toValue(opts.pod)),
    pickSample: (data) => (data?.k8sPodMetricsStream as PodMetricsSample | undefined) ?? null,
  })

  onMounted(open)
  onUnmounted(teardown)
  watch(
    () => [toValue(opts.cluster), toValue(opts.namespace), toValue(opts.pod), toValue(opts.intervalSec)],
    () => { teardown(); handle.samples.value = []; open() },
  )

  return {
    latest: handle.latest,
    samples: handle.samples,
    status: computed(() => handle.status.value),
  }
}

/** Live stream of CPU + memory samples for a single node. */
export function useK8sNodeMetricsStream(opts: {
  cluster: MaybeRefOrGetter<string | null | undefined>
  node: MaybeRefOrGetter<string | null | undefined>
  intervalSec?: MaybeRefOrGetter<number | undefined>
}) {
  const handle: StreamHandle<NodeMetricsSample> = {
    latest: ref<NodeMetricsSample | null>(null),
    samples: ref<NodeMetricsSample[]>([]),
    status: ref<Status>('idle'),
  }

  const query = `
    subscription K8sNodeMetricsStream($cluster: UUID!, $node: String!, $intervalSec: Int) {
      k8sNodeMetricsStream(cluster: $cluster, node: $node, intervalSec: $intervalSec) {
        node cpuMillicores memoryBytes cpuPercent memoryPercent timestamp
      }
    }
  `

  const { open, teardown } = bindSubscription(handle, {
    query,
    variables: () => ({
      cluster: toValue(opts.cluster),
      node: toValue(opts.node),
      intervalSec: toValue(opts.intervalSec) ?? null,
    }),
    shouldOpen: () => !!(toValue(opts.cluster) && toValue(opts.node)),
    pickSample: (data) => (data?.k8sNodeMetricsStream as NodeMetricsSample | undefined) ?? null,
  })

  onMounted(open)
  onUnmounted(teardown)
  watch(
    () => [toValue(opts.cluster), toValue(opts.node), toValue(opts.intervalSec)],
    () => { teardown(); handle.samples.value = []; open() },
  )

  return {
    latest: handle.latest,
    samples: handle.samples,
    status: computed(() => handle.status.value),
  }
}

/**
 * Live per-pod CPU + memory snapshot keyed by pod UID. The composable
 * exposes a `byId` map so list pages can render `{{ byId[row.id]?.cpu
 * ?? row.cpu }}m` without N watchers — every tick replaces the map
 * atomically.
 *
 * Pass `namespace` to narrow the stream to a single namespace
 * (matches the studio's namespace filter); omit it for cluster-wide.
 */
export function useK8sPodsListMetricsStream(opts: {
  cluster: MaybeRefOrGetter<string | null | undefined>
  namespace?: MaybeRefOrGetter<string | null | undefined>
  intervalSec?: MaybeRefOrGetter<number | undefined>
}) {
  // The studio's K8sPod type carries namespace + name (not the UID
  // emitted by the controller stream), so we expose a
  // `${namespace}/${name}` lookup — that pair is unique across the
  // cluster and lets list rows merge live values without threading
  // the pod UID through the existing query payload.
  const byKey = ref<Record<string, PodMetricsListItem>>({})
  const status = ref<Status>('idle')

  const query = `
    subscription K8sPodsMetricsListStream($cluster: UUID!, $namespace: String, $intervalSec: Int) {
      k8sPodsMetricsListStream(cluster: $cluster, namespace: $namespace, intervalSec: $intervalSec) {
        items { id namespace name cpuMillicores memoryBytes }
        timestamp
      }
    }
  `

  const handle = {
    latest: ref<{ items: PodMetricsListItem[]; timestamp: string } | null>(null),
    samples: ref<{ items: PodMetricsListItem[]; timestamp: string }[]>([]),
    status,
  } as StreamHandle<{ items: PodMetricsListItem[]; timestamp: string }>

  const { open, teardown } = bindSubscription(handle, {
    query,
    variables: () => ({
      cluster: toValue(opts.cluster),
      namespace: toValue(opts.namespace) ?? null,
      intervalSec: toValue(opts.intervalSec) ?? null,
    }),
    shouldOpen: () => !!toValue(opts.cluster),
    pickSample: (data) => {
      const sample = data?.k8sPodsMetricsListStream as { items: PodMetricsListItem[]; timestamp: string } | undefined
      if (!sample) return null
      // Snapshot wire format — replace the lookup wholesale rather
      // than merging, so pods that disappeared from the cluster also
      // disappear from the studio's overlay.
      const next: Record<string, PodMetricsListItem> = {}
      for (const item of sample.items) next[`${item.namespace}/${item.name}`] = item
      byKey.value = next
      return sample
    },
  })

  onMounted(open)
  onUnmounted(teardown)
  watch(
    () => [toValue(opts.cluster), toValue(opts.namespace), toValue(opts.intervalSec)],
    () => { teardown(); byKey.value = {}; open() },
  )

  return { byKey, status: computed(() => handle.status.value) }
}

/** Live per-node CPU + memory snapshot keyed by node name. */
export function useK8sNodesListMetricsStream(opts: {
  cluster: MaybeRefOrGetter<string | null | undefined>
  intervalSec?: MaybeRefOrGetter<number | undefined>
}) {
  const byName = ref<Record<string, NodeMetricsListItem>>({})
  const status = ref<Status>('idle')

  const query = `
    subscription K8sNodesMetricsListStream($cluster: UUID!, $intervalSec: Int) {
      k8sNodesMetricsListStream(cluster: $cluster, intervalSec: $intervalSec) {
        items { name cpuMillicores memoryBytes cpuPercent memoryPercent status role }
        timestamp
      }
    }
  `

  const handle = {
    latest: ref<{ items: NodeMetricsListItem[]; timestamp: string } | null>(null),
    samples: ref<{ items: NodeMetricsListItem[]; timestamp: string }[]>([]),
    status,
  } as StreamHandle<{ items: NodeMetricsListItem[]; timestamp: string }>

  const { open, teardown } = bindSubscription(handle, {
    query,
    variables: () => ({
      cluster: toValue(opts.cluster),
      intervalSec: toValue(opts.intervalSec) ?? null,
    }),
    shouldOpen: () => !!toValue(opts.cluster),
    pickSample: (data) => {
      const sample = data?.k8sNodesMetricsListStream as { items: NodeMetricsListItem[]; timestamp: string } | undefined
      if (!sample) return null
      const next: Record<string, NodeMetricsListItem> = {}
      for (const item of sample.items) next[item.name] = item
      byName.value = next
      return sample
    },
  })

  onMounted(open)
  onUnmounted(teardown)
  watch(
    () => [toValue(opts.cluster), toValue(opts.intervalSec)],
    () => { teardown(); byName.value = {}; open() },
  )

  return { byName, status: computed(() => handle.status.value) }
}

/** Live per-workload CPU + memory aggregate snapshot keyed by workload UID. */
export function useK8sWorkloadsListMetricsStream(opts: {
  cluster: MaybeRefOrGetter<string | null | undefined>
  namespace?: MaybeRefOrGetter<string | null | undefined>
  intervalSec?: MaybeRefOrGetter<number | undefined>
}) {
  const byId = ref<Record<string, WorkloadMetricsListItem>>({})
  const status = ref<Status>('idle')

  const query = `
    subscription K8sWorkloadsMetricsListStream($cluster: UUID!, $namespace: String, $intervalSec: Int) {
      k8sWorkloadsMetricsListStream(cluster: $cluster, namespace: $namespace, intervalSec: $intervalSec) {
        items { id cpuCores memoryGiB restarts }
        timestamp
      }
    }
  `

  const handle = {
    latest: ref<{ items: WorkloadMetricsListItem[]; timestamp: string } | null>(null),
    samples: ref<{ items: WorkloadMetricsListItem[]; timestamp: string }[]>([]),
    status,
  } as StreamHandle<{ items: WorkloadMetricsListItem[]; timestamp: string }>

  const { open, teardown } = bindSubscription(handle, {
    query,
    variables: () => ({
      cluster: toValue(opts.cluster),
      namespace: toValue(opts.namespace) ?? null,
      intervalSec: toValue(opts.intervalSec) ?? null,
    }),
    shouldOpen: () => !!toValue(opts.cluster),
    pickSample: (data) => {
      const sample = data?.k8sWorkloadsMetricsListStream as { items: WorkloadMetricsListItem[]; timestamp: string } | undefined
      if (!sample) return null
      const next: Record<string, WorkloadMetricsListItem> = {}
      for (const item of sample.items) next[item.id] = item
      byId.value = next
      return sample
    },
  })

  onMounted(open)
  onUnmounted(teardown)
  watch(
    () => [toValue(opts.cluster), toValue(opts.namespace), toValue(opts.intervalSec)],
    () => { teardown(); byId.value = {}; open() },
  )

  return { byId, status: computed(() => handle.status.value) }
}

/** Live stream of cluster-wide aggregate CPU + memory samples. */
export function useK8sClusterMetricsStream(opts: {
  cluster: MaybeRefOrGetter<string | null | undefined>
  intervalSec?: MaybeRefOrGetter<number | undefined>
}) {
  const handle: StreamHandle<ClusterMetricsSample> = {
    latest: ref<ClusterMetricsSample | null>(null),
    samples: ref<ClusterMetricsSample[]>([]),
    status: ref<Status>('idle'),
  }

  const query = `
    subscription K8sClusterMetricsStream($cluster: UUID!, $intervalSec: Int) {
      k8sClusterMetricsStream(cluster: $cluster, intervalSec: $intervalSec) {
        totalCpuMillicores totalMemoryBytes cpuPercent memoryPercent nodeCount podCount timestamp
      }
    }
  `

  const { open, teardown } = bindSubscription(handle, {
    query,
    variables: () => ({
      cluster: toValue(opts.cluster),
      intervalSec: toValue(opts.intervalSec) ?? null,
    }),
    shouldOpen: () => !!toValue(opts.cluster),
    pickSample: (data) => (data?.k8sClusterMetricsStream as ClusterMetricsSample | undefined) ?? null,
  })

  onMounted(open)
  onUnmounted(teardown)
  watch(
    () => [toValue(opts.cluster), toValue(opts.intervalSec)],
    () => { teardown(); handle.samples.value = []; open() },
  )

  return {
    latest: handle.latest,
    samples: handle.samples,
    status: computed(() => handle.status.value),
  }
}

/**
 * Live per-workload status snapshot keyed by workload id. Mirrors
 * [useK8sWorkloadsListMetricsStream] but carries the status badge
 * (built server-side from the workload objects, so scaled-to-zero /
 * failed workloads still appear) rather than cpu/memory. Lets list and
 * overview pages keep status badges live by merging `byId[row.id]?.status`
 * without a per-workload watch.
 */
export function useK8sWorkloadsStatusListStream(opts: {
  cluster: MaybeRefOrGetter<string | null | undefined>
  namespace?: MaybeRefOrGetter<string | null | undefined>
  intervalSec?: MaybeRefOrGetter<number | undefined>
}) {
  const byId = ref<Record<string, WorkloadStatusListItem>>({})
  const status = ref<Status>('idle')

  const query = `
    subscription K8sWorkloadsStatusListStream($cluster: UUID!, $namespace: String, $intervalSec: Int) {
      k8sWorkloadsStatusListStream(cluster: $cluster, namespace: $namespace, intervalSec: $intervalSec) {
        items { id status }
        timestamp
      }
    }
  `

  const handle = {
    latest: ref<{ items: WorkloadStatusListItem[]; timestamp: string } | null>(null),
    samples: ref<{ items: WorkloadStatusListItem[]; timestamp: string }[]>([]),
    status,
  } as StreamHandle<{ items: WorkloadStatusListItem[]; timestamp: string }>

  const { open, teardown } = bindSubscription(handle, {
    query,
    variables: () => ({
      cluster: toValue(opts.cluster),
      namespace: toValue(opts.namespace) ?? null,
      intervalSec: toValue(opts.intervalSec) ?? null,
    }),
    shouldOpen: () => !!toValue(opts.cluster),
    pickSample: (data) => {
      const sample = data?.k8sWorkloadsStatusListStream as { items: WorkloadStatusListItem[]; timestamp: string } | undefined
      if (!sample) return null
      // Snapshot wire format — replace wholesale so workloads that
      // disappeared also disappear from the overlay.
      const next: Record<string, WorkloadStatusListItem> = {}
      for (const item of sample.items) next[item.id] = item
      byId.value = next
      return sample
    },
  })

  onMounted(open)
  onUnmounted(teardown)
  watch(
    () => [toValue(opts.cluster), toValue(opts.namespace), toValue(opts.intervalSec)],
    () => { teardown(); byId.value = {}; open() },
  )

  return { byId, status: computed(() => handle.status.value) }
}

/**
 * Live stream of kubernetes events as the controller observes them.
 *
 * Unlike the metrics streams this is **event-driven** (no interval) —
 * it rides the `k8sEvents` subscription and accumulates the most recent
 * events into the `samples` ring buffer (newest last). An overview /
 * events page seeds from the on-demand `events` query for history, then
 * prepends `samples` as new events arrive so the list stays live
 * without polling.
 */
export function useK8sEventsStream(opts: {
  cluster: MaybeRefOrGetter<string | null | undefined>
  namespace?: MaybeRefOrGetter<string | null | undefined>
}) {
  const handle: StreamHandle<K8sEventStreamItem> = {
    latest: ref<K8sEventStreamItem | null>(null),
    samples: ref<K8sEventStreamItem[]>([]),
    status: ref<Status>('idle'),
  }

  const query = `
    subscription K8sEventsStream($cluster: UUID!, $namespace: String) {
      k8sEvents(cluster: $cluster, namespace: $namespace) {
        id level when timestamp namespace involvedObject message reason
      }
    }
  `

  const { open, teardown } = bindSubscription(handle, {
    query,
    variables: () => ({
      cluster: toValue(opts.cluster),
      namespace: toValue(opts.namespace) ?? null,
    }),
    shouldOpen: () => !!toValue(opts.cluster),
    pickSample: (data) => (data?.k8sEvents as K8sEventStreamItem | undefined) ?? null,
  })

  onMounted(open)
  onUnmounted(teardown)
  watch(
    () => [toValue(opts.cluster), toValue(opts.namespace)],
    () => { teardown(); handle.samples.value = []; open() },
  )

  return {
    latest: handle.latest,
    samples: handle.samples,
    status: computed(() => handle.status.value),
  }
}

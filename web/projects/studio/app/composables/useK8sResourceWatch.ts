import { computed, onMounted, onUnmounted, onBeforeUnmount, ref, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { bindSubscription, type StreamHandle, type K8sStreamStatus } from './useK8sMetricsStream'

/**
 * Live change watch for kubernetes resources, riding the
 * `k8sResourcesWatch` GraphQL subscription.
 *
 * The subscription emits a metadata-only tick (kind / namespace /
 * name / action) whenever any resource of the watched kinds changes.
 * Pages don't reconcile those deltas — they pass their existing
 * `refresh()` functions as `onChange` and this composable invokes
 * them (coalesced per window) so the list re-renders from a fresh
 * query. That
 * keeps every list view realtime with one socket per page and zero
 * changes to the query/adapter layer.
 *
 * Kind tokens: built-in kinds by name (`Service`, `ConfigMap`,
 * `Node`, …), `HelmRelease` for the helm release pseudo-kind, and
 * CRD kinds optionally group-qualified (`postgresql.cnpg.io/Cluster`)
 * — resolution happens server-side against the cluster's CRDs.
 *
 * Socket lifecycle (auth, reconnect, teardown on unmount) is shared
 * with the metrics streams via `bindSubscription`.
 */

export interface K8sResourceChange {
  kind: string
  namespace: string | null
  name: string
  action: 'ADDED' | 'MODIFIED' | 'DELETED'
  timestamp: string
}

const Q_RESOURCES_WATCH = `
  subscription K8sResourcesWatch($cluster: UUID!, $kinds: [String!]!, $namespace: String) {
    k8sResourcesWatch(cluster: $cluster, kinds: $kinds, namespace: $namespace) {
      kind namespace name action timestamp
    }
  }
`

/**
 * Watch opens replay the existing resources as synthetic ADDED events,
 * and busy clusters can emit several MODIFIED ticks per second during
 * a rollout. Changes coalesce into at most one `onChange` per window —
 * a fixed window (rather than a resetting debounce) so continuous
 * churn can't starve the refresh indefinitely. 750ms keeps the UI
 * visibly live while bounding query rate.
 */
const DEFAULT_COALESCE_MS = 750

export function useK8sResourceWatch(opts: {
  cluster: MaybeRefOrGetter<string | null | undefined>
  /** Kind tokens to watch — see the composable docs for the token grammar. */
  kinds: MaybeRefOrGetter<string[]>
  namespace?: MaybeRefOrGetter<string | null | undefined>
  /** Invoked (coalesced to one call per window) when any watched resource changes. */
  onChange: () => unknown
  /** Coalescing window in milliseconds; defaults to 750. */
  coalesceMs?: number
}) {
  const handle: StreamHandle<K8sResourceChange> = {
    latest: ref<K8sResourceChange | null>(null),
    samples: ref<K8sResourceChange[]>([]),
    status: ref<K8sStreamStatus>('idle'),
  }

  let coalesceTimer: ReturnType<typeof setTimeout> | undefined
  const scheduleChange = () => {
    // Trailing throttle: the first change in a window arms the timer,
    // further changes ride the same window. Deliberately NOT a
    // resetting debounce — constant churn would push the refresh out
    // forever.
    if (coalesceTimer) return
    coalesceTimer = setTimeout(() => {
      coalesceTimer = undefined
      opts.onChange()
    }, opts.coalesceMs ?? DEFAULT_COALESCE_MS)
  }

  const { open, teardown } = bindSubscription(handle, {
    query: Q_RESOURCES_WATCH,
    variables: () => ({
      cluster: toValue(opts.cluster),
      kinds: toValue(opts.kinds),
      namespace: toValue(opts.namespace) ?? null,
    }),
    shouldOpen: () => !!toValue(opts.cluster) && toValue(opts.kinds).length > 0,
    pickSample: (data) => {
      const change = (data?.k8sResourcesWatch as K8sResourceChange | undefined) ?? null
      if (change) scheduleChange()
      return change
    },
  })

  onMounted(open)
  onUnmounted(teardown)
  onBeforeUnmount(() => {
    if (coalesceTimer) {
      clearTimeout(coalesceTimer)
      coalesceTimer = undefined
    }
  })
  watch(
    () => [toValue(opts.cluster), toValue(opts.namespace), JSON.stringify(toValue(opts.kinds))],
    () => { teardown(); open() },
  )

  return {
    /** Most recent change tick — useful for "last updated" affordances. */
    latest: handle.latest,
    status: computed(() => handle.status.value),
  }
}

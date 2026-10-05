import { computed, onMounted, onUnmounted, ref, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { LiveSessionsMapConfiguration } from '@bosca/ui-analytics'
import { bindSubscription, type K8sStreamStatus, type StreamHandle } from './useK8sMetricsStream'

const LIVE_SESSIONS_QUERY = `
  subscription LiveSessions($appId: String, $appVersion: String) {
    liveSessions(appId: $appId, appVersion: $appVersion) {
      sessionId lat lon appId appVersion
    }
  }
`

/** How often the local set is swept for sessions past their TTL (ms). */
const EVICT_INTERVAL_MS = 30_000

interface LiveSessionMessage {
  sessionId: string
  lat: number
  lon: number
  appId?: string | null
  appVersion?: string | null
}

/**
 * Live sessions for the map. Opens the `liveSessions` graphql-transport-ws subscription for [appId]
 * (empty/null = every application, optionally filtered to [appVersion]) and maintains a rolling,
 * TTL-evicted set in a {@link LiveSessionsMapConfiguration}. Exposes the current session rows (for the
 * map's `data` prop), the live count, and the connection status. Re-subscribes when the inputs change;
 * tears down on unmount.
 */
export function useLiveSessions(
  appId: MaybeRefOrGetter<string | null | undefined>,
  appVersion?: MaybeRefOrGetter<string | null | undefined>,
) {
  const config = new LiveSessionsMapConfiguration()
  // Bumped whenever the underlying (non-reactive) set changes, so the computed rows re-derive.
  const revision = ref(0)

  function configure() {
    config.loadConfiguration({ appId: toValue(appId) ?? '', appVersion: toValue(appVersion) ?? '' })
  }
  configure()

  const rows = computed<Record<string, unknown>[]>(() => {
    void revision.value
    return config.getSessions()
  })
  const sessionCount = computed(() => {
    void revision.value
    return config.sessionCount
  })

  const handle: StreamHandle<LiveSessionMessage> = {
    latest: ref<LiveSessionMessage | null>(null),
    samples: ref<LiveSessionMessage[]>([]),
    status: ref<K8sStreamStatus>('idle'),
  }

  const { open, teardown } = bindSubscription(handle, {
    query: LIVE_SESSIONS_QUERY,
    variables: () => ({ appId: toValue(appId) || null, appVersion: toValue(appVersion) || null }),
    shouldOpen: () => true, // no appId is the all-applications stream, not an unconfigured one

    pickSample: (data) => {
      const session = data?.liveSessions as LiveSessionMessage | undefined
      if (session) {
        config.applyMessage(session as unknown as Record<string, unknown>, Date.now())
        revision.value++
      }
      // Handled directly by the config; no ring buffer needed.
      return null
    },
  })

  let evictTimer: ReturnType<typeof setInterval> | undefined

  onMounted(() => {
    open()
    evictTimer = setInterval(() => {
      if (config.evictStale(Date.now())) revision.value++
    }, EVICT_INTERVAL_MS)
  })

  onUnmounted(() => {
    teardown()
    if (evictTimer) clearInterval(evictTimer)
  })

  watch(
    () => [toValue(appId), toValue(appVersion)],
    () => {
      teardown()
      config.reset()
      configure()
      revision.value++
      open()
    },
  )

  return {
    rows,
    sessionCount,
    status: computed(() => handle.status.value),
  }
}

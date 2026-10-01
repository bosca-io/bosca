import { computed, onMounted, onUnmounted, ref, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { bindSubscription, type K8sStreamStatus, type StreamHandle } from './useK8sMetricsStream'

const LIVE_SESSIONS_QUERY = `
  subscription LiveSessions($appId: String!, $appVersion: String) {
    liveSessions(appId: $appId, appVersion: $appVersion) {
      sessionId lat lon appVersion
    }
  }
`

const EVICT_INTERVAL_MS = 30_000
const TTL_MS = 15 * 60 * 1000

/** One analytics application to watch, tagged with the release project it belongs to (the map's color). */
export interface ReleaseLiveSource {
  appId: string
  appVersion: string | null
  project: string
}

interface LiveSessionMessage { sessionId: string; lat: number; lon: number }

/**
 * Live sessions across ALL of a release's analytics applications at once, merged into one point set and
 * tagged with the source `project` so the map colors each project its own color. Each source opens its own
 * `liveSessions` graphql-transport-ws subscription; a session key of `appId:sessionId` keeps sessions from
 * different apps distinct. Re-subscribes when the source list changes; sweeps a 15-minute TTL; tears every
 * socket down on unmount.
 */
export function useReleaseLiveSessions(sources: MaybeRefOrGetter<ReleaseLiveSource[]>) {
  const sessions = new Map<string, { row: Record<string, unknown>; lastSeen: number }>()
  const revision = ref(0)
  // Plain array, NOT a ref: nesting each handle's `status` ref inside a `ref<StreamHandle[]>` makes Vue
  // unwrap it, which breaks `.status.value`. Membership changes are re-tracked via `revision` instead.
  const handles: StreamHandle<LiveSessionMessage>[] = []
  const teardowns: Array<() => void> = []

  const rows = computed<Record<string, unknown>[]>(() => {
    void revision.value
    return Array.from(sessions.values(), e => e.row)
  })
  const sessionCount = computed(() => {
    void revision.value
    return sessions.size
  })
  const status = computed<K8sStreamStatus>(() => {
    void revision.value // re-track when the handle set changes (openAll/teardownAll bump it)
    const values = handles
      .map(h => h.status.value)
      .filter((s): s is K8sStreamStatus => s !== undefined)
    if (values.includes('open')) return 'open'
    if (values.includes('connecting')) return 'connecting'
    if (values.includes('error')) return 'error'
    return values.length ? 'closed' : 'idle'
  })

  function openAll() {
    for (const src of toValue(sources)) {
      const handle: StreamHandle<LiveSessionMessage> = {
        latest: ref<LiveSessionMessage | null>(null),
        samples: ref<LiveSessionMessage[]>([]),
        status: ref<K8sStreamStatus>('idle'),
      }
      const { open, teardown } = bindSubscription(handle, {
        query: LIVE_SESSIONS_QUERY,
        variables: () => ({ appId: src.appId, appVersion: src.appVersion }),
        shouldOpen: () => !!src.appId,
        pickSample: (data) => {
          const session = data?.liveSessions as LiveSessionMessage | undefined
          if (session) {
            sessions.set(`${src.appId}:${session.sessionId}`, {
              row: { sessionId: session.sessionId, lat: session.lat, lon: session.lon, project: src.project },
              lastSeen: Date.now(),
            })
            revision.value++
          }
          return null
        },
      })
      open()
      handles.push(handle)
      teardowns.push(teardown)
    }
    revision.value++
  }

  function teardownAll() {
    for (const teardown of teardowns) teardown()
    teardowns.length = 0
    handles.length = 0
    sessions.clear()
    revision.value++
  }

  let evictTimer: ReturnType<typeof setInterval> | undefined
  onMounted(() => {
    openAll()
    evictTimer = setInterval(() => {
      const now = Date.now()
      let changed = false
      for (const [key, entry] of sessions) {
        if (now - entry.lastSeen > TTL_MS) { sessions.delete(key); changed = true }
      }
      if (changed) revision.value++
    }, EVICT_INTERVAL_MS)
  })
  onUnmounted(() => {
    teardownAll()
    if (evictTimer) clearInterval(evictTimer)
  })

  watch(
    () => toValue(sources).map(s => `${s.appId}|${s.appVersion}|${s.project}`).join(','),
    () => { teardownAll(); openAll() },
  )

  return { rows, sessionCount, status }
}

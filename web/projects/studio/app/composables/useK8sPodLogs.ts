import type { Ref } from 'vue'
import type { LogLine as ViewerLogLine } from '@bosca/ui'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

/**
 * Live container log stream from `kubernetes-controller`, surfaced to
 * the studio's `@bosca/ui` LogViewer.
 *
 * Subscribes to the `podLogs` GraphQL subscription over the existing
 * `/graphqlws` proxy. The transport is `graphql-transport-ws` — same
 * pipe every other subscription rides — and the WebSocket carries the
 * Bosca session JWT via `connection_init` params so the resolver can
 * gate on admin group membership before the upstream NDJSON stream
 * opens.
 *
 * The composable owns its own WebSocket lifecycle rather than going
 * through `useGraphQL().useSubscription` so it can:
 *
 *  * Detect a `complete` frame (the controller's 5-minute streaming
 *    cap, or an end of a non-following stream) and **auto-reconnect**
 *    when `follow=true`. Logs should survive the controller's
 *    runaway-stream guard; users shouldn't notice the seam.
 *  * Expose a buffer of the last N lines (default 5000) trimmed FIFO
 *    so the DOM size for the LogViewer stays bounded.
 *  * Pause/resume independent of the underlying socket — the stream
 *    keeps flowing; we just stop appending visible lines.
 *
 * Cluster, namespace, pod, and container are passed in as refs; any
 * change tears the existing subscription down and starts a new one.
 */

type BackendLevel = 'INFO' | 'WARN' | 'ERROR'

interface BackendLogLine {
  pod: string
  container: string
  timestamp: string
  level: BackendLevel
  message: string
}

interface PodLogsPayload {
  data?: { k8sPodLogs?: BackendLogLine }
}

export type PodLogsStatus =
  | 'idle'
  | 'connecting'
  | 'streaming'
  | 'reconnecting'
  | 'closed'
  | 'error'

export interface UseK8sPodLogsOptions {
  cluster: Ref<string | null | undefined>
  namespace: Ref<string | null | undefined>
  pod: Ref<string | null | undefined>
  container?: Ref<string | null | undefined>
  follow?: Ref<boolean>
  tailLines?: number
  maxLines?: number
}

const PODLOGS_QUERY = `
  subscription PodLogs($cluster: UUID!, $namespace: String!, $pod: String!, $container: String, $tailLines: Int, $follow: Boolean) {
    k8sPodLogs(cluster: $cluster, namespace: $namespace, pod: $pod, container: $container, tailLines: $tailLines, follow: $follow) {
      pod
      container
      timestamp
      level
      message
    }
  }
`

export function useK8sPodLogs(opts: UseK8sPodLogsOptions) {
  const lines = ref<ViewerLogLine[]>([])
  const paused = ref(false)
  const status = ref<PodLogsStatus>('idle')
  const maxLines = opts.maxLines ?? 5000
  const tailLines = opts.tailLines ?? 200

  let ws: WebSocket | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let subscriptionId = 0
  let unsubscribed = false

  function clear() {
    lines.value = []
  }

  function togglePause() {
    paused.value = !paused.value
  }

  function pushLine(line: BackendLogLine) {
    if (paused.value) return
    const viewer = toViewerLine(line)
    const buffer = lines.value
    if (buffer.length >= maxLines) {
      buffer.splice(0, buffer.length - maxLines + 1)
    }
    buffer.push(viewer)
  }

  function variables() {
    return {
      cluster: opts.cluster.value,
      namespace: opts.namespace.value,
      pod: opts.pod.value,
      container: opts.container?.value ?? null,
      follow: opts.follow?.value ?? true,
      tailLines,
    }
  }

  async function connect() {
    if (import.meta.server) return
    if (unsubscribed) return
    if (!opts.cluster.value || !opts.namespace.value || !opts.pod.value) {
      status.value = 'idle'
      return
    }
    status.value = 'connecting'

    const config = useRuntimeConfig()
    const wsBase = (config.public as { wsUrl?: string }).wsUrl
      || `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}`
    const url = `${wsBase}/graphqlws`

    let connectionParams: Record<string, unknown> = {}
    try {
      const { $auth } = useNuxtApp()
      if ($auth) {
        const headers = await $auth.getAuthHeaders()
        const token = headers?.['Authorization']?.replace?.('Bearer ', '')
        if (token) connectionParams = { authToken: token }
      }
    } catch {
      // auth not ready — server will reject and reconnect path will retry
    }

    const socket = new WebSocket(url, 'graphql-transport-ws')
    ws = socket
    const id = String(++subscriptionId)

    socket.onopen = () => {
      socket.send(JSON.stringify({ type: 'connection_init', payload: connectionParams }))
    }

    socket.onmessage = (event) => {
      let msg: { type: string; payload?: unknown; id?: string }
      try { msg = JSON.parse(event.data) } catch { return }
      switch (msg.type) {
        case 'connection_ack':
          socket.send(JSON.stringify({
            id,
            type: 'subscribe',
            payload: { query: PODLOGS_QUERY, variables: variables() },
          }))
          status.value = 'streaming'
          break
        case 'ping':
          socket.send(JSON.stringify({ type: 'pong' }))
          break
        case 'next': {
          const payload = msg.payload as PodLogsPayload | undefined
          const line = payload?.data?.k8sPodLogs
          if (line) pushLine(line)
          break
        }
        case 'complete':
          // Upstream stream ended (controller's 5-min cap, or finite
          // non-follow stream). For follow mode we transparently
          // reconnect; for non-follow we settle into 'closed'.
          if ((opts.follow?.value ?? true) && !unsubscribed) {
            scheduleReconnect('complete')
          } else {
            status.value = 'closed'
            socket.close()
          }
          break
        case 'error':
          console.error('Pod log subscription error', msg.payload)
          status.value = 'error'
          if ((opts.follow?.value ?? true) && !unsubscribed) {
            scheduleReconnect('error')
          }
          break
      }
    }

    socket.onclose = () => {
      if (unsubscribed) return
      if (status.value === 'closed') return
      // Either the server dropped us or the network blipped — try to
      // come back if we should still be following.
      if (opts.follow?.value ?? true) {
        scheduleReconnect('close')
      } else {
        status.value = 'closed'
      }
    }

    socket.onerror = (err) => {
      console.warn('Pod log WebSocket error', err)
    }
  }

  function scheduleReconnect(_reason: string) {
    if (unsubscribed) return
    status.value = 'reconnecting'
    if (reconnectTimer) clearTimeout(reconnectTimer)
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      if (!unsubscribed) connect()
    }, 1000)
  }

  function disconnect() {
    unsubscribed = true
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (ws && ws.readyState <= WebSocket.OPEN) {
      try { ws.close() } catch { /* ignore */ }
    }
    ws = null
    status.value = 'closed'
  }

  function reset() {
    if (ws && ws.readyState <= WebSocket.OPEN) {
      try { ws.close() } catch { /* ignore */ }
    }
    ws = null
    lines.value = []
    if (!unsubscribed) connect()
  }

  // Re-subscribe when any of the identifying inputs change. We only
  // listen on the four refs that affect the upstream subscription; the
  // tailLines / maxLines tuning are config, not subscription topology.
  watch(
    () => [opts.cluster.value, opts.namespace.value, opts.pod.value, opts.container?.value, opts.follow?.value],
    () => { if (!unsubscribed) reset() },
  )

  onMounted(() => { connect() })
  onUnmounted(() => { disconnect() })

  const isStreaming = computed(() => status.value === 'streaming')
  const isReconnecting = computed(() => status.value === 'reconnecting')

  return {
    lines,
    paused,
    status,
    isStreaming,
    isReconnecting,
    togglePause,
    clear,
    disconnect,
    reset,
  }
}

function toViewerLine(line: BackendLogLine): ViewerLogLine {
  const lvl = line.level === 'ERROR' ? 'error' : line.level === 'WARN' ? 'warn' : 'info'
  return {
    t: line.timestamp || '',
    lvl,
    msg: line.message,
  }
}

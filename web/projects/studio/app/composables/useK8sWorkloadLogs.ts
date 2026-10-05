import type { Ref } from 'vue'
import type { LogLine as ViewerLogLine } from '@bosca/ui'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

/**
 * Merged log tail across every pod owned by a workload.
 *
 * Mirrors [useK8sPodLogs] one-to-one — same WebSocket lifecycle, same
 * `graphql-transport-ws` transport over `/graphqlws`, same auto-
 * reconnect on the controller's 5-minute streaming cap — but the
 * upstream subscription is `k8sWorkloadLogs` and the studio passes
 * `(cluster, namespace, kind, name)` instead of `(cluster, namespace,
 * pod, container)`. The server fans out one watch per pod and
 * funnels every line into the single stream; the studio sees one
 * socket per page no matter how many replicas the deployment runs.
 *
 * Each line keeps the source pod name in `pod` so the viewer can
 * colour-by-source (the viewer plug-in already supports that —
 * passing the pod through `msg` would be a UI follow-up if we want a
 * prefix in the rendered text).
 */

type BackendLevel = 'INFO' | 'WARN' | 'ERROR'

type WorkloadKind = 'DEPLOYMENT' | 'STATEFUL_SET' | 'DAEMON_SET' | 'REPLICA_SET' | 'JOB' | 'CRON_JOB'

interface BackendLogLine {
  pod: string
  container: string
  timestamp: string
  level: BackendLevel
  message: string
}

interface WorkloadLogsPayload {
  data?: { k8sWorkloadLogs?: BackendLogLine }
}

export type WorkloadLogsStatus =
  | 'idle'
  | 'connecting'
  | 'streaming'
  | 'reconnecting'
  | 'closed'
  | 'error'

export interface UseK8sWorkloadLogsOptions {
  cluster: Ref<string | null | undefined>
  namespace: Ref<string | null | undefined>
  kind: Ref<WorkloadKind | null | undefined>
  name: Ref<string | null | undefined>
  follow?: Ref<boolean>
  tailLines?: number
  maxLines?: number
}

const WORKLOAD_LOGS_QUERY = `
  subscription K8sWorkloadLogs($cluster: UUID!, $namespace: String!, $kind: WorkloadKind!, $name: String!, $tailLines: Int, $follow: Boolean) {
    k8sWorkloadLogs(cluster: $cluster, namespace: $namespace, kind: $kind, name: $name, tailLines: $tailLines, follow: $follow) {
      pod
      container
      timestamp
      level
      message
    }
  }
`

export function useK8sWorkloadLogs(opts: UseK8sWorkloadLogsOptions) {
  const lines = ref<ViewerLogLine[]>([])
  const paused = ref(false)
  const status = ref<WorkloadLogsStatus>('idle')
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
      kind: opts.kind.value,
      name: opts.name.value,
      follow: opts.follow?.value ?? true,
      tailLines,
    }
  }

  async function connect() {
    if (import.meta.server) return
    if (unsubscribed) return
    if (!opts.cluster.value || !opts.namespace.value || !opts.kind.value || !opts.name.value) {
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
    } catch { /* auth not ready */ }

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
            payload: { query: WORKLOAD_LOGS_QUERY, variables: variables() },
          }))
          status.value = 'streaming'
          break
        case 'ping':
          socket.send(JSON.stringify({ type: 'pong' }))
          break
        case 'next': {
          const payload = msg.payload as WorkloadLogsPayload | undefined
          const line = payload?.data?.k8sWorkloadLogs
          if (line) pushLine(line)
          break
        }
        case 'complete':
          if ((opts.follow?.value ?? true) && !unsubscribed) {
            scheduleReconnect('complete')
          } else {
            status.value = 'closed'
            socket.close()
          }
          break
        case 'error':
          console.error('Workload log subscription error', msg.payload)
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
      if (opts.follow?.value ?? true) {
        scheduleReconnect('close')
      } else {
        status.value = 'closed'
      }
    }

    socket.onerror = (err) => {
      console.warn('Workload log WebSocket error', err)
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

  watch(
    () => [opts.cluster.value, opts.namespace.value, opts.kind.value, opts.name.value, opts.follow?.value],
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
  // Prefix the message with the source pod so a merged tail is
  // readable as one view — the LogViewer has no per-line pod column.
  return {
    t: line.timestamp || '',
    lvl,
    msg: line.pod ? `[${line.pod}] ${line.message}` : line.message,
  }
}

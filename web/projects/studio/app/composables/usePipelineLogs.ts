import type { Ref } from 'vue'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

/**
 * Live pipeline step log stream via GraphQL subscription over
 * `/graphqlws`. Follows the same `graphql-transport-ws` transport
 * as `useK8sPodLogs`.
 *
 * Lines are kept in raw shape (with `lineNumber`) so callers can
 * merge subscription output with a historical-backfill query and
 * dedupe by line number. Use `seed()` to push a backfill batch
 * (e.g. on mount, or after the step finishes to capture the tail
 * end of the stream that may have landed after the agent's last
 * pub/sub flush).
 */

export interface PipelineLogLine {
  lineNumber: number
  timestamp: string
  content: string
  stream: string
}

interface PipelineLogsPayload {
  data?: { pipelineStepLogs?: PipelineLogLine }
}

export type PipelineLogsStatus =
  | 'idle'
  | 'connecting'
  | 'streaming'
  | 'reconnecting'
  | 'closed'
  | 'error'

export interface UsePipelineLogsOptions {
  repositoryId: Ref<string | null | undefined>
  stepId: Ref<string | null | undefined>
  follow?: Ref<boolean>
  maxLines?: number
}

const PIPELINE_LOGS_QUERY = `
  subscription PipelineStepLogs($repositoryId: UUID!, $stepId: UUID!) {
    pipelineStepLogs(repositoryId: $repositoryId, stepId: $stepId) {
      lineNumber
      content
      stream
      timestamp
    }
  }
`

export function usePipelineLogs(opts: UsePipelineLogsOptions) {
  const lines = ref<PipelineLogLine[]>([])
  const paused = ref(false)
  const status = ref<PipelineLogsStatus>('idle')
  const maxLines = opts.maxLines ?? 5000

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

  // Merge a batch into the buffer, deduping by lineNumber and keeping
  // the result sorted. Safe to call before, during, or after a live
  // subscription is connected — used for historical backfill and for
  // the post-completion final query.
  function seed(batch: PipelineLogLine[]) {
    if (!batch.length) return
    const byNumber = new Map<number, PipelineLogLine>()
    for (const l of lines.value) byNumber.set(l.lineNumber, l)
    for (const l of batch) byNumber.set(l.lineNumber, l)
    const merged = Array.from(byNumber.values()).sort((a, b) => a.lineNumber - b.lineNumber)
    if (merged.length > maxLines) merged.splice(0, merged.length - maxLines)
    lines.value = merged
  }

  function pushLine(line: PipelineLogLine) {
    if (paused.value) return
    const buffer = lines.value
    const last = buffer[buffer.length - 1]
    if (last && last.lineNumber >= line.lineNumber) {
      // Out-of-order or duplicate (e.g. after a seed overlap) — fold via seed.
      seed([line])
      return
    }
    if (buffer.length >= maxLines) {
      buffer.splice(0, buffer.length - maxLines + 1)
    }
    buffer.push(line)
  }

  function variables() {
    return {
      repositoryId: opts.repositoryId.value,
      stepId: opts.stepId.value,
    }
  }

  async function connect() {
    if (import.meta.server) return
    if (unsubscribed) return
    if (!opts.repositoryId.value || !opts.stepId.value) {
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
            payload: { query: PIPELINE_LOGS_QUERY, variables: variables() },
          }))
          status.value = 'streaming'
          break
        case 'ping':
          socket.send(JSON.stringify({ type: 'pong' }))
          break
        case 'next': {
          const payload = msg.payload as PipelineLogsPayload | undefined
          const line = payload?.data?.pipelineStepLogs
          if (line) pushLine(line)
          break
        }
        case 'complete':
          if ((opts.follow?.value ?? true) && !unsubscribed) {
            scheduleReconnect()
          } else {
            status.value = 'closed'
            socket.close()
          }
          break
        case 'error':
          console.error('Pipeline log subscription error', msg.payload)
          status.value = 'error'
          if ((opts.follow?.value ?? true) && !unsubscribed) {
            scheduleReconnect()
          }
          break
      }
    }

    socket.onclose = () => {
      if (unsubscribed) return
      if (status.value === 'closed') return
      if (opts.follow?.value ?? true) {
        scheduleReconnect()
      } else {
        status.value = 'closed'
      }
    }

    socket.onerror = (err) => {
      console.warn('Pipeline log WebSocket error', err)
    }
  }

  function scheduleReconnect() {
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
    () => [opts.repositoryId.value, opts.stepId.value, opts.follow?.value],
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
    seed,
    disconnect,
    reset,
  }
}

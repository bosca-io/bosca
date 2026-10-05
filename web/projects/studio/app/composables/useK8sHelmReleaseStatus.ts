import type { Ref } from 'vue'
import { onUnmounted, ref, watch } from 'vue'
import type { HelmStatus, K8sHelmRelease } from './useK8sTypes'

/**
 * Live release-status stream for a single helm release.
 *
 * Subscribes to the `helmReleaseStatus` GraphQL subscription over the
 * existing `/graphqlws` proxy. The studio's install / upgrade wizard
 * uses this to surface `pending-install → deployed` (or `failed`)
 * transitions during long rollouts without polling.
 *
 * Design notes:
 *  * The subscription is **opt-in** — `start()` opens the socket and
 *    `stop()` closes it. The caller controls when the watch begins so
 *    the page can fire the install mutation first and then start
 *    streaming, or vice versa.
 *  * The composable owns its own WebSocket (rather than going through
 *    `useGraphQL().useSubscription`) so it can re-subscribe whenever
 *    `cluster / namespace / name` change without remounting the page.
 *  * Reaching a terminal status (`DEPLOYED`, `FAILED`, `SUPERSEDED`,
 *    `UNINSTALLED`) closes the socket automatically — there's no
 *    further state for the caller to follow.
 */

type BackendStatus = 'DEPLOYED' | 'PENDING' | 'FAILED' | 'SUPERSEDED' | 'UNINSTALLED'

interface BackendRelease {
  id: string
  name: string
  namespace: string
  chart: string
  chartVersion: string
  appVersion: string
  revision: number
  status: BackendStatus
  updated: string
  installed: string
  repo: string
  repoUrl: string
  description: string
}

interface SubscriptionPayload {
  data?: { k8sHelmReleaseStatus?: BackendRelease }
}

export type HelmReleaseStatusConnState =
  | 'idle'
  | 'connecting'
  | 'streaming'
  | 'closed'
  | 'error'

export interface UseK8sHelmReleaseStatusOptions {
  cluster: Ref<string | null | undefined>
  namespace: Ref<string | null | undefined>
  name: Ref<string | null | undefined>
}

const STATUS_MAP: Record<BackendStatus, HelmStatus> = {
  DEPLOYED: 'deployed',
  PENDING: 'pending',
  FAILED: 'failed',
  SUPERSEDED: 'superseded',
  UNINSTALLED: 'failed',
}

const TERMINAL: ReadonlySet<BackendStatus> = new Set(['DEPLOYED', 'FAILED', 'SUPERSEDED', 'UNINSTALLED'])

const QUERY = `
  subscription HelmReleaseStatus($cluster: UUID!, $namespace: String!, $name: String!) {
    k8sHelmReleaseStatus(cluster: $cluster, namespace: $namespace, name: $name) {
      id name namespace chart chartVersion appVersion revision status
      updated installed repo repoUrl description
    }
  }
`

export function useK8sHelmReleaseStatus(opts: UseK8sHelmReleaseStatusOptions) {
  const release = ref<K8sHelmRelease | null>(null)
  const state = ref<HelmReleaseStatusConnState>('idle')

  let ws: WebSocket | null = null
  let subId = 0
  let unmounted = false

  function toMock(backend: BackendRelease): K8sHelmRelease {
    return {
      id: backend.id,
      name: backend.name,
      ns: backend.namespace,
      chart: backend.chart,
      chartVersion: backend.chartVersion,
      appVersion: backend.appVersion,
      revision: backend.revision,
      status: STATUS_MAP[backend.status] ?? 'pending',
      updated: backend.updated,
      installed: backend.installed,
      repo: backend.repo,
      repoUrl: backend.repoUrl,
      description: backend.description,
    }
  }

  function close() {
    if (ws && ws.readyState <= WebSocket.OPEN) {
      try { ws.close() } catch { /* ignore */ }
    }
    ws = null
  }

  async function start() {
    if (import.meta.server) return
    if (unmounted) return
    if (!opts.cluster.value || !opts.namespace.value || !opts.name.value) return

    // Tear down any previous connection — keep the most recent
    // (cluster, namespace, name) tuple as the source of truth.
    close()
    state.value = 'connecting'
    release.value = null
    subId += 1
    const id = `helm-${subId}`

    const config = useRuntimeConfig()
    const wsBase = (config.public as { wsUrl?: string }).wsUrl
      || `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}`
    const url = `${wsBase}/graphqlws`

    let connectionParams: Record<string, unknown> = {}
    try {
      const { $auth } = useNuxtApp()
      if ($auth) {
        const headers = await ($auth as { getAuthHeaders?: () => Promise<Record<string, string>> }).getAuthHeaders?.()
        const token = headers?.['Authorization']?.replace?.('Bearer ', '')
        if (token) connectionParams = { authToken: token }
      }
    } catch { /* auth not ready */ }

    const socket = new WebSocket(url, 'graphql-transport-ws')
    ws = socket

    socket.onopen = () => {
      socket.send(JSON.stringify({ type: 'connection_init', payload: connectionParams }))
    }

    socket.onmessage = (event) => {
      let msg: { type: string; id?: string; payload?: SubscriptionPayload }
      try {
        msg = JSON.parse(event.data)
      } catch {
        return
      }
      switch (msg.type) {
        case 'connection_ack':
          socket.send(JSON.stringify({
            id,
            type: 'subscribe',
            payload: {
              query: QUERY,
              variables: {
                cluster: opts.cluster.value,
                namespace: opts.namespace.value,
                name: opts.name.value,
              },
            },
          }))
          break
        case 'ping':
          socket.send(JSON.stringify({ type: 'pong' }))
          break
        case 'next': {
          const backend = msg.payload?.data?.k8sHelmReleaseStatus
          if (!backend) return
          release.value = toMock(backend)
          state.value = 'streaming'
          // Auto-close on terminal status — no more transitions to wait on.
          if (TERMINAL.has(backend.status)) {
            socket.send(JSON.stringify({ id, type: 'complete' }))
            close()
            state.value = 'closed'
          }
          break
        }
        case 'error':
          state.value = 'error'
          break
        case 'complete':
          state.value = 'closed'
          break
      }
    }

    socket.onerror = () => { state.value = 'error' }
    socket.onclose = () => {
      ws = null
      if (state.value !== 'error' && state.value !== 'closed') {
        state.value = 'closed'
      }
    }
  }

  function stop() {
    close()
    state.value = 'idle'
  }

  // Re-subscribe when any identifier changes mid-stream (e.g. user
  // navigates between releases on a follow-up wizard step). Subscription
  // only restarts if it was already running — `start()` from idle is the
  // page's responsibility.
  watch(
    () => [opts.cluster.value, opts.namespace.value, opts.name.value],
    () => {
      if (state.value === 'streaming' || state.value === 'connecting') {
        start()
      }
    },
  )

  onUnmounted(() => {
    unmounted = true
    close()
  })

  return { release, state, start, stop }
}

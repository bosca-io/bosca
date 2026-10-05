import { computed, ref, watch } from 'vue'
import type { K8sCluster } from './useK8sTypes'

// The currently selected cluster id is shared across the kubernetes subsystem.
// Persisted to localStorage so it survives reloads.
const STORAGE_KEY = 'k8s.cluster'
const selectedId = ref<string | null>(null)

function readStored(): string | null {
  if (typeof window === 'undefined') return null
  try { return window.localStorage.getItem(STORAGE_KEY) } catch { return null }
}
function writeStored(id: string) {
  if (typeof window === 'undefined') return
  try { window.localStorage.setItem(STORAGE_KEY, id) } catch { /* ignore */ }
}

/**
 * Single source of truth for the active kubernetes cluster.
 *
 * Backed by the live `kubernetes.clusters` query; the selection is
 * persisted to localStorage so the same cluster is restored across page
 * reloads. When the persisted id no longer matches a registered cluster
 * (cluster removed, kubeconfig revoked) the first available cluster is
 * picked instead.
 */
export function useK8sCluster() {
  const { data, status, refresh } = useK8sClusters()
  const clusters = computed<K8sCluster[]>(() => data.value ?? [])

  // Initialise the persisted id once, then keep it pointing at a valid
  // cluster as the live list refreshes.
  if (selectedId.value === null) {
    selectedId.value = readStored()
  }
  watch(clusters, (list) => {
    if (!list.length) return
    const exists = selectedId.value && list.some(c => c.id === selectedId.value)
    if (!exists) {
      selectedId.value = list[0]?.id ?? null
      if (selectedId.value) writeStored(selectedId.value)
    }
  }, { immediate: true })

  const current = computed<K8sCluster | null>(() => {
    if (!selectedId.value) return clusters.value[0] ?? null
    return clusters.value.find(c => c.id === selectedId.value) ?? clusters.value[0] ?? null
  })

  function setCluster(id: string) {
    selectedId.value = id
    writeStored(id)
  }

  return { clusters, current, setCluster, status, refresh }
}

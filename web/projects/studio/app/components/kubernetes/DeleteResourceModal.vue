<script setup lang="ts">
import { computed, ref } from 'vue'

/**
 * Generic delete-confirmation modal wrapping `kubernetes.deleteResource`.
 * Pages bind a reactive `resource` prop. When it's non-null the modal
 * shows; when null it's hidden. Emits `deleted` on success so the page
 * can refresh and `close` on cancel.
 */

export interface DeleteResourceTarget {
  /** Pretty kind shown in the prompt — e.g. "Pod", "Deployment". */
  displayKind: string
  /** Backend kind string passed to `deleteResource` — typically the Kubernetes Kind. */
  kind: string
  name: string
  namespace?: string | null
  /** API group for CRDs (e.g. `networking.k8s.io`). Omit for core resources. */
  group?: string | null
}

const props = defineProps<{
  resource: DeleteResourceTarget | null
}>()

const emit = defineEmits<{
  close: []
  deleted: [resource: DeleteResourceTarget]
}>()

const { current } = useK8sCluster()
const clusterIdRef = computed(() => current.value?.id)
const { deleteResource } = useK8sMutations({ cluster: clusterIdRef })
const toast = useToast()

const loading = ref(false)

async function confirm() {
  const target = props.resource
  if (!target) return
  loading.value = true
  try {
    await deleteResource({
      kind: target.kind,
      name: target.name,
      namespace: target.namespace ?? undefined,
      group: target.group ?? undefined,
    })
    toast.success(`${target.displayKind} ${target.name} deleted`)
    emit('deleted', target)
  } catch (err) {
    const msg = err instanceof Error && err.message ? err.message : 'Unknown error'
    toast.error(`Failed to delete ${target.displayKind} ${target.name}: ${msg}`)
  } finally {
    loading.value = false
  }
}

function close() {
  if (!loading.value) emit('close')
}
</script>

<template>
  <ConfirmModal
    v-if="resource"
    :title="`Delete ${resource.displayKind} ${resource.name}?`"
    confirm-label="Delete"
    :loading="loading"
    @close="close"
    @confirm="confirm"
  >
    <p class="body">
      This will remove
      <strong class="mono">{{ resource.displayKind }}/{{ resource.name }}</strong>
      <template v-if="resource.namespace">
        from namespace <strong class="mono">{{ resource.namespace }}</strong>
      </template>
      from the cluster.
    </p>
  </ConfirmModal>
</template>

<style scoped>
.body { margin: 0; font-size: 13px; color: var(--fg-2); line-height: 1.5; }
.mono { font-family: var(--font-mono); color: var(--fg-1); font-weight: 600; }
</style>

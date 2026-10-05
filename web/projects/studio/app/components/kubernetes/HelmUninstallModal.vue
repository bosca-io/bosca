<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * Confirmation modal for `helmUninstall`. Pages bind a reactive
 * `release` prop (name + namespace). Optional `keepHistory` lets the
 * caller leave behind the release Secret history for rollback after
 * uninstall.
 */

export interface HelmUninstallTarget {
  name: string
  namespace: string
}

const props = defineProps<{
  release: HelmUninstallTarget | null
}>()

const emit = defineEmits<{
  close: []
  uninstalled: [release: HelmUninstallTarget]
}>()

const { current } = useK8sCluster()
const clusterIdRef = computed(() => current.value?.id)
const { helmUninstall } = useK8sMutations({ cluster: clusterIdRef })
const toast = useToast()

const loading = ref(false)
const keepHistory = ref(false)

watch(() => props.release, (r) => {
  if (r) keepHistory.value = false
})

async function confirm() {
  const target = props.release
  if (!target) return
  loading.value = true
  try {
    await helmUninstall({
      namespace: target.namespace,
      name: target.name,
      keepHistory: keepHistory.value,
    })
    toast.success(`Uninstalled ${target.name}`)
    emit('uninstalled', target)
  } catch (err) {
    const msg = err instanceof Error && err.message ? err.message : 'Unknown error'
    toast.error(`Failed to uninstall ${target.name}: ${msg}`)
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
    v-if="release"
    :title="`Uninstall ${release.name}?`"
    confirm-label="Uninstall"
    :loading="loading"
    @close="close"
    @confirm="confirm"
  >
    <div class="body">
      <p class="text">
        This removes all kubernetes resources owned by release
        <strong class="mono">{{ release.namespace }}/{{ release.name }}</strong>.
      </p>
      <label class="checkbox">
        <input
          v-model="keepHistory"
          type="checkbox"
          :disabled="loading">
        <span>Keep release history (allows future rollback)</span>
      </label>
    </div>
  </ConfirmModal>
</template>

<style scoped>
.body { display: flex; flex-direction: column; gap: 12px; }
.text { margin: 0; font-size: 13px; color: var(--fg-2); line-height: 1.5; }
.mono { font-family: var(--font-mono); color: var(--fg-1); font-weight: 600; }
.checkbox { display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--fg-2); cursor: pointer; }
.checkbox input { cursor: pointer; }
</style>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { K8sApplyResult } from '~/composables/useK8sMutations'

/**
 * Load-edit-apply modal for a single kubernetes resource. Reads the
 * current manifest via `kubernetes.yaml`, lets the operator edit it in
 * the CodeEditor, and submits the result through `applyManifest`.
 *
 * Pass `readonly` to use the same modal as a YAML viewer (no Apply
 * footer button, no edit possible).
 */

export interface EditResourceYamlTarget {
  displayKind: string
  kind: string
  name: string
  namespace?: string | null
  group?: string | null
}

const props = defineProps<{
  target: EditResourceYamlTarget | null
  accent?: string
  readonly?: boolean
}>()

const emit = defineEmits<{
  close: []
  applied: [target: EditResourceYamlTarget, result: K8sApplyResult]
}>()

const { current } = useK8sCluster()
const clusterIdRef = computed(() => current.value?.id)
const { applyManifest } = useK8sMutations({ cluster: clusterIdRef })
const toast = useToast()

const manifest = ref('')
const original = ref('')
const submitting = ref(false)
const banner = ref<string | null>(null)
const result = ref<K8sApplyResult | null>(null)

const { data: yamlData, status: yamlStatus } = useK8sResourceYaml({
  cluster: clusterIdRef,
  kind: () => props.target?.kind ?? null,
  name: () => props.target?.name ?? null,
  namespace: () => props.target?.namespace ?? null,
  group: () => props.target?.group ?? null,
})

watch(yamlData, (text) => {
  manifest.value = text ?? ''
  original.value = text ?? ''
}, { immediate: true })

watch(() => props.target, (t) => {
  if (!t) {
    manifest.value = ''
    original.value = ''
    banner.value = null
    result.value = null
  }
})

const isDirty = computed(() => manifest.value !== original.value)
const isLoading = computed(() => yamlStatus.value === 'pending')

async function run(dryRun: boolean) {
  const target = props.target
  if (!target) return
  if (!manifest.value.trim()) {
    banner.value = 'Manifest is empty.'
    return
  }
  if (!clusterIdRef.value) {
    banner.value = 'Select a cluster before applying.'
    return
  }
  banner.value = null
  result.value = null
  submitting.value = true
  try {
    const r = await applyManifest({ manifest: manifest.value, dryRun })
    result.value = r
    if (r.succeeded) {
      toast.success(dryRun
        ? `Dry-run OK — ${r.applied.length} resource${r.applied.length === 1 ? '' : 's'} would apply`
        : `Applied ${r.applied.length} resource${r.applied.length === 1 ? '' : 's'}`,
      )
      if (!dryRun) {
        emit('applied', target, r)
        original.value = manifest.value
      }
    } else {
      toast.error(`${r.failed.length} resource${r.failed.length === 1 ? '' : 's'} failed`)
    }
  } catch (err) {
    banner.value = err instanceof Error && err.message ? err.message : 'Apply failed'
  } finally {
    submitting.value = false
  }
}

function close() {
  if (!submitting.value) emit('close')
}
</script>

<template>
  <Modal
    v-if="target"
    :title="readonly ? `YAML — ${target.displayKind} ${target.name}` : `Edit YAML — ${target.displayKind} ${target.name}`"
    :subtitle="target.namespace ? `${target.namespace}/${target.name}` : target.name"
    icon="code"
    :accent="accent ?? '#326ce5'"
    width="780px"
    @close="close"
  >
    <div v-if="banner" class="banner">{{ banner }}</div>

    <div v-if="isLoading" class="loading">Loading manifest…</div>
    <CodeEditor
      v-else
      v-model="manifest"
      language="text"
      :rows="20"
      :readonly="readonly"
    />

    <div v-if="result" class="result">
      <div class="result-head">
        <span class="result-status" :class="result.succeeded ? 'ok' : 'err'">
          {{ result.succeeded ? 'Succeeded' : 'Partial failure' }}
        </span>
        <span class="muted">{{ result.applied.length }} applied · {{ result.failed.length }} failed</span>
      </div>
      <div v-if="result.applied.length" class="result-section">
        <div class="result-label">Applied</div>
        <ul class="result-list">
          <li v-for="r in result.applied" :key="r" class="mono">{{ r }}</li>
        </ul>
      </div>
      <div v-if="result.failed.length" class="result-section">
        <div class="result-label">Failed</div>
        <ul class="result-list">
          <li v-for="f in result.failed" :key="f.resource" class="failed">
            <span class="mono">{{ f.resource }}</span>
            <span class="err-text">{{ f.error }}</span>
          </li>
        </ul>
      </div>
    </div>

    <template #footer>
      <Button size="sm" :disabled="submitting" @click="close">Close</Button>
      <template v-if="!readonly">
        <Button size="sm" :disabled="submitting || isLoading || !isDirty" @click="run(true)">
          {{ submitting ? 'Working…' : 'Dry run' }}
        </Button>
        <Button
          primary
          size="sm"
          :accent="accent ?? '#326ce5'"
          :disabled="submitting || isLoading || !isDirty"
          @click="run(false)">
          {{ submitting ? 'Applying…' : 'Apply' }}
        </Button>
      </template>
    </template>
  </Modal>
</template>

<style scoped>
.banner {
  background: color-mix(in oklch, var(--err) 12%, var(--bg-2));
  border: 1px solid color-mix(in oklch, var(--err) 30%, transparent);
  color: var(--fg-1);
  padding: 10px 12px;
  border-radius: 6px;
  margin-bottom: 12px;
  font-size: 13px;
}
.loading { padding: 24px; text-align: center; color: var(--fg-3); font-size: 13px; }
.result { margin-top: 14px; border-top: 1px solid var(--line); padding-top: 12px; }
.result-head { display: flex; align-items: center; gap: 14px; margin-bottom: 10px; }
.result-status {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  padding: 3px 8px;
  border-radius: 4px;
}
.result-status.ok { background: color-mix(in oklch, var(--ok) 18%, var(--bg-2)); color: var(--ok); }
.result-status.err { background: color-mix(in oklch, var(--err) 18%, var(--bg-2)); color: var(--err); }
.muted { color: var(--fg-3); font-size: 12px; }
.result-section { margin-bottom: 10px; }
.result-label {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--fg-3);
  margin-bottom: 4px;
}
.result-list { margin: 0; padding: 0; list-style: none; }
.result-list li { padding: 3px 0; font-size: 12.5px; }
.result-list .failed { display: grid; grid-template-columns: 1fr 2fr; gap: 12px; }
.err-text { color: var(--err); font-size: 12px; }
.mono { font-family: var(--font-mono); }
</style>

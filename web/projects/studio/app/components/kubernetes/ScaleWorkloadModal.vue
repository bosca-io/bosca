<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { WorkloadKind } from '~/composables/useK8sTypes'
import { toBackendKind } from '~/composables/useK8sMutations'

/**
 * Replicas-change modal wrapping `kubernetes.scaleWorkload`.
 * Pages bind a reactive `target` prop. When non-null the modal shows;
 * when null it's hidden. Emits `scaled` on success.
 */

export interface ScaleWorkloadTarget {
  kind: WorkloadKind
  name: string
  namespace: string
  currentReplicas: number
}

const props = defineProps<{
  target: ScaleWorkloadTarget | null
  accent?: string
}>()

const emit = defineEmits<{
  close: []
  scaled: [target: ScaleWorkloadTarget, replicas: number]
}>()

const { current } = useK8sCluster()
const clusterIdRef = computed(() => current.value?.id)
const { scaleWorkload } = useK8sMutations({ cluster: clusterIdRef })
const toast = useToast()

const replicas = ref<number>(0)
const submitting = ref(false)
const banner = ref<string | null>(null)

watch(() => props.target, (t) => {
  if (t) {
    replicas.value = t.currentReplicas
    banner.value = null
  }
}, { immediate: true })

const isValid = computed(() => Number.isInteger(replicas.value) && replicas.value >= 0)

async function submit() {
  const target = props.target
  if (!target) return
  if (!isValid.value) {
    banner.value = 'Replicas must be a non-negative integer.'
    return
  }
  const backendKind = toBackendKind(target.kind)
  if (!backendKind) {
    banner.value = `Workload kind ${target.kind} is not scalable.`
    return
  }
  banner.value = null
  submitting.value = true
  try {
    await scaleWorkload({
      namespace: target.namespace,
      kind: backendKind,
      name: target.name,
      replicas: replicas.value,
    })
    toast.success(`Scaled ${target.kind} ${target.name} to ${replicas.value} replica${replicas.value === 1 ? '' : 's'}`)
    emit('scaled', target, replicas.value)
  } catch (err: unknown) {
    banner.value = err instanceof Error && err.message ? err.message : 'Scale failed'
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
    :title="`Scale ${target.kind}`"
    :subtitle="`${target.namespace}/${target.name}`"
    icon="maximize"
    :accent="accent ?? '#326ce5'"
    @close="close"
  >
    <div v-if="banner" class="banner">{{ banner }}</div>

    <div class="form">
      <div class="row">
        <label class="label">Current replicas</label>
        <span class="value mono">{{ target.currentReplicas }}</span>
      </div>
      <div class="row">
        <label for="replicas" class="label">Replicas</label>
        <input
          id="replicas"
          v-model.number="replicas"
          type="number"
          min="0"
          step="1"
          class="number-input mono"
          :disabled="submitting">

      </div>
      <p class="hint">Set to <strong>0</strong> to scale the workload down without deleting it.</p>
    </div>

    <template #footer>
      <Button size="sm" :disabled="submitting" @click="close">Cancel</Button>
      <Button
        primary
        size="sm"
        :accent="accent ?? '#326ce5'"
        :disabled="submitting || !isValid || replicas === target.currentReplicas"
        @click="submit">
        {{ submitting ? 'Scaling…' : 'Scale' }}
      </Button>
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
.form { display: flex; flex-direction: column; gap: 14px; }
.row { display: grid; grid-template-columns: 140px 1fr; align-items: center; gap: 12px; }
.label { font-size: 12.5px; color: var(--fg-3); }
.value { font-size: 13.5px; color: var(--fg-1); }
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.number-input {
  width: 110px;
  background: var(--bg-2);
  border: 1px solid var(--line-2);
  border-radius: 6px;
  padding: 6px 10px;
  color: var(--fg-1);
  font-size: 13px;
}
.number-input:focus { outline: none; border-color: var(--brand-2, #326ce5); }
.hint { font-size: 11.5px; color: var(--fg-3); margin: 0; }
.hint strong { color: var(--fg-2); }
</style>

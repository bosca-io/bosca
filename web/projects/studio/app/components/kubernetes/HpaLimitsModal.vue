<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * Replica-bounds modal wrapping `kubernetes.updateHpaLimits` — the
 * quick-edit counterpart of the YAML editor for HorizontalPodAutoscalers.
 * Pages bind a reactive `target` prop. When non-null the modal shows;
 * when null it's hidden. Emits `updated` on success.
 */

export interface HpaLimitsTarget {
  name: string
  namespace: string
  minReplicas: number
  maxReplicas: number
}

const props = defineProps<{
  target: HpaLimitsTarget | null
  accent?: string
}>()

const emit = defineEmits<{
  close: []
  updated: [target: HpaLimitsTarget, minReplicas: number, maxReplicas: number]
}>()

const { current } = useK8sCluster()
const clusterIdRef = computed(() => current.value?.id)
const { updateHpaLimits } = useK8sMutations({ cluster: clusterIdRef })
const toast = useToast()

const minReplicas = ref<number>(1)
const maxReplicas = ref<number>(1)
const submitting = ref(false)
const banner = ref<string | null>(null)

watch(() => props.target, (t) => {
  if (t) {
    minReplicas.value = t.minReplicas
    maxReplicas.value = t.maxReplicas
    banner.value = null
  }
}, { immediate: true })

const isValid = computed(() =>
  Number.isInteger(minReplicas.value)
  && Number.isInteger(maxReplicas.value)
  && minReplicas.value >= 1
  && maxReplicas.value >= minReplicas.value)

const unchanged = computed(() =>
  props.target !== null
  && minReplicas.value === props.target.minReplicas
  && maxReplicas.value === props.target.maxReplicas)

async function submit() {
  const target = props.target
  if (!target) return
  if (!isValid.value) {
    banner.value = 'Min must be at least 1 and max must be at least min.'
    return
  }
  banner.value = null
  submitting.value = true
  try {
    await updateHpaLimits({
      namespace: target.namespace,
      name: target.name,
      minReplicas: minReplicas.value,
      maxReplicas: maxReplicas.value,
    })
    toast.success(`Updated ${target.name} bounds to ${minReplicas.value}–${maxReplicas.value} replicas`)
    emit('updated', target, minReplicas.value, maxReplicas.value)
  } catch (err: unknown) {
    banner.value = err instanceof Error && err.message ? err.message : 'Update failed'
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
    title="Edit autoscaler limits"
    :subtitle="`${target.namespace}/${target.name}`"
    icon="maximize"
    :accent="accent ?? '#326ce5'"
    @close="close"
  >
    <div v-if="banner" class="banner">{{ banner }}</div>

    <div class="form">
      <div class="row">
        <label for="hpa-min" class="label">Min replicas</label>
        <input
          id="hpa-min"
          v-model.number="minReplicas"
          type="number"
          min="1"
          step="1"
          class="number-input mono"
          :disabled="submitting">
      </div>
      <div class="row">
        <label for="hpa-max" class="label">Max replicas</label>
        <input
          id="hpa-max"
          v-model.number="maxReplicas"
          type="number"
          :min="minReplicas"
          step="1"
          class="number-input mono"
          :disabled="submitting">
      </div>
      <p class="hint">Metric targets and scaling behavior are edited through the YAML editor.</p>
    </div>

    <template #footer>
      <Button size="sm" :disabled="submitting" @click="close">Cancel</Button>
      <Button
        primary
        size="sm"
        :accent="accent ?? '#326ce5'"
        :disabled="submitting || !isValid || unchanged"
        @click="submit">
        {{ submitting ? 'Updating…' : 'Update' }}
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
</style>

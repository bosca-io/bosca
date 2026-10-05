<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { K8sHelmReleaseOut } from '~/composables/useK8sMutations'

/**
 * Upgrade modal for an installed Helm release. Loads available chart
 * versions and the release's current effective values, lets the
 * operator pick a target version and edit values, then submits via
 * `helmUpgrade`. Supports server-side dry-run.
 */

export interface HelmUpgradeTarget {
  name: string
  namespace: string
  repo: string
  chart: string
  chartVersion: string
}

const props = defineProps<{
  release: HelmUpgradeTarget | null
  accent?: string
}>()

const emit = defineEmits<{
  close: []
  upgraded: [release: HelmUpgradeTarget, result: K8sHelmReleaseOut]
}>()

const { current } = useK8sCluster()
const clusterIdRef = computed(() => current.value?.id)
const { helmUpgrade } = useK8sMutations({ cluster: clusterIdRef })
const toast = useToast()

const submitting = ref(false)
const banner = ref<string | null>(null)
const selectedVersion = ref<string>('')
const valuesText = ref<string>('')
const resetValues = ref(false)

const repoRef = computed(() => props.release?.repo ?? null)
const chartRef = computed(() => props.release?.chart ?? null)
const namespaceRef = computed(() => props.release?.namespace ?? null)
const nameRef = computed(() => props.release?.name ?? null)

const { data: versionsData, status: versionsStatus } = useK8sHelmChartVersions({
  repo: repoRef,
  chart: chartRef,
})
const versions = computed(() => versionsData.value ?? [])
const versionOptions = computed(() => versions.value.map(v => ({
  label: v.current ? `${v.version} (latest)` : v.version,
  value: v.version,
})))
const isLoadingVersions = computed(() => versionsStatus.value === 'pending')

const { data: currentValues, status: valuesStatus } = useK8sHelmReleaseValues({
  cluster: clusterIdRef,
  namespace: namespaceRef,
  name: nameRef,
})
const isLoadingValues = computed(() => valuesStatus.value === 'pending')

watch(() => props.release, (r) => {
  if (r) {
    selectedVersion.value = r.chartVersion
    banner.value = null
    resetValues.value = false
  } else {
    selectedVersion.value = ''
    valuesText.value = ''
  }
})

watch(currentValues, (text) => {
  if (!resetValues.value && props.release) {
    valuesText.value = text ?? ''
  }
}, { immediate: true })

watch(resetValues, (reset) => {
  if (reset) {
    valuesText.value = ''
  } else if (currentValues.value) {
    valuesText.value = currentValues.value
  }
})

async function run(dryRun: boolean) {
  const target = props.release
  if (!target) return
  if (!selectedVersion.value) {
    banner.value = 'Pick a chart version.'
    return
  }
  banner.value = null
  submitting.value = true
  try {
    const result = await helmUpgrade({
      name: target.name,
      namespace: target.namespace,
      version: selectedVersion.value,
      values: valuesText.value || null,
      dryRun,
      resetValues: resetValues.value,
    })
    if (dryRun) {
      toast.success(`Dry-run OK for ${target.name} → ${selectedVersion.value}`)
    } else {
      toast.success(`Upgraded ${target.name} to ${selectedVersion.value}`)
      emit('upgraded', target, result)
    }
  } catch (err) {
    banner.value = err instanceof Error && err.message ? err.message : 'Upgrade failed'
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
    v-if="release"
    :title="`Upgrade ${release.name}`"
    :subtitle="`${release.namespace}/${release.name} · ${release.chart}`"
    icon="arrowUpRight"
    :accent="accent ?? '#326ce5'"
    width="720px"
    @close="close"
  >
    <div v-if="banner" class="banner">{{ banner }}</div>

    <div class="row">
      <label class="label">Chart version</label>
      <Select
        v-model="selectedVersion"
        :options="versionOptions"
        :loading="isLoadingVersions"
        :disabled="submitting" />
    </div>

    <div class="row stack">
      <div class="label-row">
        <label class="label">Values</label>
        <span class="spacer" />
        <label class="checkbox">
          <input v-model="resetValues" type="checkbox" :disabled="submitting">
          <span>Reset to chart defaults</span>
        </label>
      </div>
      <div v-if="isLoadingValues" class="loading">Loading current values…</div>
      <CodeEditor
        v-else
        v-model="valuesText"
        language="text"
        :rows="14"
        placeholder="# Values overrides as YAML…" />
    </div>

    <template #footer>
      <Button size="sm" :disabled="submitting" @click="close">Cancel</Button>
      <Button
        size="sm"
        :disabled="submitting || isLoadingVersions || !selectedVersion"
        @click="run(true)">
        {{ submitting ? 'Working…' : 'Dry run' }}
      </Button>
      <Button
        primary
        size="sm"
        :accent="accent ?? '#326ce5'"
        :disabled="submitting || isLoadingVersions || !selectedVersion"
        @click="run(false)">
        {{ submitting ? 'Upgrading…' : 'Upgrade' }}
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
.row { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.row.stack { flex-direction: column; align-items: stretch; gap: 8px; }
.label { font-size: 12.5px; color: var(--fg-3); width: 110px; flex-shrink: 0; }
.label-row { display: flex; align-items: center; gap: 8px; }
.label-row .label { width: auto; }
.spacer { flex: 1; }
.checkbox { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--fg-2); cursor: pointer; }
.checkbox input { cursor: pointer; }
.loading { padding: 18px; text-align: center; color: var(--fg-3); font-size: 12.5px; }
</style>

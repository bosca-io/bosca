<script setup lang="ts">
import { computed, ref } from 'vue'

const route = useRoute()
const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const toast = useToast()

const clusterIdRef = computed(() => current.value?.id)
const mutations = useK8sMutations({ cluster: clusterIdRef })

const chartId = computed(() => (route.query.chart as string | undefined) ?? '')
const repoFromId = computed(() => chartId.value.split('/')[0] ?? null)
const chartFromId = computed(() => chartId.value.split('/').slice(1).join('/') || null)

const { data: chartsData } = useK8sHelmCharts({ repo: repoFromId, search: chartFromId })
const chart = computed(() => (chartsData.value ?? []).find(c => c.id === chartId.value))

const { data: namespacesData } = useK8sNamespaces(clusterIdRef)
const nsOptions = computed(() => (namespacesData.value ?? []).map(n => ({ label: n.name, value: n.name })))

const { data: valuesData } = useK8sHelmChartValues({
  repo: () => chart.value?.repo,
  chart: () => chart.value?.name,
  version: () => chart.value?.version,
})

const releaseName = ref('')
const namespace = ref('default')
const createNs = ref(false)
const newNsName = ref('')
const version = ref('')
const valuesYaml = ref('')

watch(chart, (c) => {
  if (c && !version.value) version.value = c.version
})
watch(valuesData, (v) => {
  if (v && !valuesYaml.value) valuesYaml.value = v.defaultValues
})
watch(namespacesData, (list) => {
  if (list && list.length > 0 && !list.some(n => n.name === namespace.value)) {
    const first = list[0]
    if (first) namespace.value = first.name
  }
})

const dryRunOutput = ref<string | null>(null)
const installing = ref(false)
const installed = ref(false)

// Subscribe to live release-status transitions during install.
// `start()` is called immediately before firing the install mutation so
// the first PENDING event from the backend's fabric8 Secret watch can't
// race ahead of us. The composable auto-closes on terminal status.
const liveNamespace = ref<string | null>(null)
const liveName = ref<string | null>(null)
const { release: liveRelease, state: liveState, start: startLive, stop: stopLive } = useK8sHelmReleaseStatus({
  cluster: clusterIdRef,
  namespace: liveNamespace,
  name: liveName,
})

const liveStatusLabel = computed(() => {
  const s = liveRelease.value?.status
  switch (s) {
    case 'deployed': return 'Deployed'
    case 'pending': return 'Pending'
    case 'failed': return 'Failed'
    case 'superseded': return 'Superseded'
    default: return liveState.value === 'connecting' ? 'Connecting…'
      : liveState.value === 'streaming' ? 'Waiting for first event…'
      : liveState.value === 'error' ? 'Stream error'
      : ''
  }
})

async function runDryRun() {
  if (!chart.value || !releaseName.value.trim()) {
    toast.error('Set a release name first')
    return
  }
  installing.value = true
  try {
    const release = await mutations.helmInstall({
      name: releaseName.value,
      namespace: createNs.value ? (newNsName.value || namespace.value) : namespace.value,
      createNamespace: createNs.value,
      repo: chart.value.repo,
      chart: chart.value.name,
      version: version.value,
      values: valuesYaml.value || null,
      dryRun: true,
    })
    dryRunOutput.value = `Dry-run succeeded: ${release.namespace}/${release.name} @ rev ${release.revision}`
    toast.success('Dry-run passed')
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : 'Dry-run failed'
    toast.error(msg)
    dryRunOutput.value = msg
  } finally {
    installing.value = false
  }
}

async function install() {
  if (!chart.value || !releaseName.value.trim()) {
    toast.error('Set a release name first')
    return
  }
  installing.value = true
  const targetNs = createNs.value ? (newNsName.value || namespace.value) : namespace.value
  liveNamespace.value = targetNs
  liveName.value = releaseName.value
  await startLive()
  try {
    await mutations.helmInstall({
      name: releaseName.value,
      namespace: targetNs,
      createNamespace: createNs.value,
      repo: chart.value.repo,
      chart: chart.value.name,
      version: version.value,
      values: valuesYaml.value || null,
      dryRun: false,
    })
    installed.value = true
    toast.success(`Installed ${releaseName.value}`)
  } catch (err: unknown) {
    toast.error(err instanceof Error ? err.message : 'Install failed')
    stopLive()
  } finally {
    installing.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="[{ label: 'Kubernetes', to: '/kubernetes/overview' }, { label: 'Helm Catalog', to: '/kubernetes/helm/catalog' }, 'Install']"
        :title="`Install ${chart?.name || ''}`"
        :subtitle="chart ? `${chart.repo}/${chart.name} — app v${chart.appVersion}` : ''"
      >
        <template #actions>
          <Button size="sm" icon="eye" @click="runDryRun">Dry-run</Button>
          <Button
            primary
            :accent="accent"
            size="sm"
            icon="download"
            :disabled="installing || installed"
            @click="install">
            {{ installed ? 'Installed' : installing ? 'Installing…' : 'Install' }}
          </Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div class="layout">
      <SectionCard padded glass class="left">
        <template #header>
          <h3 class="card-title">Target</h3>
        </template>
        <div class="form-stack">
          <TextInput v-model="releaseName" label="Release name" placeholder="e.g. monitoring-stack" />
          <div class="ns-row">
            <Select
              v-model="namespace"
              label="Namespace"
              :options="nsOptions"
              :disabled="createNs" />
            <Checkbox v-model="createNs" label="Create namespace" />
          </div>
          <TextInput
            v-if="createNs"
            v-model="newNsName"
            label="New namespace name"
            placeholder="e.g. acme-tools" />
          <TextInput
            v-model="version"
            label="Chart version"
            placeholder="0.0.1"
            mono />
        </div>
      </SectionCard>

      <SectionCard padded glass class="right">
        <template #header>
          <div class="card-title-row">
            <h3 class="card-title">values.yaml</h3>
            <span class="muted">schema-validated when applied</span>
          </div>
        </template>
        <CodeEditor v-model="valuesYaml" language="text" :rows="22" />
      </SectionCard>
    </div>

    <SectionCard v-if="liveRelease || liveState !== 'idle'" padded glass>
      <template #header>
        <div class="card-title-row">
          <h3 class="card-title">Release status</h3>
          <span class="muted">live · subscription closes on terminal state</span>
        </div>
      </template>
      <div class="live-row">
        <K8sStatusBadge
          :status="liveStatusLabel"
          :pulse="liveRelease?.status === 'pending' || liveState === 'connecting'"
        />
        <span v-if="liveRelease" class="mono dim">
          {{ liveRelease.ns }}/{{ liveRelease.name }} · rev {{ liveRelease.revision }}
        </span>
        <span v-if="liveRelease?.description" class="dim small">{{ liveRelease.description }}</span>
      </div>
    </SectionCard>

    <SectionCard v-if="dryRunOutput" padded glass>
      <template #header>
        <div class="card-title-row">
          <h3 class="card-title">Dry-run result</h3>
          <span class="muted">No resources changed</span>
        </div>
      </template>
      <pre class="dry-run">{{ dryRunOutput }}</pre>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 1fr 1.4fr;
  gap: 14px;
}
@media (max-width: 1000px) {
  .layout { grid-template-columns: 1fr; }
}

.form-stack { display: flex; flex-direction: column; gap: 12px; }
.ns-row { display: flex; gap: 10px; align-items: flex-end; }

.card-title { margin: 0; font-size: 14px; font-weight: 600; }
.card-title-row { display: flex; justify-content: space-between; align-items: center; width: 100%; }
.muted { color: var(--fg-3); font-size: 12px; }

.dry-run {
  margin: 0;
  padding: 14px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-1);
  white-space: pre;
  overflow: auto;
  max-height: 380px;
}
.live-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; font-size: 12.5px; }
.dim { color: var(--fg-3); }
.dim.small { font-size: 11.5px; }
</style>

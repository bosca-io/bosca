<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const nsFilter = useK8sNamespaceFilter()

const clusterId = computed(() => current.value?.id)
const { data: opData, status: opStatus, refresh: refreshOps } = useK8sOperators(clusterId)
const { data: crData, status: crStatus, refresh: refreshCrs } = useK8sCustomResources({ cluster: clusterId })
const OPERATORS = computed(() => opData.value ?? [])
const CUSTOM_RESOURCES = computed(() => crData.value ?? [])
const isLoading = computed(() => opStatus.value === 'pending' || crStatus.value === 'pending')

function refreshAll() {
  refreshOps(); refreshCrs()
}

// Live refresh — CRD installs/removals update the operators tab, and
// each operator's instance kinds (group-qualified so same-named kinds
// across groups resolve to the right CRD) keep the instances tab
// live. The kind list re-derives — and the watch re-subscribes — as
// operators data loads.
const watchKinds = computed(() => [
  'CustomResourceDefinition',
  ...Array.from(new Set(OPERATORS.value.flatMap(o => o.crds.map(k => `${o.group}/${k}`)))),
])
useK8sResourceWatch({
  cluster: clusterId,
  kinds: watchKinds,
  onChange: refreshAll,
})

type Tab = 'operators' | 'instances'
const tab = ref<Tab>('operators')
const tabs = computed(() => ['Operators', 'Resource Instances'])
const tabToVal: Record<string, Tab> = { 'Operators': 'operators', 'Resource Instances': 'instances' }
const valToTab: Record<Tab, string> = { operators: 'Operators', instances: 'Resource Instances' }

const search = ref('')
const group = ref<string>('all')

const groupOptions = computed(() => [
  { label: 'All API groups', value: 'all' },
  ...Array.from(new Set(OPERATORS.value.map(o => o.group))).sort().map(g => ({ label: g, value: g })),
])

const filteredOperators = computed(() => OPERATORS.value.filter(o => {
  if (group.value !== 'all' && o.group !== group.value) return false
  if (search.value && !`${o.name} ${o.desc} ${o.group}`.toLowerCase().includes(search.value.toLowerCase())) return false
  return true
}))

const filteredCrs = computed(() => CUSTOM_RESOURCES.value.filter(r => {
  // CRD instances can be cluster-scoped (ns === '' on the wire); only
  // apply the namespace filter when the instance is namespaced.
  if (r.ns && !nsFilter.matches(r.ns)) return false
  if (group.value !== 'all' && r.group !== group.value) return false
  if (search.value && !`${r.name} ${r.kind} ${r.detail}`.toLowerCase().includes(search.value.toLowerCase())) return false
  return true
}))

const operatorCols: GlassTableColumn[] = [
  { key: 'name', label: 'Operator', width: 'minmax(200px, 1.5fr)' },
  { key: 'version', label: 'Version', width: '90px' },
  { key: 'group', label: 'API group', width: 'minmax(200px, 1.5fr)', muted: true },
  { key: 'ns', label: 'Namespace', width: '140px', muted: true },
  { key: 'status', label: 'Status', width: '110px' },
  { key: 'instances', label: 'Instances', width: '90px', align: 'right' },
  { key: 'crds', label: 'Provides', width: 'minmax(240px, 2fr)' },
]

const crCols: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 1.5fr)' },
  { key: 'kind', label: 'Kind', width: '160px' },
  { key: 'group', label: 'API group', width: 'minmax(220px, 1.5fr)', muted: true },
  { key: 'ns', label: 'Namespace', width: '130px', muted: true },
  { key: 'status', label: 'Status', width: '100px' },
  { key: 'detail', label: 'Detail', width: 'minmax(280px, 2fr)', muted: true },
  { key: 'age', label: 'Age', width: '70px', muted: true },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Custom Resources')"
        title="Custom Resources"
        :subtitle="`${OPERATORS.length} operators · ${CUSTOM_RESOURCES.length} resource instances`"
        :tabs="tabs"
        :active-tab="valToTab[tab]"
        @tab="(v) => tab = tabToVal[v] || 'operators'"
      >
        <template #actions>
          <Button
            icon="refresh"
            size="sm"
            :disabled="isLoading"
            @click="refreshAll">Refresh</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div class="filters">
      <SearchInput v-model="search" placeholder="Filter…" class="search" />
      <Select v-model="group" :options="groupOptions" />
      <K8sNamespaceFilter v-if="tab === 'instances'" />
      <span class="spacer" />
    </div>

    <SectionCard v-if="tab === 'operators'" glass>
      <GlassTable
        :columns="operatorCols"
        :rows="filteredOperators"
        row-key="id"
        :loading="opStatus === 'pending'"
        loading-text="Loading operators…"
        empty-text="No operators match.">
        <template #col-name="{ row }">
          <span class="mono name">{{ row.name }}</span>
          <div class="dim sub">{{ row.desc }}</div>
        </template>
        <template #col-version="{ row }"><span class="mono">v{{ row.version }}</span></template>
        <template #col-group="{ row }"><span class="mono">{{ row.group }}</span></template>
        <template #col-status="{ row }">
          <K8sStatusBadge :status="row.status === 'ok' ? 'Healthy' : row.status === 'warn' ? 'Degraded' : 'Failing'" :pulse="row.status !== 'ok'" />
        </template>
        <template #col-crds="{ row }">
          <div class="crd-pills">
            <span v-for="c in (row.crds as string[]).slice(0, 4)" :key="c" class="crd-pill mono">{{ c }}</span>
            <span v-if="(row.crds as string[]).length > 4" class="crd-pill more">+{{ (row.crds as string[]).length - 4 }}</span>
          </div>
        </template>
      </GlassTable>
    </SectionCard>

    <SectionCard v-else glass>
      <GlassTable
        :columns="crCols"
        :rows="filteredCrs"
        row-key="id"
        :loading="crStatus === 'pending'"
        loading-text="Loading resource instances…"
        empty-text="No resource instances match.">
        <template #col-name="{ row }"><span class="mono name">{{ row.name }}</span></template>
        <template #col-kind="{ row }">
          <Badge color="#a78bff">{{ row.kind }}</Badge>
        </template>
        <template #col-group="{ row }"><span class="mono">{{ row.group }}</span></template>
        <template #col-status="{ row }">
          <K8sStatusBadge :status="row.status === 'ok' ? 'Healthy' : row.status === 'warn' ? 'Degraded' : 'Failing'" :pulse="row.status !== 'ok'" />
        </template>
      </GlassTable>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.filters { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.search { flex: 1; min-width: 240px; max-width: 360px; }
.spacer { flex: 1; }
.name { font-weight: 500; font-size: 12.5px; }
.sub { font-size: 11px; margin-top: 2px; }
.mono { font-family: var(--font-mono); font-size: 12px; font-variant-numeric: tabular-nums; }
.dim { color: var(--fg-3); }

.crd-pills { display: flex; flex-wrap: wrap; gap: 4px; }
.crd-pill {
  display: inline-flex;
  align-items: center;
  padding: 1px 6px;
  border: 1px solid var(--line);
  border-radius: 4px;
  font-size: 10.5px;
  color: var(--fg-2);
  background: var(--bg-2);
}
.crd-pill.more { color: var(--fg-3); }
</style>

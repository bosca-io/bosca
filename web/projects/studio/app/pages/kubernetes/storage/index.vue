<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const nsFilter = useK8sNamespaceFilter()

const clusterId = computed(() => current.value?.id)
const { data: pvcData, status: pvcStatus, refresh: refreshPvcs } = useK8sPvcs({ cluster: clusterId })
const { data: scData, status: scStatus, refresh: refreshSc } = useK8sStorageClasses(clusterId)
const PVCS = computed(() => pvcData.value ?? [])
const STORAGE_CLASSES = computed(() => scData.value ?? [])
const isLoading = computed(() => pvcStatus.value === 'pending' || scStatus.value === 'pending')

function refreshAll() {
  refreshPvcs()
  refreshSc()
}

// Live refresh — PVC binding transitions and StorageClass changes
// re-run the list queries for both tabs.
useK8sResourceWatch({
  cluster: clusterId,
  kinds: ['PersistentVolumeClaim', 'StorageClass'],
  onChange: refreshAll,
})

type Tab = 'pvcs' | 'sc'
const tab = ref<Tab>('pvcs')
const tabs = computed(() => ['Persistent Volume Claims', 'Storage Classes'])
const tabToVal: Record<string, Tab> = { 'Persistent Volume Claims': 'pvcs', 'Storage Classes': 'sc' }
const valToTab: Record<Tab, string> = { pvcs: 'Persistent Volume Claims', sc: 'Storage Classes' }

const search = ref('')

const filteredPvcs = computed(() => PVCS.value.filter(p => nsFilter.matches(p.ns) && (!search.value || p.name.toLowerCase().includes(search.value.toLowerCase()))))
const filteredSc = computed(() => STORAGE_CLASSES.value.filter(s => !search.value || s.name.toLowerCase().includes(search.value.toLowerCase())))

const pvcCols: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 1.5fr)' },
  { key: 'ns', label: 'Namespace', width: '120px', muted: true },
  { key: 'status', label: 'Status', width: '110px' },
  { key: 'capacity', label: 'Capacity', width: '130px' },
  { key: 'access', label: 'Access', width: '80px' },
  { key: 'storageClass', label: 'Storage class', width: '140px', muted: true },
  // Fixed width keeps the usage bar's percent label next to its bar
  // instead of drifting under the next column's header.
  { key: 'used', label: 'Used', width: '150px' },
  { key: 'workload', label: 'Workload', width: 'minmax(140px, 1fr)', muted: true },
  { key: 'age', label: 'Age', width: '70px', muted: true },
]

const scCols: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 1.2fr)' },
  { key: 'provisioner', label: 'Provisioner', width: 'minmax(220px, 1.5fr)' },
  { key: 'reclaim', label: 'Reclaim', width: '100px' },
  { key: 'binding', label: 'Binding', width: '170px' },
  { key: 'default', label: 'Default', width: '90px' },
  { key: 'params', label: 'Parameters', width: 'minmax(220px, 2fr)', muted: true },
  { key: 'age', label: 'Age', width: '70px', muted: true },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Storage')"
        title="Storage"
        :subtitle="`${PVCS.length} PVCs · ${STORAGE_CLASSES.length} storage classes`"
        :tabs="tabs"
        :active-tab="valToTab[tab]"
        @tab="(v) => tab = tabToVal[v] || 'pvcs'"
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
      <SearchInput v-model="search" :placeholder="tab === 'pvcs' ? 'Filter PVCs…' : 'Filter storage classes…'" class="search" />
      <K8sNamespaceFilter v-if="tab === 'pvcs'" />
      <span class="spacer" />
    </div>

    <SectionCard v-if="tab === 'pvcs'" glass>
      <GlassTable
        :columns="pvcCols"
        :rows="filteredPvcs"
        row-key="id"
        :loading="pvcStatus === 'pending'"
        loading-text="Loading PVCs…"
        empty-text="No PVCs match the filters.">
        <template #col-name="{ row }"><span class="mono name">{{ row.name }}</span></template>
        <template #col-status="{ row }">
          <K8sStatusBadge :status="row.status" :pulse="row.status !== 'Bound'" />
        </template>
        <template #col-capacity="{ row }"><span class="mono">{{ row.capacity }}</span></template>
        <template #col-used="{ row }"><K8sUsageBar :pct="row.usedPct" /></template>
      </GlassTable>
    </SectionCard>

    <SectionCard v-else glass>
      <GlassTable
        :columns="scCols"
        :rows="filteredSc"
        row-key="name"
        :arrow="false"
        :loading="scStatus === 'pending'"
        loading-text="Loading storage classes…"
        empty-text="No storage classes.">
        <template #col-name="{ row }"><span class="mono name">{{ row.name }}</span></template>
        <template #col-provisioner="{ row }"><span class="mono dim">{{ row.provisioner }}</span></template>
        <template #col-default="{ row }">
          <Badge v-if="row.default" color="#34d99a">default</Badge>
          <span v-else class="dim">—</span>
        </template>
        <template #col-params="{ row }"><span class="mono dim">{{ row.params }}</span></template>
      </GlassTable>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.filters { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.search { flex: 1; min-width: 240px; max-width: 360px; }
.spacer { flex: 1; }
.name { font-weight: 500; font-size: 12px; }
.mono { font-family: var(--font-mono); font-size: 12px; font-variant-numeric: tabular-nums; }
.dim { color: var(--fg-3); }
</style>

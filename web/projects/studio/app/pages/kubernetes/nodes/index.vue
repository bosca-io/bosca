<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GlassTableColumn } from '@bosca/ui'
import type { K8sNode, NodeRole } from '~/composables/useK8sTypes'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()

const search = ref('')
const roleFilter = ref<'all' | NodeRole>('all')
const zoneFilter = ref('all')

const { data: nodesData, status: queryStatus, refresh } = useK8sNodes(() => current.value?.id)
// Live CPU + memory per node, keyed by name. UsageBar reads the
// allocatable-relative percent, which matches what the one-shot
// nodes query already returns.
const { byName: liveNodeByName } = useK8sNodesListMetricsStream({
  cluster: () => current.value?.id,
})
// Live refresh — node joins / cordons / drains / removals re-run the
// list query; the metrics stream above keeps CPU + memory live.
useK8sResourceWatch({
  cluster: () => current.value?.id,
  kinds: ['Node'],
  onChange: refresh,
})
const NODES = computed<K8sNode[]>(() => (nodesData.value ?? []).map((n) => {
  const live = liveNodeByName.value[n.name]
  if (!live) return n
  return { ...n, cpu: live.cpuPercent, mem: live.memoryPercent }
}))
const isLoading = computed(() => queryStatus.value === 'pending')
const zones = computed(() => Array.from(new Set(NODES.value.map(n => n.zone))).sort())

const filtered = computed<K8sNode[]>(() => NODES.value.filter((n) => {
  if (roleFilter.value !== 'all' && n.role !== roleFilter.value) return false
  if (zoneFilter.value !== 'all' && n.zone !== zoneFilter.value) return false
  if (search.value && !`${n.name} ${n.instance}`.toLowerCase().includes(search.value.toLowerCase())) return false
  return true
}))

const readyCount = computed(() => NODES.value.filter(n => n.status === 'Ready').length)
const issueCount = computed(() => NODES.value.length - readyCount.value)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(260px, 2fr)' },
  { key: 'role', label: 'Role', width: '120px' },
  { key: 'instance', label: 'Instance', width: '120px', muted: true },
  { key: 'zone', label: 'Zone', width: '110px', muted: true },
  { key: 'status', label: 'Status', width: '130px' },
  // Fixed widths: a 1fr track lets the usage bar stretch until its percent
  // label sits under the next column's header.
  { key: 'cpu', label: 'CPU', width: '150px' },
  { key: 'mem', label: 'Memory', width: '150px' },
  { key: 'pods', label: 'Pods', width: '60px', align: 'right' },
  { key: 'version', label: 'Version', width: '90px', muted: true },
  { key: 'age', label: 'Age', width: '60px', muted: true },
]

const zoneOptions = computed(() => [
  { label: 'All zones', value: 'all' },
  ...zones.value.map(z => ({ label: z, value: z })),
])
const roleOptions: { label: string; value: 'all' | NodeRole }[] = [
  { label: 'All roles', value: 'all' },
  { label: 'Control plane', value: 'control-plane' },
  { label: 'Worker', value: 'worker' },
]

const route = useRoute()
const router = useRouter()
const drawerName = ref<string | null>((route.query.open as string | undefined) ?? null)
const drawerNode = computed<K8sNode | null>(
  () => drawerName.value ? NODES.value.find(n => n.name === drawerName.value) ?? null : null,
)

function openDrawer(n: K8sNode) {
  drawerName.value = n.name
  router.replace({ query: { ...route.query, open: n.name } })
}
function closeDrawer() {
  drawerName.value = null
  const rest = Object.fromEntries(Object.entries(route.query).filter(([k]) => k !== 'open'))
  router.replace({ query: rest })
}

function onRowClick(row: K8sNode) {
  openDrawer(row)
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Nodes')"
        title="Nodes"
        :subtitle="`${NODES.length} nodes · ${readyCount} ready · ${issueCount} with issues`"
      >
        <template #actions>
          <Button
            icon="refresh"
            size="sm"
            :disabled="isLoading"
            @click="refresh()">Refresh</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div class="filters">
      <SearchInput v-model="search" placeholder="Filter nodes…" class="search" />
      <Select v-model="roleFilter" :options="roleOptions" />
      <Select v-model="zoneFilter" :options="zoneOptions" />
      <span class="spacer" />
      <span class="muted">{{ filtered.length }} of {{ NODES.length }}</span>
    </div>

    <SectionCard glass>
      <GlassTable
        :columns="columns"
        :rows="filtered.map(n => ({ id: n.name, ...n }))"
        row-key="id"
        :loading="isLoading"
        loading-text="Loading nodes…"
        empty-text="No nodes match the current filters."
        @row-click="onRowClick"
      >
        <template #col-name="{ row }">
          <span class="mono name">{{ row.name }}</span>
        </template>
        <template #col-role="{ row }">
          <Badge :color="row.role === 'control-plane' ? '#a78bff' : '#5ec5ff'">{{ row.role }}</Badge>
        </template>
        <template #col-status="{ row }">
          <K8sStatusBadge :status="row.status" :pulse="row.status !== 'Ready'" />
        </template>
        <template #col-cpu="{ row }">
          <K8sUsageBar :pct="row.cpu" />
        </template>
        <template #col-mem="{ row }">
          <K8sUsageBar :pct="row.mem" />
        </template>
        <template #col-pods="{ row }">
          <span class="mono">{{ row.pods }}</span>
        </template>
      </GlassTable>
    </SectionCard>

    <NodeDrawer
      :node="drawerNode"
      @close="closeDrawer"
    />
  </PageShell>
</template>

<style scoped>
.filters { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.search { flex: 1; min-width: 240px; max-width: 360px; }
.spacer { flex: 1; }
.muted { color: var(--fg-3); font-size: 12px; }

.name { font-weight: 500; font-size: 12px; }
.mono { font-family: var(--font-mono); font-size: 12px; font-variant-numeric: tabular-nums; }
</style>

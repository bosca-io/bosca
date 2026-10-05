<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GlassTableColumn } from '@bosca/ui'
import type { K8sPodWithWorkload } from '~/composables/useK8sTypes'
import type { DeleteResourceTarget } from '~/components/kubernetes/DeleteResourceModal.vue'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const nsFilter = useK8sNamespaceFilter()

const search = ref('')
const statusFilter = ref<'all' | 'running' | 'failing'>('all')

const { data: podsPage, status: queryStatus, refresh } = useK8sPods({
  cluster: () => current.value?.id,
})
// Live CPU + memory keyed by pod UID. Snapshot wire format — each
// tick replaces the lookup wholesale (pods that left the cluster
// also leave the overlay), so a row falls back to the one-shot fetch
// values until the first sample arrives.
const { byKey: liveByKey } = useK8sPodsListMetricsStream({
  cluster: () => current.value?.id,
})
// Live refresh — pod add / status change / delete re-runs the list
// query so rows appear and disappear without a manual refresh; the
// metrics stream above keeps CPU + memory live in between.
useK8sResourceWatch({
  cluster: () => current.value?.id,
  kinds: ['Pod'],
  onChange: refresh,
})
const pods = computed<K8sPodWithWorkload[]>(() => (podsPage.value?.items ?? []).map((p) => {
  const live = liveByKey.value[`${p.workload.ns}/${p.name}`]
  if (!live) return p
  return { ...p, cpu: live.cpuMillicores, mem: Math.round(live.memoryBytes / (1024 * 1024)) }
}))
const isLoading = computed(() => queryStatus.value === 'pending')

const filtered = computed(() => pods.value.filter((p) => {
  if (!nsFilter.matches(p.workload.ns)) return false
  if (statusFilter.value === 'running' && p.status !== 'Running') return false
  if (statusFilter.value === 'failing' && p.status === 'Running') return false
  if (search.value && !p.name.toLowerCase().includes(search.value.toLowerCase())) return false
  return true
}))

const runningCount = computed(() => pods.value.filter(p => p.status === 'Running').length)
const failingCount = computed(() => pods.value.length - runningCount.value)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Pod', width: 'minmax(260px, 2fr)' },
  { key: 'status', label: 'Status', width: '140px' },
  { key: 'ready', label: 'Ready', width: '70px', align: 'right' },
  { key: 'workload', label: 'Workload', width: 'minmax(150px, 1fr)' },
  { key: 'ns', label: 'Namespace', width: '110px', muted: true },
  { key: 'node', label: 'Node', width: 'minmax(180px, 1fr)', muted: true },
  { key: 'cpu', label: 'CPU', width: '70px', align: 'right' },
  { key: 'mem', label: 'Memory', width: '80px', align: 'right' },
  { key: 'restarts', label: 'Restarts', width: '70px', align: 'right' },
  { key: 'age', label: 'Age', width: '60px', muted: true },
]

const route = useRoute()
const router = useRouter()
const drawerName = ref<string | null>((route.query.open as string | undefined) ?? null)
const deleteTarget = ref<DeleteResourceTarget | null>(null)

function onDelete(p: K8sPodWithWorkload) {
  deleteTarget.value = {
    displayKind: 'Pod',
    kind: 'Pod',
    name: p.name,
    namespace: p.workload.ns,
  }
}

function onDeleted() {
  deleteTarget.value = null
  void refresh()
}
const drawerPod = computed<K8sPodWithWorkload | null>(
  () => drawerName.value ? pods.value.find(p => p.name === drawerName.value) ?? null : null,
)

function openDrawer(p: K8sPodWithWorkload) {
  drawerName.value = p.name
  router.replace({ query: { ...route.query, open: p.name } })
}
function closeDrawer() {
  drawerName.value = null
  const rest = Object.fromEntries(Object.entries(route.query).filter(([k]) => k !== 'open'))
  router.replace({ query: rest })
}

function onRowClick(row: Record<string, unknown>) {
  const flat = row as { id: string; workload: string; ns: string }
  const target = pods.value.find(p => p.name === flat.id)
  if (target) openDrawer(target)
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Pods')"
        title="Pods"
        :subtitle="`${pods.length} pods · ${runningCount} running · ${failingCount} not running`"
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
      <SearchInput v-model="search" placeholder="Filter pods…" class="search" />
      <K8sNamespaceFilter />
      <div class="chips">
        <button
          v-for="s in (['all', 'running', 'failing'] as const)"
          :key="s"
          class="chip"
          :class="{ on: statusFilter === s }"
          @click="statusFilter = s">{{ s }}</button>
      </div>
      <span class="spacer" />
      <span class="muted">{{ filtered.length }} of {{ pods.length }}</span>
    </div>

    <SectionCard glass>
      <GlassTable
        :columns="columns"
        :rows="filtered.map(p => ({
          id: p.name,
          name: p.name,
          status: p.status,
          ready: p.ready,
          workload: p.workload.name,
          ns: p.workload.ns,
          node: p.node,
          cpu: p.cpu,
          mem: p.mem,
          restarts: p.restarts,
          age: p.age,
        }))"
        row-key="id"
        :loading="isLoading"
        loading-text="Loading pods…"
        empty-text="No pods match the current filters."
        @row-click="onRowClick"
      >
        <template #col-name="{ row }">
          <span class="mono name">{{ row.name }}</span>
        </template>
        <template #col-status="{ row }">
          <K8sStatusBadge :status="row.status" :pulse="row.status !== 'Running'" />
        </template>
        <template #col-ready="{ row }">
          <span class="mono">{{ row.ready }}</span>
        </template>
        <template #col-cpu="{ row }">
          <span class="mono">{{ row.cpu }}m</span>
        </template>
        <template #col-mem="{ row }">
          <span class="mono">{{ row.mem }}Mi</span>
        </template>
        <template #col-restarts="{ row }">
          <span class="mono" :style="{ color: row.restarts > 0 ? 'var(--warn)' : 'var(--fg-3)' }">{{ row.restarts }}</span>
        </template>
      </GlassTable>
    </SectionCard>

    <PodDrawer
      :pod="drawerPod"
      @close="closeDrawer"
      @delete="onDelete"
    />

    <DeleteResourceModal
      :resource="deleteTarget"
      @close="deleteTarget = null"
      @deleted="onDeleted" />
  </PageShell>
</template>

<style scoped>
.filters { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.search { flex: 1; min-width: 240px; max-width: 360px; }
.spacer { flex: 1; }
.muted { color: var(--fg-3); font-size: 12px; }

.chips {
  display: inline-flex;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 2px;
}
.chip {
  padding: 5px 10px;
  font-size: 12px;
  font-weight: 500;
  background: transparent;
  border: none;
  border-radius: 6px;
  color: var(--fg-3);
  cursor: pointer;
  text-transform: capitalize;
}
.chip.on { background: var(--bg-3); color: var(--fg-0); }

.name { font-weight: 500; font-size: 12px; }
.mono { font-family: var(--font-mono); font-size: 12px; font-variant-numeric: tabular-nums; }
</style>

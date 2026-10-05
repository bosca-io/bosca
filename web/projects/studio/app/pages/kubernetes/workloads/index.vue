<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GlassTableColumn, OverflowMenuItem } from '@bosca/ui'
import type { K8sWorkload, WorkloadKind } from '~/composables/useK8sTypes'
import { toBackendKind } from '~/composables/useK8sMutations'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const nsFilter = useK8sNamespaceFilter()
const toast = useToast()
const clusterIdRef = computed(() => current.value?.id)
const mutations = useK8sMutations({ cluster: clusterIdRef })

// Mutation modal state — single set of refs covers scale/restart/delete
// because only one of the three is open at a time.
const scaleTarget = ref<K8sWorkload | null>(null)
const scaleReplicas = ref(0)
const scaleSubmitting = ref(false)
const restartTarget = ref<K8sWorkload | null>(null)
const restartSubmitting = ref(false)
const deleteTarget = ref<K8sWorkload | null>(null)
const deleteSubmitting = ref(false)

function openScale(w: K8sWorkload) {
  scaleTarget.value = w
  scaleReplicas.value = w.want
}

function openRestart(w: K8sWorkload) {
  restartTarget.value = w
}

function openDelete(w: K8sWorkload) {
  deleteTarget.value = w
}

async function confirmScale() {
  const target = scaleTarget.value
  if (!target) return
  const kind = toBackendKind(target.kind)
  if (!kind) { toast.error(`Scale not supported for ${target.kind}`); return }
  scaleSubmitting.value = true
  try {
    await mutations.scaleWorkload({
      namespace: target.ns,
      kind,
      name: target.name,
      replicas: scaleReplicas.value,
    })
    toast.success(`Scaled ${target.name} to ${scaleReplicas.value} replicas`)
    scaleTarget.value = null
    await refresh()
  } catch (err: unknown) {
    toast.error(err instanceof Error ? err.message : 'Scale failed')
  } finally {
    scaleSubmitting.value = false
  }
}

async function confirmRestart() {
  const target = restartTarget.value
  if (!target) return
  const kind = toBackendKind(target.kind)
  if (!kind) { toast.error(`Restart not supported for ${target.kind}`); return }
  restartSubmitting.value = true
  try {
    await mutations.restartWorkload({
      namespace: target.ns,
      kind,
      name: target.name,
    })
    toast.success(`Restart triggered for ${target.name}`)
    restartTarget.value = null
  } catch (err: unknown) {
    toast.error(err instanceof Error ? err.message : 'Restart failed')
  } finally {
    restartSubmitting.value = false
  }
}

async function confirmDelete() {
  const target = deleteTarget.value
  if (!target) return
  deleteSubmitting.value = true
  try {
    await mutations.deleteResource({
      kind: target.kind,           // Deployment/StatefulSet/... as raw Kind string
      name: target.name,
      namespace: target.ns,
      group: kindGroup(target.kind),
    })
    toast.success(`Deleted ${target.name}`)
    deleteTarget.value = null
    await refresh()
  } catch (err: unknown) {
    toast.error(err instanceof Error ? err.message : 'Delete failed')
  } finally {
    deleteSubmitting.value = false
  }
}

/**
 * Maps a workload Kind to its API group. Deployments / ReplicaSets /
 * StatefulSets / DaemonSets live under `apps`; Jobs / CronJobs under
 * `batch`. The backend defaults `group` to "" (core) when omitted,
 * which is wrong for these kinds.
 */
function kindGroup(kind: string): string {
  switch (kind) {
    case 'Job':
    case 'CronJob':
      return 'batch'
    default:
      return 'apps'
  }
}

const search = ref('')
const kind = ref<'all' | WorkloadKind>('all')
const status = ref<'all' | 'ok' | 'pending' | 'warn' | 'err'>('all')

const { data: workloadsData, status: queryStatus, refresh } = useK8sWorkloads({
  cluster: () => current.value?.id,
})
// Live per-workload aggregates keyed by workload UID. Server-side
// aggregation walks ReplicaSet→Deployment and Job→CronJob so a
// Deployment row sees the sum across its ReplicaSets — matches the
// on-demand `workloads` query's units (cores, GiB).
const { byId: liveWorkloadById } = useK8sWorkloadsListMetricsStream({
  cluster: () => current.value?.id,
})
// Live refresh — workload create/scale/rollout/delete re-runs the
// list query, keeping replicas / status / age realtime alongside the
// streamed CPU + memory values above.
useK8sResourceWatch({
  cluster: () => current.value?.id,
  kinds: ['Deployment', 'StatefulSet', 'DaemonSet', 'ReplicaSet', 'Job', 'CronJob'],
  onChange: refresh,
})
const WORKLOADS = computed<K8sWorkload[]>(() => (workloadsData.value ?? []).map((w) => {
  const live = liveWorkloadById.value[w.id]
  if (!live) return w
  return { ...w, cpu: live.cpuCores, mem: live.memoryGiB, restarts: live.restarts }
}))
const isLoading = computed(() => queryStatus.value === 'pending')

const filtered = computed(() => WORKLOADS.value.filter((w) => {
  if (!nsFilter.matches(w.ns)) return false
  if (kind.value !== 'all' && w.kind !== kind.value) return false
  if (status.value !== 'all' && w.status !== status.value) return false
  if (search.value && !`${w.name} ${w.image}`.toLowerCase().includes(search.value.toLowerCase())) return false
  return true
}))

const kindOptions: { label: string; value: 'all' | WorkloadKind }[] = [
  { label: 'All kinds', value: 'all' },
  { label: 'Deployment', value: 'Deployment' },
  { label: 'StatefulSet', value: 'StatefulSet' },
  { label: 'DaemonSet', value: 'DaemonSet' },
  { label: 'ReplicaSet', value: 'ReplicaSet' },
  { label: 'Job', value: 'Job' },
  { label: 'CronJob', value: 'CronJob' },
]

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(220px, 2fr)' },
  { key: 'kind', label: 'Kind', width: '110px' },
  { key: 'ns', label: 'Namespace', width: '120px', muted: true },
  { key: 'replicas', label: 'Replicas', width: '90px', align: 'right' },
  { key: 'status', label: 'Status', width: '110px' },
  { key: 'image', label: 'Image', width: 'minmax(180px, 2fr)', muted: true },
  { key: 'cpu', label: 'CPU', width: '70px', align: 'right' },
  { key: 'mem', label: 'Memory', width: '70px', align: 'right' },
  { key: 'restarts', label: 'Restarts', width: '70px', align: 'right' },
  { key: 'age', label: 'Age', width: '70px', muted: true },
]

const rowActions = (row: K8sWorkload): OverflowMenuItem[] => {
  return [
    { id: 'view', label: 'View YAML', icon: 'code' },
    { id: 'scale', label: 'Scale', icon: 'sliders', disabled: row.kind === 'DaemonSet' || row.kind === 'CronJob' },
    { id: 'restart', label: 'Restart', icon: 'refresh' },
    { id: 'separator', separator: true, label: '' },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  ]
}

function onAction({ action, row }: { action: string; row: K8sWorkload }) {
  switch (action) {
    case 'restart': openRestart(row); break
    case 'scale': openScale(row); break
    case 'delete': openDelete(row); break
    case 'view': openDrawer(row); break
  }
}

const applyOpen = ref(false)

const route = useRoute()
const router = useRouter()
const drawerId = ref<string | null>((route.query.open as string | undefined) ?? null)
const drawerWorkload = computed<K8sWorkload | null>(
  () => drawerId.value ? WORKLOADS.value.find(w => w.id === drawerId.value) ?? null : null,
)

function openDrawer(w: K8sWorkload) {
  drawerId.value = w.id
  router.replace({ query: { ...route.query, open: w.id } })
}
function closeDrawer() {
  drawerId.value = null
  const rest = Object.fromEntries(Object.entries(route.query).filter(([k]) => k !== 'open'))
  router.replace({ query: rest })
}

function onRowClick(row: K8sWorkload) {
  openDrawer(row)
}

const statusLabel: Record<K8sWorkload['status'], string> = {
  ok: 'Healthy', pending: 'Pending', warn: 'Degraded', err: 'Failing',
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Workloads')"
        title="Workloads"
        :subtitle="`${WORKLOADS.length} workloads · ${WORKLOADS.filter(w => w.status !== 'ok').length} need attention`"
      >
        <template #actions>
          <Button
            icon="refresh"
            size="sm"
            :disabled="isLoading"
            @click="refresh()">Refresh</Button>
          <Button
            primary
            icon="code"
            size="sm"
            :accent="accent"
            @click="applyOpen = true">Apply</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div class="filters">
      <SearchInput v-model="search" placeholder="Filter workloads…" class="search" />
      <K8sNamespaceFilter />
      <Select v-model="kind" :options="kindOptions" />
      <div class="chips">
        <button
          v-for="s in (['all', 'ok', 'pending', 'warn', 'err'] as const)"
          :key="s"
          class="chip"
          :class="{ on: status === s }"
          @click="status = s">
          {{ s === 'all' ? 'All' : statusLabel[s] }}
        </button>
      </div>
      <span class="spacer" />
      <span class="muted">{{ filtered.length }} of {{ WORKLOADS.length }}</span>
    </div>

    <SectionCard glass>
      <GlassTable
        :columns="columns"
        :rows="filtered"
        row-key="id"
        :row-actions="rowActions"
        :loading="isLoading"
        loading-text="Loading workloads…"
        empty-text="No workloads match the current filters."
        @row-click="onRowClick"
        @row-action="onAction"
      >
        <template #col-name="{ row }">
          <span class="name mono">{{ row.name }}</span>
        </template>
        <template #col-kind="{ row }">
          <Badge :color="row.kind === 'Deployment' ? '#5ec5ff' : row.kind === 'StatefulSet' ? '#a78bff' : row.kind === 'DaemonSet' ? '#34d99a' : '#ffb547'">{{ row.kind }}</Badge>
        </template>
        <template #col-replicas="{ row }">
          <span class="mono">{{ row.replicas }}</span>
        </template>
        <template #col-status="{ row }">
          <K8sStatusBadge :status="statusLabel[row.status as K8sWorkload['status']]" :pulse="row.status !== 'ok'" />
        </template>
        <template #col-cpu="{ row }">
          <span class="mono">{{ row.cpu }}</span>
        </template>
        <template #col-mem="{ row }">
          <span class="mono">{{ row.mem }}Gi</span>
        </template>
        <template #col-restarts="{ row }">
          <span class="mono" :style="{ color: row.restarts > 0 ? 'var(--warn)' : 'var(--fg-3)' }">{{ row.restarts }}</span>
        </template>
      </GlassTable>
    </SectionCard>

    <WorkloadDrawer
      :workload="drawerWorkload"
      @close="closeDrawer"
      @restart="openRestart"
      @scale="openScale"
      @delete="openDelete"
    />

    <Modal v-if="scaleTarget" title="Scale workload" @close="scaleTarget = null">
      <div class="modal-body">
        <p class="modal-text">
          Scale <strong>{{ scaleTarget.name }}</strong> in
          <strong>{{ scaleTarget.ns }}</strong>.
          Current desired: {{ scaleTarget.want }}, ready: {{ scaleTarget.ready }}.
        </p>
        <label class="replicas-label">
          Replicas
          <input
            v-model.number="scaleReplicas"
            type="number"
            min="0"
            class="replicas-input"
            :disabled="scaleSubmitting"
          >
        </label>
      </div>
      <template #footer>
        <Button size="sm" :disabled="scaleSubmitting" @click="scaleTarget = null">Cancel</Button>
        <Button
          size="sm"
          variant="primary"
          :disabled="scaleSubmitting || scaleReplicas < 0"
          @click="confirmScale">
          {{ scaleSubmitting ? 'Scaling…' : 'Scale' }}
        </Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="restartTarget"
      title="Trigger rolling restart"
      :subtitle="`Stamps the rollout annotation on ${restartTarget.name}. Pods will restart one at a time.`"
      confirm-label="Restart"
      :loading="restartSubmitting"
      @close="restartTarget = null"
      @confirm="confirmRestart"
    />

    <ConfirmModal
      v-if="deleteTarget"
      title="Delete workload"
      :subtitle="`Deletes ${deleteTarget.kind}/${deleteTarget.name} in ${deleteTarget.ns}. This cannot be undone.`"
      confirm-label="Delete"
      :loading="deleteSubmitting"
      @close="deleteTarget = null"
      @confirm="confirmDelete"
    />

    <ApplyManifestModal
      v-if="applyOpen"
      :accent="accent"
      @close="applyOpen = false"
      @applied="refresh()" />
  </PageShell>
</template>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.search { flex: 1; min-width: 240px; max-width: 360px; }
.spacer { flex: 1; }
.muted { color: var(--fg-3); font-size: 12px; }

.modal-body { padding: 4px 0 12px; }
.modal-text { color: var(--fg-2); margin: 0 0 16px; font-size: 13px; }
.replicas-label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.replicas-input {
  width: 120px;
  padding: 8px 10px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 6px;
  color: var(--fg-1);
  font-family: var(--font-mono);
  font-size: 14px;
}
.replicas-input:focus { outline: 2px solid var(--brand); outline-offset: -2px; }

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
}
.chip.on { background: var(--bg-3); color: var(--fg-0); }

.name { font-weight: 500; }
.mono { font-family: var(--font-mono); font-size: 12.5px; font-variant-numeric: tabular-nums; }
</style>

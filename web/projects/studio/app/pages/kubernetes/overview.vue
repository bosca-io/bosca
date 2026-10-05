<script setup lang="ts">
import { computed, ref } from 'vue'
import type { EventLevel, K8sEvent, NodeRole, NodeStatus, WorkloadStatus } from '~/composables/useK8sTypes'
import type { K8sEventStreamItem } from '~/composables/useK8sMetricsStream'

const { accent } = useCurrentSubsystem()
const applyOpen = ref(false)
const { clusters, current, setCluster } = useK8sCluster()

const clusterIdRef = computed(() => current.value?.id)
const { data: nodesData, refresh: refreshNodes } = useK8sNodes(clusterIdRef)
const { data: workloadsData, refresh: refreshWorkloads } = useK8sWorkloads({ cluster: clusterIdRef })
const { data: eventsData } = useK8sEvents({ cluster: clusterIdRef, limit: 8 })

// Live refresh of the base node / workload lists — the streams below
// overlay live values onto known rows, but rows appearing or leaving
// (node joins, deploys, deletes) need the list queries re-run.
useK8sResourceWatch({
  cluster: clusterIdRef,
  kinds: ['Node', 'Deployment', 'StatefulSet', 'DaemonSet', 'ReplicaSet', 'Job', 'CronJob'],
  onChange: () => { refreshNodes(); refreshWorkloads() },
})

// Live per-node values feed the fleet grid + per-pool capacity tiles
// and — since the node list stream now also carries readiness — keep
// the Nodes-ready tile, cluster-health donut, and checklist live. The
// cluster-wide stream drives the headline CPU / Memory / Pods tiles,
// and the workloads-status stream keeps the namespace badges live.
const { byName: liveNodeByName } = useK8sNodesListMetricsStream({
  cluster: clusterIdRef,
})
const { byId: liveWorkloadStatusById } = useK8sWorkloadsStatusListStream({
  cluster: clusterIdRef,
})
const NODES = computed(() => (nodesData.value ?? []).map((n) => {
  const live = liveNodeByName.value[n.name]
  if (!live) return n
  return {
    ...n,
    cpu: live.cpuPercent,
    mem: live.memoryPercent,
    status: (live.status as NodeStatus),
    role: (live.role === 'control-plane' ? 'control-plane' : 'worker') as NodeRole,
  }
}))
// Backend WorkloadStatus enum (OK/PENDING/WARN/ERROR) → studio badge union.
const WORKLOAD_STATUS_MAP: Record<'OK' | 'PENDING' | 'WARN' | 'ERROR', WorkloadStatus> = {
  OK: 'ok',
  PENDING: 'pending',
  WARN: 'warn',
  ERROR: 'err',
}
const WORKLOADS = computed(() => (workloadsData.value ?? []).map((w) => {
  const live = liveWorkloadStatusById.value[w.id]
  if (!live) return w
  return { ...w, status: WORKLOAD_STATUS_MAP[live.status] ?? w.status }
}))

const controlNodes = computed(() => NODES.value.filter(n => n.role === 'control-plane'))
const workerNodes = computed(() => NODES.value.filter(n => n.role === 'worker'))
const readyNodes = computed(() => NODES.value.filter(n => n.status === 'Ready').length)
const unreadyWorkers = computed(() => workerNodes.value.filter(n => n.status !== 'Ready'))

// Control-plane health is reported from the readiness of the
// control-plane-role nodes the cluster exposes. Managed clusters
// (EKS / GKE / AKS) don't surface their control-plane nodes at all, so
// `controlNodes` is empty there — that's not a failure, it's a
// provider-managed plane we can't observe. We render that case as a
// neutral "not exposed" rather than a misleading warning.
const controlPlaneVisible = computed(() => controlNodes.value.length > 0)
const controlPlaneReady = computed(() => controlNodes.value.filter(n => n.status === 'Ready').length)
const controlPlaneOk = computed(() => controlPlaneVisible.value
  && controlPlaneReady.value === controlNodes.value.length)
const controlPlaneNote = computed(() => controlPlaneVisible.value
  ? `${controlPlaneReady.value} / ${controlNodes.value.length} ready`
  : 'Managed by provider — nodes not exposed')

// Live cluster-wide CPU + memory percentage from
// metrics.k8s.io, streamed every 5s. Falls back to the per-node
// average (each node's individual % from the on-demand nodes
// query) when the stream hasn't produced its first sample yet —
// keeps the tile from showing 0 during the first interval.
const { latest: liveClusterMetrics } = useK8sClusterMetricsStream({
  cluster: () => current.value?.id,
})
const avgCpu = computed(() => {
  if (liveClusterMetrics.value) return liveClusterMetrics.value.cpuPercent
  if (!workerNodes.value.length) return 0
  return Math.round(workerNodes.value.reduce((s, n) => s + n.cpu, 0) / workerNodes.value.length)
})
const avgMem = computed(() => {
  if (liveClusterMetrics.value) return liveClusterMetrics.value.memoryPercent
  if (!workerNodes.value.length) return 0
  return Math.round(workerNodes.value.reduce((s, n) => s + n.mem, 0) / workerNodes.value.length)
})

// The cluster-wide metrics stream already carries a live pod count on
// every tick, so the "Pods running" tile tracks the same WebSocket as
// the CPU / Memory tiles. Falls back to the one-shot cluster summary
// (`current.pods`) until the stream produces its first sample.
const livePods = computed(() => liveClusterMetrics.value?.podCount ?? current.value?.pods ?? 0)

const nodeFoot = computed(() => {
  const cp = controlNodes.value.length
  const w = workerNodes.value.length
  if (cp === 0 && w === 0) return '—'
  return `${cp} control · ${w} worker`
})

const workloadsByNamespace = computed(() => {
  const groups = new Map<string, typeof WORKLOADS.value>()
  for (const w of WORKLOADS.value) {
    const ns = w.ns || 'default'
    if (!groups.has(ns)) groups.set(ns, [])
    groups.get(ns)!.push(w)
  }
  return Array.from(groups.entries())
    .map(([ns, items]) => ({ ns, items }))
    .sort((a, b) => a.ns.localeCompare(b.ns))
})

// Live events ride the `k8sEvents` subscription. We seed from the
// on-demand query (history) and prepend streamed events as they
// arrive, deduping by a stable composite key — the seed maps the
// opaque event id to a numeric one, so an id can't be matched across
// both sources.
const { samples: liveEventSamples } = useK8sEventsStream({ cluster: clusterIdRef })
const EVENT_LEVEL_MAP: Record<string, EventLevel> = { INFO: 'info', WARN: 'warn', ERROR: 'error' }
function mapStreamEvent(e: K8sEventStreamItem, idx: number): K8sEvent {
  const numericId = Number.parseInt(e.id.replace(/\D/g, '').slice(0, 9), 10)
  return {
    id: Number.isFinite(numericId) && numericId > 0 ? numericId : idx + 1,
    lvl: EVENT_LEVEL_MAP[e.level] ?? 'info',
    when: e.when,
    ns: e.namespace,
    obj: e.involvedObject,
    msg: e.message,
    reason: e.reason,
  }
}
const recentEvents = computed<K8sEvent[]>(() => {
  // The buffer appends newest-last; reverse so streamed events lead.
  const streamed = [...(liveEventSamples.value ?? [])].reverse().map(mapStreamEvent)
  const seed = eventsData.value ?? []
  const seen = new Set<string>()
  const merged: K8sEvent[] = []
  for (const e of [...streamed, ...seed]) {
    const key = `${e.when}|${e.ns}|${e.obj}|${e.reason}|${e.msg}`
    if (seen.has(key)) continue
    seen.add(key)
    merged.push(e)
  }
  return merged.slice(0, 8)
})

const healthLabel = computed(() => {
  if (!current.value) return 'Unknown'
  return current.value.health === 'ok' ? 'Healthy' : current.value.health === 'warn' ? 'Degraded' : 'Failing'
})
const healthPct = computed(() => {
  if (NODES.value.length === 0) return 0
  return Math.round((readyNodes.value / NODES.value.length) * 100)
})
const healthColor = computed(() => {
  if (!current.value) return 'var(--fg-3)'
  return current.value.health === 'ok' ? 'var(--ok)' : current.value.health === 'warn' ? 'var(--warn)' : 'var(--err)'
})

function openWorkload(name: string) {
  navigateTo(`/kubernetes/workloads?open=${encodeURIComponent(name)}`)
}

function workloadStatusLabel(status: string): 'Healthy' | 'Pending' | 'Degraded' | 'Failing' {
  if (status === 'err') return 'Failing'
  if (status === 'warn') return 'Degraded'
  if (status === 'pending') return 'Pending'
  return 'Healthy'
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Overview')"
        :title="current?.name || 'Overview'"
      >
        <template v-if="current" #subtitle>
          <K8sStatusBadge :status="healthLabel" hide-label :pulse="current.health !== 'ok'" />
          <span class="sub-text">{{ current.provider }} · {{ current.version }} · {{ current.nodes }} nodes · {{ current.pods }} pods</span>
        </template>
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="applyOpen = true">Apply manifest</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <!-- Stat tiles -->
    <div class="stat-grid">
      <K8sStatTile
        label="Nodes ready"
        :value="readyNodes"
        :unit="`/ ${NODES.length}`"
        :foot="nodeFoot"
      />
      <K8sStatTile
        label="Pods running"
        :value="livePods"
      />
      <K8sStatTile
        label="CPU usage"
        :value="avgCpu"
        unit="%"
        foot="Avg. across workers"
      />
      <K8sStatTile
        label="Memory usage"
        :value="avgMem"
        unit="%"
        foot="Avg. across workers"
      />
    </div>

    <!-- Capacity + health -->
    <div class="asym-grid">
      <SectionCard padded glass>
        <template #header>
          <div class="card-head">
            <h3>Capacity by node pool</h3>
            <div class="muted">{{ workerNodes.length }} worker nodes</div>
          </div>
        </template>
        <K8sNodePoolCapacity :nodes="NODES" />
        <div class="fleet-block">
          <div class="fleet-label">Worker fleet</div>
          <K8sNodeFleetGrid :nodes="workerNodes" />
        </div>
      </SectionCard>

      <SectionCard padded glass>
        <template #header>
          <div class="card-head">
            <h3>Cluster health</h3>
          </div>
        </template>
        <div class="health-top">
          <Donut :pct="healthPct" :size="88" :color="healthColor" />
          <div class="health-body">
            <div class="health-label">Node readiness</div>
            <div class="health-value">
              {{ readyNodes }} <span class="health-of">/ {{ NODES.length }} ready</span>
            </div>
            <div v-if="unreadyWorkers.length" class="muted small">
              {{ unreadyWorkers.length }} worker{{ unreadyWorkers.length === 1 ? '' : 's' }} not ready
            </div>
          </div>
        </div>
        <div class="divider" />
        <K8sChecklistRow
          label="Control plane"
          :ok="controlPlaneOk"
          :warn="controlPlaneVisible && !controlPlaneOk"
          :info="!controlPlaneVisible"
          :note="controlPlaneNote"
        />
        <K8sChecklistRow
          label="Worker nodes"
          :ok="workerNodes.length > 0 && unreadyWorkers.length === 0"
          :warn="unreadyWorkers.length > 0"
          :note="workerNodes.length
            ? (unreadyWorkers.length
              ? `${unreadyWorkers.map(n => n.name).join(', ')} not ready`
              : 'All workers ready')
            : 'no worker nodes observed'"
        />
      </SectionCard>
    </div>

    <!-- Workloads by namespace + events -->
    <div class="asym-grid">
      <SectionCard padded glass>
        <template #header>
          <div class="card-head">
            <h3>Workloads by namespace</h3>
            <NuxtLink to="/kubernetes/workloads" class="see-all">View all →</NuxtLink>
          </div>
        </template>
        <div v-if="!workloadsByNamespace.length" class="empty">No workloads observed yet.</div>
        <div v-else class="ns-grid">
          <div v-for="g in workloadsByNamespace" :key="g.ns" class="ns-group">
            <div class="ns-head">
              <span class="mono">ns/{{ g.ns }}</span>
              <span class="muted">{{ g.items.length }} workload{{ g.items.length === 1 ? '' : 's' }}</span>
            </div>
            <div class="ns-items">
              <Popover
                v-for="w in g.items"
                :key="w.id"
                trigger="mouseenter"
                placement="top"
                :delay="250"
              >
                <template #trigger>
                  <button class="ns-item" @click="openWorkload(w.name)">
                    <K8sStatusBadge
                      :status="workloadStatusLabel(w.status)"
                      hide-label
                      :pulse="w.status !== 'ok'"
                    />
                    <span class="item-name">{{ w.name }}</span>
                  </button>
                </template>
                <K8sWorkloadHoverCard :workload="w" />
              </Popover>
            </div>
          </div>
        </div>
      </SectionCard>

      <SectionCard padded glass>
        <template #header>
          <div class="card-head">
            <h3>Recent events</h3>
            <NuxtLink to="/kubernetes/events" class="see-all">View all →</NuxtLink>
          </div>
        </template>
        <div v-if="!recentEvents.length" class="empty">No recent events.</div>
        <div v-else class="events-scroll">
          <K8sEventRow v-for="e in recentEvents" :key="e.id" :event="e" />
        </div>
      </SectionCard>
    </div>

    <ApplyManifestModal v-if="applyOpen" :accent="accent" @close="applyOpen = false" />
  </PageShell>
</template>

<style scoped>
.sub-text { color: var(--fg-2); }

.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 14px;
}
.asym-grid {
  display: grid;
  grid-template-columns: 1.6fr 1fr;
  gap: 14px;
}
@media (max-width: 1100px) {
  .asym-grid { grid-template-columns: 1fr; }
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}
.card-head h3 { margin: 0; font-size: 14px; font-weight: 600; letter-spacing: -0.005em; }
.muted { color: var(--fg-3); font-size: 12px; }
.muted.small { font-size: 11.5px; margin-top: 2px; }
.see-all { color: var(--fg-3); font-size: 12px; text-decoration: none; }
.see-all:hover { color: var(--fg-1); }

.fleet-block { margin-top: 14px; }
.fleet-label {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--fg-3);
  margin-bottom: 8px;
}

.health-top { display: flex; align-items: center; gap: 18px; }
.health-body { flex: 1; min-width: 0; }
.health-label { font-size: 12px; color: var(--fg-3); margin-bottom: 4px; }
.health-value { font-size: 18px; font-weight: 600; letter-spacing: -0.01em; font-variant-numeric: tabular-nums; }
.health-of { font-size: 13px; font-weight: 500; color: var(--fg-3); }
.divider { height: 1px; background: var(--line); margin: 14px 0; }

.empty { color: var(--fg-3); font-size: 13px; padding: 24px 4px; text-align: center; }

.ns-grid { display: flex; flex-direction: column; gap: 14px; }
.ns-group { border: 1px solid var(--line); border-radius: var(--r-sm, 8px); padding: 10px 12px; background: var(--bg-2); }
.ns-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-bottom: 8px; }
.ns-head .mono { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.mono { font-family: var(--mono, ui-monospace, monospace); font-size: 12px; }
.ns-items { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 6px; }
.ns-items :deep(.popover-trigger) { display: flex; min-width: 0; }
.ns-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 8px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-sm, 6px);
  color: inherit;
  font: inherit;
  font-size: 12.5px;
  cursor: pointer;
  text-align: left;
}
.ns-item:hover { background: var(--bg-3); border-color: var(--line-2); }
.item-name {
  flex: 1;
  min-width: 0;
  /* Wrap long workload names to a second line before ellipsing —
     single-line ellipsis chopped most names in half. */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  overflow-wrap: anywhere;
  line-height: 1.35;
}

.events-scroll { max-height: 380px; overflow: auto; }
</style>

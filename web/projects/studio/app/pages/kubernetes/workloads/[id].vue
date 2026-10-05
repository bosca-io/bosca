<script setup lang="ts">
import { computed, ref } from 'vue'
import type { K8sWorkload } from '~/composables/useK8sTypes'
import { toBackendKind } from '~/composables/useK8sMutations'
import type { ScaleWorkloadTarget } from '~/components/kubernetes/ScaleWorkloadModal.vue'

const route = useRoute()
const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const toast = useToast()

const clusterIdRef = computed(() => current.value?.id)
const id = computed(() => route.params.id as string)

const { data: workloadsData, refresh: refreshWorkloads } = useK8sWorkloads({ cluster: clusterIdRef })
const workloadSnapshot = computed<K8sWorkload | undefined>(
  () => (workloadsData.value ?? []).find(w => w.id === id.value),
)

// Overlay live aggregates onto the cached workload — CPU / memory /
// restarts tick every 5s while the page is open. Falls back to the
// one-shot snapshot until the first sample arrives.
const { byId: liveWorkloadById } = useK8sWorkloadsListMetricsStream({
  cluster: clusterIdRef,
})
const workload = computed<K8sWorkload | undefined>(() => {
  const w = workloadSnapshot.value
  if (!w) return undefined
  const live = liveWorkloadById.value[w.id]
  if (!live) return w
  return { ...w, cpu: live.cpuCores, mem: live.memoryGiB, restarts: live.restarts }
})

const { data: podsPage, refresh: refreshPods } = useK8sPods({
  cluster: clusterIdRef,
  workloadId: id,
})
// Live refresh — workload spec/status changes and pod churn in this
// workload's namespace re-run the detail + pods queries. The metrics
// streams above keep CPU / memory ticking in between.
useK8sResourceWatch({
  cluster: clusterIdRef,
  namespace: () => workloadSnapshot.value?.ns,
  kinds: ['Deployment', 'StatefulSet', 'DaemonSet', 'ReplicaSet', 'Job', 'CronJob', 'Pod'],
  onChange: () => { refreshWorkloads(); refreshPods() },
})
// Live per-pod values for the Pods tab — merge by namespace/name as
// the cluster-wide pods page does. The workload-scoped pods query
// already filters server-side (walking ownership chain) so the live
// stream's cluster-wide view is fine to overlay.
const { byKey: livePodByKey } = useK8sPodsListMetricsStream({
  cluster: clusterIdRef,
  namespace: () => workloadSnapshot.value?.ns,
})
const pods = computed(() => (podsPage.value?.items ?? []).map((p) => {
  const live = livePodByKey.value[`${p.workload.ns}/${p.name}`]
  if (!live) return p
  return { ...p, cpu: live.cpuMillicores, mem: Math.round(live.memoryBytes / (1024 * 1024)) }
}))

const mutations = useK8sMutations({ cluster: clusterIdRef })

/**
 * Workload kinds split across two API groups: Job/CronJob live under
 * `batch`, the rest under `apps`. The yaml(...) query needs the right
 * group to find the resource.
 */
function kindGroup(kind: K8sWorkload['kind']): string {
  return kind === 'Job' || kind === 'CronJob' ? 'batch' : 'apps'
}

async function restart() {
  if (!workload.value) return
  const backendKind = toBackendKind(workload.value.kind)
  if (!backendKind) {
    toast.error(`Restart not supported for ${workload.value.kind}`)
    return
  }
  try {
    await mutations.restartWorkload({
      namespace: workload.value.ns,
      kind: backendKind,
      name: workload.value.name,
    })
    toast.success(`Restart triggered for ${workload.value.name}`)
    await refreshWorkloads()
  } catch (err: unknown) {
    toast.error(err instanceof Error ? err.message : 'Restart failed')
  }
}

const scaleTarget = ref<ScaleWorkloadTarget | null>(null)
function openScale() {
  const w = workload.value
  if (!w) return
  scaleTarget.value = {
    kind: w.kind,
    name: w.name,
    namespace: w.ns,
    currentReplicas: w.want,
  }
}
function onScaled() {
  scaleTarget.value = null
  void refreshWorkloads()
}

const tabs = ['Overview', 'Pods', 'Logs', 'Events', 'Manifest']
const tab = ref('Overview')

const { data: manifestYaml, status: manifestStatus } = useK8sResourceYaml({
  cluster: clusterIdRef,
  kind: () => workload.value?.kind ?? null,
  name: () => workload.value?.name ?? null,
  namespace: () => workload.value?.ns ?? null,
  group: () => workload.value ? kindGroup(workload.value.kind) : null,
})
const isManifestLoading = computed(() => manifestStatus.value === 'pending')

// Merged log tail across every pod that belongs to this workload.
// The kubernetes-controller fans out one watch per pod and funnels
// every line into the single subscription, so a Deployment with 20
// replicas still costs one studio-side WebSocket.
const logFollow = ref(true)
const workloadNamespaceRef = computed(() => workload.value?.ns ?? null)
const workloadKindRef = computed(() => workload.value ? toBackendKind(workload.value.kind) : null)
const workloadNameRef = computed(() => workload.value?.name ?? null)
const supportsLogs = computed(() => workload.value?.kind !== 'CronJob')
const {
  lines: logLines,
  paused: logsPaused,
  status: logStatus,
  togglePause: toggleLogPause,
  clear: clearLogs,
} = useK8sWorkloadLogs({
  cluster: clusterIdRef,
  namespace: workloadNamespaceRef,
  kind: workloadKindRef,
  name: workloadNameRef,
  follow: logFollow,
})

const statusLabel: Record<K8sWorkload['status'], string> = { ok: 'Healthy', pending: 'Pending', warn: 'Degraded', err: 'Failing' }

function logStatusLabel(status: string): string {
  switch (status) {
    case 'streaming': return 'Live'
    case 'connecting': return 'Connecting…'
    case 'reconnecting': return 'Reconnecting…'
    case 'error': return 'Stream error'
    case 'closed': return 'Closed'
    default: return 'Idle'
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="[{ label: 'Kubernetes', to: '/kubernetes/overview' }, { label: 'Workloads', to: '/kubernetes/workloads' }, workload?.name || id]"
        :title="workload?.name || id"
        :tabs="tabs"
        :active-tab="tab"
        @tab="(v) => tab = v"
      >
        <template v-if="workload" #subtitle>
          <K8sStatusBadge :status="statusLabel[workload.status]" :pulse="workload.status !== 'ok'" />
          <span class="sub-text">{{ workload.kind }} · {{ workload.ns }}</span>
        </template>
        <template #actions>
          <Button size="sm" icon="refresh" @click="restart">Restart</Button>
          <Button
            primary
            size="sm"
            icon="maximize"
            :accent="accent"
            @click="openScale">Scale</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div v-if="!workload" class="empty">Workload not found.</div>

    <template v-else>
      <div v-if="tab === 'Overview'" class="grid">
        <SectionCard padded glass>
          <template #header><h3 class="card-h">Properties</h3></template>
          <div class="kv">
            <div class="k">Kind</div><div class="v">{{ workload.kind }}</div>
            <div class="k">Namespace</div><div class="v mono">{{ workload.ns }}</div>
            <div class="k">Image</div><div class="v mono break">{{ workload.image }}</div>
            <div class="k">Strategy</div><div class="v">{{ workload.strategy }}</div>
            <div class="k">Replicas</div><div class="v mono">{{ workload.replicas }}</div>
            <div class="k">Restarts</div><div class="v mono">{{ workload.restarts }}</div>
            <div class="k">Age</div><div class="v">{{ workload.age }}</div>
          </div>
        </SectionCard>
        <SectionCard padded glass>
          <template #header><h3 class="card-h">Resources</h3></template>
          <div class="kv">
            <div class="k">CPU</div><div class="v mono">{{ workload.cpu }} cores</div>
            <div class="k">Memory</div><div class="v mono">{{ workload.mem }} GiB</div>
            <div class="k">Pods ready</div><div class="v mono">{{ workload.ready }} / {{ workload.want }}</div>
          </div>
        </SectionCard>
      </div>

      <SectionCard v-else-if="tab === 'Pods'" padded glass>
        <div v-if="pods.length === 0" class="empty">No pods yet.</div>
        <div v-else class="pods-list">
          <div
            v-for="p in pods"
            :key="p.name"
            class="pod-row"
            @click="navigateTo(`/kubernetes/pods/${encodeURIComponent(p.name)}`)">
            <span class="mono name">{{ p.name }}</span>
            <K8sStatusBadge :status="p.status" :pulse="p.status !== 'Running'" />
            <span class="mono dim">{{ p.node }}</span>
            <span class="mono">CPU {{ p.cpu }}m</span>
            <span class="mono">Mem {{ p.mem }}Mi</span>
            <span class="mono dim">{{ p.age }}</span>
          </div>
        </div>
      </SectionCard>

      <SectionCard v-else-if="tab === 'Logs'" padded glass>
        <template #header>
          <div class="logs-head">
            <h3 class="card-h">Logs</h3>
            <div class="logs-meta">
              <span v-if="!supportsLogs" class="muted">Logs are not supported for CronJobs — open a child Job's pod to view its logs.</span>
              <template v-else>
                <span class="muted">{{ logStatusLabel(logStatus) }} · {{ pods.length }} pod{{ pods.length === 1 ? '' : 's' }} · merged tail</span>
                <Button size="sm" :icon="logFollow ? 'pause' : 'play'" @click="logFollow = !logFollow">
                  {{ logFollow ? 'Pause follow' : 'Resume follow' }}
                </Button>
                <Button size="sm" :icon="logsPaused ? 'play' : 'pause'" @click="toggleLogPause()">
                  {{ logsPaused ? 'Resume render' : 'Pause render' }}
                </Button>
                <Button size="sm" icon="x" @click="clearLogs()">Clear</Button>
              </template>
            </div>
          </div>
        </template>
        <div v-if="!supportsLogs" class="empty">Pick a Job from the Pods tab to view its logs.</div>
        <LogViewer
          v-else
          :lines="logLines"
          max-height="540px"
          :default-follow="logFollow" />
      </SectionCard>

      <SectionCard v-else-if="tab === 'Events'" padded glass>
        <div class="empty">Event filtering by workload lands when the controller is wired.</div>
      </SectionCard>

      <SectionCard v-else-if="tab === 'Manifest'" padded glass>
        <div v-if="isManifestLoading" class="empty">Loading manifest…</div>
        <CodeEditor
          v-else
          :model-value="manifestYaml"
          language="text"
          :rows="28"
          readonly />
      </SectionCard>
    </template>

    <ScaleWorkloadModal
      :target="scaleTarget"
      :accent="accent"
      @close="scaleTarget = null"
      @scaled="onScaled" />
  </PageShell>
</template>

<style scoped>
.sub-text { color: var(--fg-2); }

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 14px;
}
.card-h { margin: 0; font-size: 14px; font-weight: 600; }
.kv { display: grid; grid-template-columns: 120px 1fr; row-gap: 8px; column-gap: 14px; font-size: 12.5px; }
.k { color: var(--fg-3); font-size: 11.5px; text-transform: uppercase; letter-spacing: 0.06em; padding-top: 2px; }
.v { color: var(--fg-1); }
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.dim { color: var(--fg-3); }

.logs-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  width: 100%;
}
.logs-meta { display: inline-flex; align-items: center; gap: 8px; }
.muted { color: var(--fg-3); font-size: 12px; }
.break { word-break: break-all; }

.empty { padding: 32px; text-align: center; color: var(--fg-3); }

.pods-list { display: flex; flex-direction: column; }
.pod-row {
  display: grid;
  grid-template-columns: 1.8fr 120px 1.4fr auto auto auto;
  gap: 12px;
  align-items: center;
  padding: 9px 10px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
}
.pod-row:hover { background: var(--bg-2); }
.pod-row .name { font-weight: 500; }
</style>

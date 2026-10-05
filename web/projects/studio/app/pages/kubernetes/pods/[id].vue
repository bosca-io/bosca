<script setup lang="ts">
import { computed, ref } from 'vue'
import type { K8sPodWithWorkload } from '~/composables/useK8sTypes'
import type { DeleteResourceTarget } from '~/components/kubernetes/DeleteResourceModal.vue'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()

const id = computed(() => decodeURIComponent(route.params.id as string))
const clusterIdForQuery = computed(() => current.value?.id)

const { data: podsPage, refresh: refreshPod } = useK8sPods({
  cluster: clusterIdForQuery,
  search: id,
})
const pod = computed<K8sPodWithWorkload | undefined>(
  () => (podsPage.value?.items ?? []).find(p => p.name === id.value),
)

// Live refresh — phase / readiness / restart-count changes on the pod
// re-run the query (the logs + metrics streams below stay live on
// their own sockets).
useK8sResourceWatch({
  cluster: clusterIdForQuery,
  namespace: () => pod.value?.workload.ns,
  kinds: ['Pod'],
  onChange: () => { refreshPod(); refreshManifest() },
})

const tabs = ['Overview', 'Logs', 'Events', 'Manifest']
const tab = ref('Overview')

// Live log stream — subscribes to the kubernetes podLogs GraphQL
// subscription. The composable handles WebSocket lifecycle, auto-
// reconnect at the controller's 5-min stream cap, and a bounded ring
// buffer of recent lines. Identifiers come from the cluster switcher
// + the loaded pod row; the watch inside the composable re-subscribes
// when any of them change.
const follow = ref(true)
const clusterIdRef = computed(() => current.value?.id)
const namespaceRef = computed(() => pod.value?.workload.ns ?? null)
const podNameRef = computed(() => pod.value?.name ?? null)
const containerRef = computed<string | null>(() => null)
const {
  lines: logLines,
  paused: logsPaused,
  status: logStatus,
  togglePause: toggleLogPause,
  clear: clearLogs,
} = useK8sPodLogs({
  cluster: clusterIdRef,
  namespace: namespaceRef,
  pod: podNameRef,
  container: containerRef,
  follow,
})

const { data: manifestYaml, status: manifestStatus, refresh: refreshManifest } = useK8sResourceYaml({
  cluster: clusterIdRef,
  kind: () => 'Pod',
  name: () => pod.value?.name ?? null,
  namespace: () => pod.value?.workload.ns ?? null,
})
const isManifestLoading = computed(() => manifestStatus.value === 'pending')

// Live CPU + memory stream. The subscription opens once the pod
// metadata has loaded; the studio renders the latest sample
// directly so the user sees usage tick every interval rather than
// the stale value from the initial pod query.
const podMetricsNamespaceRef = computed(() => pod.value?.workload.ns ?? undefined)
const podMetricsNameRef = computed(() => pod.value?.name ?? undefined)
const { latest: liveMetrics } = useK8sPodMetricsStream({
  cluster: clusterIdRef,
  namespace: podMetricsNamespaceRef,
  pod: podMetricsNameRef,
})
const liveCpuMillicores = computed(() => liveMetrics.value?.cpuMillicores ?? pod.value?.cpu ?? 0)
const liveMemoryMiB = computed(() => {
  const bytes = liveMetrics.value?.memoryBytes
  if (bytes != null) return Math.round(bytes / (1024 * 1024))
  return pod.value?.mem ?? 0
})

const deleteTarget = ref<DeleteResourceTarget | null>(null)
function openDelete() {
  const p = pod.value
  if (!p) return
  deleteTarget.value = {
    displayKind: 'Pod',
    kind: 'Pod',
    name: p.name,
    namespace: p.workload.ns,
  }
}
function onDeleted() {
  deleteTarget.value = null
  router.replace('/kubernetes/pods')
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="[{ label: 'Kubernetes', to: '/kubernetes/overview' }, { label: 'Pods', to: '/kubernetes/pods' }, pod?.name || id]"
        :title="pod?.name || id"
        :tabs="tabs"
        :active-tab="tab"
        @tab="(v) => tab = v"
      >
        <template v-if="pod" #title>
          <span class="mono">{{ pod.name }}</span>
        </template>
        <template v-if="pod" #subtitle>
          <K8sStatusBadge :status="pod.status" :pulse="pod.status !== 'Running'" />
          <span class="sub-text">{{ pod.workload.ns }} · {{ pod.workload.kind }}/{{ pod.workload.name }}</span>
        </template>
        <template #actions>
          <Button size="sm" icon="trash" @click="openDelete">Delete</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div v-if="!pod" class="empty">Pod not found.</div>

    <template v-else>
      <div v-if="tab === 'Overview'" class="grid">
        <SectionCard padded glass>
          <template #header><h3 class="card-h">Properties</h3></template>
          <div class="kv">
            <div class="k">Namespace</div><div class="v mono">{{ pod.workload.ns }}</div>
            <div class="k">Workload</div><div class="v mono">{{ pod.workload.kind }}/{{ pod.workload.name }}</div>
            <div class="k">Node</div><div class="v mono break">{{ pod.node }}</div>
            <div class="k">Phase</div><div class="v">{{ pod.status }}</div>
            <div class="k">Ready</div><div class="v mono">{{ pod.ready }}</div>
            <div class="k">Restarts</div><div class="v mono">{{ pod.restarts }}</div>
            <div class="k">Age</div><div class="v">{{ pod.age }}</div>
            <div class="k">Image</div><div class="v mono break">{{ pod.workload.image }}</div>
          </div>
        </SectionCard>
        <SectionCard padded glass>
          <template #header><h3 class="card-h">Resource usage</h3></template>
          <div class="usage-row"><span class="lbl">CPU</span><span class="mono">{{ liveCpuMillicores }}m</span></div>
          <div class="usage-row"><span class="lbl">Memory</span><span class="mono">{{ liveMemoryMiB }}Mi</span></div>
        </SectionCard>
      </div>

      <SectionCard
        v-else-if="tab === 'Logs'"
        padded
        glass
        title="Logs">
        <template #right>
          <span class="log-status" :class="`log-status--${logStatus}`">
            {{ logStatus === 'streaming' ? 'streaming'
              : logStatus === 'reconnecting' ? 'reconnecting…'
                : logStatus === 'connecting' ? 'connecting…'
                  : logStatus === 'error' ? 'error'
                    : logStatus === 'closed' ? 'closed'
                      : 'idle' }}
          </span>
          <Button size="sm" :icon="logsPaused ? 'play' : 'pause'" @click="toggleLogPause()">
            {{ logsPaused ? 'Resume' : 'Pause' }}
          </Button>
          <Button size="sm" icon="x" @click="clearLogs()">Clear</Button>
        </template>
        <LogViewer :lines="logLines" max-height="520px" :default-follow="follow" />
      </SectionCard>

      <SectionCard v-else-if="tab === 'Events'" padded glass>
        <div class="empty">Pod-scoped events land when the controller is wired.</div>
      </SectionCard>

      <SectionCard v-else-if="tab === 'Manifest'" padded glass>
        <div v-if="isManifestLoading" class="empty">Loading manifest…</div>
        <CodeEditor
          v-else
          :model-value="manifestYaml"
          language="text"
          :rows="26"
          readonly />
      </SectionCard>
    </template>

    <DeleteResourceModal
      :resource="deleteTarget"
      @close="deleteTarget = null"
      @deleted="onDeleted" />
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
.break { word-break: break-all; }
.empty { padding: 32px; text-align: center; color: var(--fg-3); }

.usage-row { display: flex; justify-content: space-between; padding: 6px 0; font-size: 12.5px; border-bottom: 1px solid var(--line); }
.usage-row:last-child { border-bottom: none; }
.lbl { color: var(--fg-3); }

.log-status {
  font-size: 11px;
  color: var(--fg-3);
  margin-right: 8px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  font-variant-numeric: tabular-nums;
}
.log-status--streaming { color: var(--ok, #16a34a); }
.log-status--reconnecting { color: var(--warn, #d97706); }
.log-status--error { color: var(--err, #dc2626); }
</style>

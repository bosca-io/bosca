<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GlassTableColumn } from '@bosca/ui'
import type { K8sCnpgCluster } from '~/composables/useK8sTypes'

const route = useRoute()
const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()

const id = computed(() => route.params.name as string)
const clusterId = computed(() => current.value?.id)

// The route carries only the cluster name. The list query gives us the
// summary row and — crucially — the namespace the detail query needs.
const { data: cnpgData, status: listStatus } = useK8sCnpgClusters({ cluster: clusterId })
const summary = computed<K8sCnpgCluster | undefined>(
  () => (cnpgData.value ?? []).find(c => c.name === id.value),
)
const namespace = computed(() => summary.value?.namespace)

// Full detail (instances, backups, parameters, storage). Waits for the
// namespace to resolve, then fires; returns null when the cluster is gone.
const { data: detailData, status: detailStatus, refresh: refreshDetail } = useK8sCnpgCluster({
  cluster: clusterId,
  namespace,
  name: id,
})

// Live refresh — instance role changes (switchover / failover), pod
// churn, and backup completions re-run the detail query.
useK8sResourceWatch({
  cluster: clusterId,
  namespace,
  kinds: ['CnpgCluster', 'CnpgBackup', 'Pod'],
  onChange: () => { refreshDetail(); refreshManifest() },
})
const detail = computed(() => detailData.value)
// Prefer the detail's own summary once it arrives, else the list row.
const cnpg = computed<K8sCnpgCluster | undefined>(() => detailData.value?.cluster ?? summary.value)
const detailLoading = computed(() => detailStatus.value === 'pending')
const loading = computed(() => !cnpg.value && (listStatus.value === 'pending' || detailStatus.value === 'pending'))

const tabs = ['Topology', 'Backups', 'Parameters', 'Manifest']
const tab = ref('Topology')

const instances = computed(() => detail.value?.instances ?? [])
const backups = computed(() => detail.value?.backups ?? [])
const parameters = computed(() => detail.value?.parameters ?? [])

// Real Cluster CRD YAML, fetched only when the Manifest tab is open and
// the namespace is known (gating the cluster id defers the query).
const { data: manifestYaml, refresh: refreshManifest } = useK8sResourceYaml({
  cluster: computed(() => (tab.value === 'Manifest' && namespace.value ? clusterId.value : undefined)),
  namespace,
  kind: 'Cluster',
  group: 'postgresql.cnpg.io',
  name: id,
})

const instCols: GlassTableColumn[] = [
  { key: 'name', label: 'Instance', width: 'minmax(180px, 1.3fr)' },
  { key: 'role', label: 'Role', width: '110px' },
  { key: 'status', label: 'Status', width: '150px' },
  { key: 'node', label: 'Node', width: 'minmax(200px, 1.4fr)', muted: true },
  { key: 'zone', label: 'Zone', width: '120px', muted: true },
  { key: 'pvcSize', label: 'Volume', width: '100px', align: 'right' },
  { key: 'restarts', label: 'Restarts', width: '90px', align: 'right' },
  { key: 'age', label: 'Age', width: '80px', muted: true },
]

const backupCols: GlassTableColumn[] = [
  { key: 'name', label: 'Backup', width: 'minmax(220px, 1.5fr)' },
  { key: 'method', label: 'Method', width: 'minmax(180px, 1.2fr)' },
  { key: 'started', label: 'Started', width: '120px', muted: true },
  { key: 'completed', label: 'Completed', width: '130px', muted: true },
  { key: 'duration', label: 'Duration', width: '110px', align: 'right' },
  { key: 'status', label: 'Status', width: '130px' },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="[{ label: 'Kubernetes', to: '/kubernetes/overview' }, { label: 'CloudNativePG', to: '/kubernetes/cnpg' }, cnpg?.name || id]"
        :title="cnpg?.name || id"
        :tabs="tabs"
        :active-tab="tab"
        @tab="(v) => tab = v"
      >
        <template v-if="cnpg" #subtitle>
          <K8sStatusBadge :status="cnpg.statusKind === 'ok' ? 'Healthy' : 'Degraded'" :pulse="cnpg.statusKind !== 'ok'" />
          <span class="sub-text">{{ cnpg.namespace }} · PG {{ cnpg.pgVersion }} · {{ cnpg.instances }} instances</span>
        </template>
        <template #actions>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div v-if="loading" class="empty">Loading CNPG cluster…</div>
    <div v-else-if="!cnpg" class="empty">CNPG cluster not found.</div>

    <template v-else>
      <div v-if="tab === 'Topology'">
        <div class="summary">
          <SectionCard padded glass>
            <template #header><h3 class="card-h">Cluster</h3></template>
            <div class="kv">
              <div class="k">Namespace</div><div class="v mono">{{ cnpg.namespace }}</div>
              <div class="k">PG version</div><div class="v mono">{{ cnpg.pgVersion || '—' }}</div>
              <div class="k">Image</div><div class="v mono break">{{ cnpg.image || '—' }}</div>
              <div class="k">Instances</div><div class="v mono">{{ cnpg.instances }}</div>
              <div class="k">Primary</div><div class="v mono">{{ cnpg.primary || '—' }}</div>
              <div class="k">Storage</div><div class="v mono">{{ detail?.storageSize || '—' }}<span v-if="detail?.storageClass" class="dim"> · {{ detail.storageClass }}</span></div>
              <div class="k">Status</div><div class="v">{{ cnpg.status || '—' }}</div>
              <div class="k">Age</div><div class="v">{{ cnpg.age }}</div>
            </div>
          </SectionCard>
          <SectionCard padded glass>
            <template #header><h3 class="card-h">Backups</h3></template>
            <div class="kv">
              <div class="k">Schedule</div><div class="v mono">{{ cnpg.backupSchedule || '—' }}</div>
              <div class="k">Last backup</div><div class="v">{{ cnpg.lastBackup || '—' }}</div>
              <div class="k">Retention</div><div class="v">{{ detail?.backupRetention || '—' }}</div>
              <div class="k">Target</div><div class="v mono break">{{ detail?.backupDestinationPath || '—' }}</div>
            </div>
          </SectionCard>
        </div>

        <SectionCard glass>
          <template #header><h3 class="card-h">Instances</h3></template>
          <GlassTable
            :columns="instCols"
            :rows="instances"
            row-key="name"
            :loading="detailLoading"
            loading-text="Loading instances…"
            empty-text="No instances reported."
            :arrow="false">
            <template #col-name="{ row }"><span class="mono name">{{ row.name }}</span></template>
            <template #col-role="{ row }">
              <Badge :color="row.role === 'primary' ? '#34d99a' : '#5ec5ff'">{{ row.role || 'unknown' }}</Badge>
            </template>
            <template #col-status="{ row }"><K8sStatusBadge :status="row.status" :pulse="!row.ready && row.status !== 'Unknown'" /></template>
            <template #col-node="{ row }"><span class="mono dim">{{ row.node || '—' }}</span></template>
            <template #col-zone="{ row }"><span class="dim">{{ row.zone || '—' }}</span></template>
            <template #col-pvcSize="{ row }"><span class="mono">{{ row.pvcSize || '—' }}</span></template>
            <template #col-restarts="{ row }"><span class="mono">{{ row.restarts }}</span></template>
          </GlassTable>
        </SectionCard>
      </div>

      <SectionCard v-else-if="tab === 'Backups'" glass>
        <GlassTable
          :columns="backupCols"
          :rows="backups"
          row-key="name"
          :loading="detailLoading"
          loading-text="Loading backups…"
          empty-text="No backups found."
          :arrow="false">
          <template #col-name="{ row }"><span class="mono name">{{ row.name }}</span></template>
          <template #col-method="{ row }"><span class="mono">{{ row.method || '—' }}</span></template>
          <template #col-duration="{ row }"><span class="mono">{{ row.duration || '—' }}</span></template>
          <template #col-status="{ row }"><K8sStatusBadge :status="row.phase || 'unknown'" /></template>
        </GlassTable>
      </SectionCard>

      <SectionCard v-else-if="tab === 'Parameters'" padded glass>
        <div v-if="!parameters.length" class="empty">No tuned parameters.</div>
        <div v-else class="params">
          <div v-for="p in parameters" :key="p.key" class="param-row">
            <span class="mono dim">{{ p.key }}</span>
            <span class="mono">{{ p.value }}</span>
          </div>
        </div>
      </SectionCard>

      <SectionCard v-else-if="tab === 'Manifest'" padded glass>
        <CodeEditor
          :model-value="manifestYaml || ''"
          language="text"
          :rows="24"
          readonly />
      </SectionCard>
    </template>
  </PageShell>
</template>

<style scoped>
.sub-text { color: var(--fg-2); }
.card-h { margin: 0; font-size: 14px; font-weight: 600; }

.summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}

.kv { display: grid; grid-template-columns: 130px 1fr; row-gap: 8px; column-gap: 14px; font-size: 12.5px; }
.k { color: var(--fg-3); font-size: 11.5px; text-transform: uppercase; letter-spacing: 0.06em; padding-top: 2px; }
.v { color: var(--fg-1); }
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.break { word-break: break-all; }
.dim { color: var(--fg-3); }
.name { font-weight: 500; font-size: 12.5px; }
.empty { padding: 32px; text-align: center; color: var(--fg-3); }

.params { display: grid; grid-template-columns: 1fr; row-gap: 4px; }
.param-row {
  display: grid;
  grid-template-columns: 1fr 1.2fr;
  padding: 6px 4px;
  border-bottom: 1px solid var(--line);
  font-size: 12.5px;
}
.param-row:last-child { border-bottom: none; }
</style>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GlassTableColumn } from '@bosca/ui'
import type { K8sHpa, K8sPdb } from '~/composables/useK8sTypes'
import type { EditResourceYamlTarget } from '~/components/kubernetes/EditResourceYamlModal.vue'
import type { DeleteResourceTarget } from '~/components/kubernetes/DeleteResourceModal.vue'
import type { HpaLimitsTarget } from '~/components/kubernetes/HpaLimitsModal.vue'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const nsFilter = useK8sNamespaceFilter()

const clusterId = computed(() => current.value?.id)
const { data: hpaData, status: hpaStatus, refresh: refreshHpas } = useK8sHpas({ cluster: clusterId })
const { data: pdbData, status: pdbStatus, refresh: refreshPdbs } = useK8sPdbs({ cluster: clusterId })
const HPAS = computed(() => hpaData.value ?? [])
const PDBS = computed(() => pdbData.value ?? [])
const isLoading = computed(() => hpaStatus.value === 'pending' || pdbStatus.value === 'pending')

function refreshAll() {
  refreshHpas()
  refreshPdbs()
}

// Live refresh — autoscaler status ticks (desired-replica changes,
// condition flips) and PDB budget changes re-run both list queries.
useK8sResourceWatch({
  cluster: clusterId,
  kinds: ['HorizontalPodAutoscaler', 'PodDisruptionBudget'],
  onChange: refreshAll,
})

type Tab = 'hpas' | 'pdbs'
const tab = ref<Tab>('hpas')
const tabs = computed(() => ['Autoscalers', 'Disruption Budgets'])
const tabToVal: Record<string, Tab> = { 'Autoscalers': 'hpas', 'Disruption Budgets': 'pdbs' }
const valToTab: Record<Tab, string> = { hpas: 'Autoscalers', pdbs: 'Disruption Budgets' }

const search = ref('')
const applyOpen = ref(false)
const yamlTarget = ref<EditResourceYamlTarget | null>(null)
const deleteTarget = ref<DeleteResourceTarget | null>(null)
const limitsTarget = ref<HpaLimitsTarget | null>(null)

const filteredHpas = computed(() => HPAS.value.filter(h => nsFilter.matches(h.ns) && (!search.value || `${h.name} ${h.targetName}`.toLowerCase().includes(search.value.toLowerCase()))))
const filteredPdbs = computed(() => PDBS.value.filter(p => nsFilter.matches(p.ns) && (!search.value || `${p.name} ${p.selector}`.toLowerCase().includes(search.value.toLowerCase()))))

// ok = scaling normally; warn = clamped by bounds or metrics missing;
// err = the autoscaler cannot act at all.
function hpaStatusLabel(h: K8sHpa): 'Scaling' | 'Limited' | 'Degraded' | 'Failing' {
  if (!h.ableToScale) return 'Failing'
  if (h.scalingLimited) return 'Limited'
  if (!h.scalingActive) return 'Degraded'
  return 'Scaling'
}
function hpaPulse(h: K8sHpa): boolean {
  return !h.ableToScale || !h.scalingActive
}

function pdbStatusLabel(p: K8sPdb): 'Ready' | 'Blocked' {
  return p.disruptionsAllowed > 0 ? 'Ready' : 'Blocked'
}

function openHpaLimits(h: K8sHpa) {
  limitsTarget.value = {
    name: h.name,
    namespace: h.ns,
    minReplicas: h.minReplicas,
    maxReplicas: h.maxReplicas,
  }
}

function openHpaYaml(h: K8sHpa) {
  yamlTarget.value = {
    displayKind: 'HorizontalPodAutoscaler',
    kind: 'HorizontalPodAutoscaler',
    name: h.name,
    namespace: h.ns,
    group: 'autoscaling',
  }
}

function openHpaDelete(h: K8sHpa) {
  deleteTarget.value = {
    displayKind: 'HorizontalPodAutoscaler',
    kind: 'HorizontalPodAutoscaler',
    name: h.name,
    namespace: h.ns,
    group: 'autoscaling',
  }
}

function openPdbYaml(p: K8sPdb) {
  yamlTarget.value = {
    displayKind: 'PodDisruptionBudget',
    kind: 'PodDisruptionBudget',
    name: p.name,
    namespace: p.ns,
    group: 'policy',
  }
}

function openPdbDelete(p: K8sPdb) {
  deleteTarget.value = {
    displayKind: 'PodDisruptionBudget',
    kind: 'PodDisruptionBudget',
    name: p.name,
    namespace: p.ns,
    group: 'policy',
  }
}

const hpaCols: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 1.4fr)' },
  { key: 'ns', label: 'Namespace', width: '120px', muted: true },
  { key: 'target', label: 'Target', width: 'minmax(160px, 1.2fr)', muted: true },
  { key: 'bounds', label: 'Min–Max', width: '90px', align: 'right' },
  { key: 'replicas', label: 'Replicas', width: '90px', align: 'right' },
  { key: 'metrics', label: 'Metrics', width: 'minmax(160px, 1.2fr)' },
  { key: 'status', label: 'Status', width: '110px' },
  { key: 'age', label: 'Age', width: '70px', muted: true },
  { key: 'actions', label: '', width: '210px', align: 'right' },
]

const pdbCols: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 1.4fr)' },
  { key: 'ns', label: 'Namespace', width: '120px', muted: true },
  { key: 'budget', label: 'Budget', width: '140px' },
  { key: 'healthy', label: 'Healthy', width: '90px', align: 'right' },
  { key: 'disruptions', label: 'Disruptions', width: '100px', align: 'right' },
  { key: 'selector', label: 'Selector', width: 'minmax(180px, 1.4fr)', muted: true },
  { key: 'status', label: 'Status', width: '100px' },
  { key: 'age', label: 'Age', width: '70px', muted: true },
  { key: 'actions', label: '', width: '150px', align: 'right' },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Autoscaling & PDBs')"
        title="Autoscaling & PDBs"
        :subtitle="`${HPAS.length} autoscalers · ${PDBS.length} disruption budgets`"
        :tabs="tabs"
        :active-tab="valToTab[tab]"
        @tab="(v) => tab = tabToVal[v] || 'hpas'"
      >
        <template #actions>
          <Button
            icon="refresh"
            size="sm"
            :disabled="isLoading"
            @click="refreshAll">Refresh</Button>
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

    <div class="filters">
      <SearchInput v-model="search" :placeholder="tab === 'hpas' ? 'Filter autoscalers…' : 'Filter disruption budgets…'" class="search" />
      <K8sNamespaceFilter />
      <span class="spacer" />
    </div>

    <SectionCard v-if="tab === 'hpas'" glass>
      <GlassTable
        :columns="hpaCols"
        :rows="filteredHpas"
        row-key="id"
        :arrow="false"
        :loading="hpaStatus === 'pending'"
        loading-text="Loading autoscalers…"
        empty-text="No HorizontalPodAutoscalers match the filters.">
        <template #col-name="{ row }"><span class="mono name">{{ row.name }}</span></template>
        <template #col-target="{ row }"><span class="mono dim">{{ row.targetKind }}/{{ row.targetName }}</span></template>
        <template #col-bounds="{ row }"><span class="mono">{{ row.minReplicas }}–{{ row.maxReplicas }}</span></template>
        <template #col-replicas="{ row }">
          <span class="mono">{{ row.currentReplicas }}<template v-if="row.desiredReplicas !== row.currentReplicas"> → {{ row.desiredReplicas }}</template></span>
        </template>
        <template #col-metrics="{ row }">
          <div class="metric-list">
            <span v-for="m in row.metrics" :key="m.label" class="mono metric">
              {{ m.label }}: {{ m.current ?? '—' }} / {{ m.target }}
            </span>
            <span v-if="!row.metrics.length" class="dim">—</span>
          </div>
        </template>
        <template #col-status="{ row }">
          <K8sStatusBadge :status="hpaStatusLabel(row)" :pulse="hpaPulse(row)" />
        </template>
        <template #col-actions="{ row }">
          <div class="row-actions">
            <Button size="sm" icon="maximize" @click.stop="openHpaLimits(row)">Limits</Button>
            <Button size="sm" icon="code" @click.stop="openHpaYaml(row)">YAML</Button>
            <Button size="sm" icon="trash" @click.stop="openHpaDelete(row)" />
          </div>
        </template>
      </GlassTable>
    </SectionCard>

    <SectionCard v-else glass>
      <GlassTable
        :columns="pdbCols"
        :rows="filteredPdbs"
        row-key="id"
        :arrow="false"
        :loading="pdbStatus === 'pending'"
        loading-text="Loading disruption budgets…"
        empty-text="No PodDisruptionBudgets match the filters.">
        <template #col-name="{ row }"><span class="mono name">{{ row.name }}</span></template>
        <template #col-budget="{ row }">
          <span class="mono">
            <template v-if="row.minAvailable != null">min {{ row.minAvailable }}</template>
            <template v-else-if="row.maxUnavailable != null">max unavail {{ row.maxUnavailable }}</template>
            <template v-else>—</template>
          </span>
        </template>
        <template #col-healthy="{ row }"><span class="mono">{{ row.currentHealthy }}/{{ row.desiredHealthy }}</span></template>
        <template #col-disruptions="{ row }"><span class="mono">{{ row.disruptionsAllowed }}</span></template>
        <template #col-selector="{ row }"><span class="mono dim">{{ row.selector }}</span></template>
        <template #col-status="{ row }">
          <K8sStatusBadge :status="pdbStatusLabel(row)" :pulse="row.disruptionsAllowed === 0" />
        </template>
        <template #col-actions="{ row }">
          <div class="row-actions">
            <Button size="sm" icon="code" @click.stop="openPdbYaml(row)">YAML</Button>
            <Button size="sm" icon="trash" @click.stop="openPdbDelete(row)" />
          </div>
        </template>
      </GlassTable>
    </SectionCard>

    <ApplyManifestModal
      v-if="applyOpen"
      :accent="accent"
      @close="applyOpen = false"
      @applied="refreshAll" />

    <EditResourceYamlModal
      :target="yamlTarget"
      :accent="accent"
      @close="yamlTarget = null"
      @applied="yamlTarget = null; refreshAll()" />

    <DeleteResourceModal
      :resource="deleteTarget"
      @close="deleteTarget = null"
      @deleted="deleteTarget = null; refreshAll()" />

    <HpaLimitsModal
      :target="limitsTarget"
      :accent="accent"
      @close="limitsTarget = null"
      @updated="limitsTarget = null; refreshHpas()" />
  </PageShell>
</template>

<style scoped>
.filters { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.search { flex: 1; min-width: 240px; max-width: 360px; }
.spacer { flex: 1; }
.name { font-weight: 500; font-size: 12px; }
.mono { font-family: var(--font-mono); font-size: 12px; font-variant-numeric: tabular-nums; }
.dim { color: var(--fg-3); }
.metric-list { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.metric { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row-actions { display: flex; justify-content: flex-end; gap: 6px; }
</style>

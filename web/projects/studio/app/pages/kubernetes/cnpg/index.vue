<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const nsFilter = useK8sNamespaceFilter()

const clusterId = computed(() => current.value?.id)
const { data: cnpgData, status: queryStatus, refresh } = useK8sCnpgClusters({ cluster: clusterId })
// Live refresh — CNPG cluster phase changes (switchovers, failovers,
// scaling) and completed backups re-run the list query.
useK8sResourceWatch({
  cluster: clusterId,
  kinds: ['CnpgCluster', 'CnpgBackup'],
  onChange: refresh,
})
const CNPG_CLUSTERS = computed(() => cnpgData.value ?? [])
const isLoading = computed(() => queryStatus.value === 'pending')

const search = ref('')
const applyOpen = ref(false)
const filtered = computed(() => CNPG_CLUSTERS.value.filter(c => nsFilter.matches(c.namespace) && (!search.value || c.name.toLowerCase().includes(search.value.toLowerCase()))))

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Cluster', width: 'minmax(180px, 1.3fr)' },
  { key: 'namespace', label: 'Namespace', width: '120px', muted: true },
  { key: 'pgVersion', label: 'PG version', width: '100px' },
  { key: 'instances', label: 'Instances', width: '90px', align: 'right' },
  { key: 'primary', label: 'Primary', width: 'minmax(160px, 1fr)' },
  { key: 'status', label: 'Status', width: 'minmax(180px, 1.5fr)' },
  { key: 'lastBackup', label: 'Last backup', width: '110px', muted: true },
  { key: 'age', label: 'Age', width: '70px', muted: true },
]

function onRowClick(row: K8sCnpgCluster) {
  navigateTo(`/kubernetes/cnpg/${encodeURIComponent(row.name)}`)
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'CloudNativePG')"
        title="CloudNativePG"
        :subtitle="`${CNPG_CLUSTERS.length} Postgres clusters managed by CNPG`"
      >
        <template #actions>
          <Button
            icon="refresh"
            size="sm"
            :disabled="isLoading"
            @click="refresh()">Refresh</Button>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="applyOpen = true">New cluster</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div class="filters">
      <SearchInput v-model="search" placeholder="Filter clusters…" class="search" />
      <K8sNamespaceFilter />
      <span class="spacer" />
    </div>

    <SectionCard glass>
      <GlassTable
        :columns="columns"
        :rows="filtered"
        row-key="name"
        :loading="isLoading"
        loading-text="Loading CNPG clusters…"
        empty-text="No CNPG clusters."
        @row-click="onRowClick">
        <template #col-name="{ row }">
          <div class="name-cell">
            <Icon name="database" :size="13" :color="accent" />
            <span class="mono name">{{ row.name }}</span>
          </div>
        </template>
        <template #col-pgVersion="{ row }"><span class="mono">{{ row.pgVersion }}</span></template>
        <template #col-instances="{ row }"><span class="mono">{{ row.instances }}</span></template>
        <template #col-primary="{ row }"><span class="mono">{{ row.primary }}</span></template>
        <template #col-status="{ row }">
          <K8sStatusBadge :status="row.statusKind === 'ok' ? 'Healthy' : row.statusKind === 'warn' ? 'Degraded' : 'Failing'" :pulse="row.statusKind !== 'ok'" />
          <span class="dim status-text">{{ row.status }}</span>
        </template>
      </GlassTable>
    </SectionCard>

    <ApplyManifestModal
      v-if="applyOpen"
      :accent="accent"
      @close="applyOpen = false"
      @applied="refresh()" />
  </PageShell>
</template>

<style scoped>
.filters { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.search { flex: 1; min-width: 240px; max-width: 360px; }
.spacer { flex: 1; }
.name-cell { display: flex; align-items: center; gap: 8px; }
.name { font-weight: 500; font-size: 12px; }
.mono { font-family: var(--font-mono); font-size: 12px; font-variant-numeric: tabular-nums; }
.dim { color: var(--fg-3); }
.status-text { margin-left: 6px; font-size: 11.5px; }
</style>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const nsFilter = useK8sNamespaceFilter()

const clusterId = computed(() => current.value?.id)
const { data: servicesData, status: svcStatus, refresh: refreshServices } = useK8sServices({ cluster: clusterId })
const { data: ingressesData, status: ingStatus, refresh: refreshIngresses } = useK8sIngresses({ cluster: clusterId })
const { data: netpolsData, status: npStatus, refresh: refreshNetpols } = useK8sNetworkPolicies({ cluster: clusterId })

const SERVICES = computed(() => servicesData.value ?? [])
const INGRESSES = computed(() => ingressesData.value ?? [])
const NETPOLS = computed(() => netpolsData.value ?? [])
const isLoading = computed(() => svcStatus.value === 'pending' || ingStatus.value === 'pending' || npStatus.value === 'pending')

function refreshAll() {
  refreshServices()
  refreshIngresses()
  refreshNetpols()
}

// Live refresh — any Service / Ingress / NetworkPolicy change in the
// cluster re-runs the list queries, so all three tabs stay realtime.
useK8sResourceWatch({
  cluster: clusterId,
  kinds: ['Service', 'Ingress', 'NetworkPolicy'],
  onChange: refreshAll,
})

type Tab = 'services' | 'ingresses' | 'netpols'
const tab = ref<Tab>('services')

const tabs = computed(() => ['Services', 'Ingresses', 'Network Policies'])
const tabToVal: Record<string, Tab> = { 'Services': 'services', 'Ingresses': 'ingresses', 'Network Policies': 'netpols' }
const valToTab: Record<Tab, string> = { services: 'Services', ingresses: 'Ingresses', netpols: 'Network Policies' }

const search = ref('')
const applyOpen = ref(false)

const filteredServices = computed(() => SERVICES.value.filter(s => nsFilter.matches(s.ns) && (!search.value || `${s.name} ${s.selector}`.toLowerCase().includes(search.value.toLowerCase()))))
const filteredIngresses = computed(() => INGRESSES.value.filter(i => nsFilter.matches(i.ns) && (!search.value || `${i.name} ${i.hosts.join(' ')}`.toLowerCase().includes(search.value.toLowerCase()))))
const filteredNetpols = computed(() => NETPOLS.value.filter(n => nsFilter.matches(n.ns) && (!search.value || `${n.name} ${n.podSelector}`.toLowerCase().includes(search.value.toLowerCase()))))

const svcColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 1.5fr)' },
  { key: 'ns', label: 'Namespace', width: '120px', muted: true },
  { key: 'type', label: 'Type', width: '130px' },
  { key: 'clusterIP', label: 'Cluster IP', width: '170px' },
  { key: 'externalIP', label: 'External IP', width: 'minmax(180px, 1.5fr)' },
  { key: 'ports', label: 'Ports', width: 'minmax(160px, 1.2fr)' },
  { key: 'endpoints', label: 'Endpoints', width: '90px', align: 'right' },
  { key: 'age', label: 'Age', width: '70px', muted: true },
]

const ingColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 1.5fr)' },
  { key: 'ns', label: 'Namespace', width: '120px', muted: true },
  { key: 'class', label: 'Class', width: '110px' },
  { key: 'hosts', label: 'Hosts', width: 'minmax(180px, 1.5fr)' },
  { key: 'paths', label: 'Paths', width: 'minmax(140px, 1fr)' },
  { key: 'backends', label: 'Backends', width: 'minmax(160px, 1fr)' },
  { key: 'tls', label: 'TLS', width: '70px' },
  { key: 'age', label: 'Age', width: '70px', muted: true },
]

const npColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 1.5fr)' },
  { key: 'ns', label: 'Namespace', width: '130px', muted: true },
  { key: 'podSelector', label: 'Pod selector', width: 'minmax(200px, 1.5fr)' },
  { key: 'ingress', label: 'Ingress', width: 'minmax(180px, 1fr)' },
  { key: 'egress', label: 'Egress', width: 'minmax(180px, 1fr)' },
  { key: 'age', label: 'Age', width: '70px', muted: true },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Networking')"
        title="Networking"
        :subtitle="`${SERVICES.length} services · ${INGRESSES.length} ingresses · ${NETPOLS.length} network policies`"
        :tabs="tabs"
        :active-tab="valToTab[tab]"
        @tab="(v) => tab = tabToVal[v] || 'services'"
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
            @click="applyOpen = true">New resource</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div class="filters">
      <SearchInput v-model="search" placeholder="Filter…" class="search" />
      <K8sNamespaceFilter />
      <span class="spacer" />
    </div>

    <SectionCard v-if="tab === 'services'" glass>
      <GlassTable
        :columns="svcColumns"
        :rows="filteredServices"
        row-key="id"
        :loading="svcStatus === 'pending'"
        loading-text="Loading services…"
        empty-text="No services match the current filters."
      >
        <template #col-name="{ row }">
          <span class="mono name">{{ row.name }}</span>
        </template>
        <template #col-type="{ row }">
          <Badge :color="row.type === 'LoadBalancer' ? '#34d99a' : row.type === 'NodePort' ? '#ffb547' : '#5ec5ff'">{{ row.type }}</Badge>
        </template>
        <template #col-clusterIP="{ row }">
          <span class="mono dim">{{ row.clusterIP }}</span>
        </template>
        <template #col-externalIP="{ row }">
          <span class="mono dim">{{ row.externalIP }}</span>
        </template>
        <template #col-ports="{ row }">
          <span class="mono">{{ (row.ports as string[]).join(', ') }}</span>
        </template>
        <template #col-endpoints="{ row }">
          <span class="mono">{{ row.endpoints }}</span>
        </template>
      </GlassTable>
    </SectionCard>

    <SectionCard v-else-if="tab === 'ingresses'" glass>
      <GlassTable
        :columns="ingColumns"
        :rows="filteredIngresses"
        row-key="id"
        :loading="ingStatus === 'pending'"
        loading-text="Loading ingresses…"
        empty-text="No ingresses match the current filters."
      >
        <template #col-name="{ row }">
          <span class="mono name">{{ row.name }}</span>
        </template>
        <template #col-class="{ row }">
          <Badge color="#5ec5ff">{{ row.class }}</Badge>
        </template>
        <template #col-hosts="{ row }">
          <span class="mono">{{ (row.hosts as string[]).join(', ') }}</span>
        </template>
        <template #col-paths="{ row }">
          <span class="mono">{{ (row.paths as string[]).join(', ') }}</span>
        </template>
        <template #col-backends="{ row }">
          <span class="mono">{{ (row.backends as string[]).join(', ') }}</span>
        </template>
        <template #col-tls="{ row }">
          <Icon
            v-if="row.tls"
            name="lock"
            :size="13"
            color="var(--ok)" />
          <span v-else class="dim">—</span>
        </template>
      </GlassTable>
    </SectionCard>

    <SectionCard v-else-if="tab === 'netpols'" glass>
      <GlassTable
        :columns="npColumns"
        :rows="filteredNetpols"
        row-key="id"
        :loading="npStatus === 'pending'"
        loading-text="Loading network policies…"
        empty-text="No network policies match the current filters."
      >
        <template #col-name="{ row }">
          <span class="mono name">{{ row.name }}</span>
        </template>
        <template #col-podSelector="{ row }">
          <span class="mono">{{ row.podSelector }}</span>
        </template>
        <template #col-ingress="{ row }">
          <span class="mono">{{ row.ingress }}</span>
        </template>
        <template #col-egress="{ row }">
          <span class="mono">{{ row.egress }}</span>
        </template>
      </GlassTable>
    </SectionCard>

    <ApplyManifestModal
      v-if="applyOpen"
      :accent="accent"
      @close="applyOpen = false"
      @applied="refreshAll" />
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

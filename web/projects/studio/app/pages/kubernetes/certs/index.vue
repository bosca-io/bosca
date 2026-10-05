<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const nsFilter = useK8sNamespaceFilter()

const clusterId = computed(() => current.value?.id)
const { data: certsData, status: certsStatus, refresh: refreshCerts } = useK8sCertificates({ cluster: clusterId })
const { data: issuersData, status: issuersStatus, refresh: refreshIssuers } = useK8sIssuers({ cluster: clusterId })
const CERTIFICATES = computed(() => certsData.value ?? [])
const ISSUERS = computed(() => issuersData.value ?? [])
const isLoading = computed(() => certsStatus.value === 'pending' || issuersStatus.value === 'pending')
function refreshAll() { refreshCerts(); refreshIssuers() }

// Live refresh — issuance / renewal / readiness transitions on
// cert-manager resources re-run the list queries for both tabs.
useK8sResourceWatch({
  cluster: clusterId,
  kinds: ['Certificate', 'Issuer', 'ClusterIssuer'],
  onChange: refreshAll,
})

type Tab = 'certs' | 'issuers'
const tab = ref<Tab>('certs')
const tabs = computed(() => ['Certificates', 'Issuers'])
const tabToVal: Record<string, Tab> = { 'Certificates': 'certs', 'Issuers': 'issuers' }
const valToTab: Record<Tab, string> = { certs: 'Certificates', issuers: 'Issuers' }

const search = ref('')
const applyOpen = ref(false)

const filteredCerts = computed(() => CERTIFICATES.value.filter(c => nsFilter.matches(c.ns) && (!search.value || `${c.name} ${c.dns.join(' ')}`.toLowerCase().includes(search.value.toLowerCase()))))
const filteredIssuers = computed(() => ISSUERS.value.filter(i => !search.value || i.name.toLowerCase().includes(search.value.toLowerCase())))

const certsCols: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 1.3fr)' },
  { key: 'ns', label: 'Namespace', width: '120px', muted: true },
  { key: 'dns', label: 'DNS names', width: 'minmax(200px, 1.5fr)' },
  { key: 'issuer', label: 'Issuer', width: '160px' },
  { key: 'status', label: 'Status', width: '120px' },
  { key: 'expires', label: 'Expires (d)', width: 'minmax(140px, 1fr)' },
  { key: 'renewsIn', label: 'Renews in', width: '100px', align: 'right' },
  { key: 'secretName', label: 'Secret', width: 'minmax(160px, 1fr)', muted: true },
]

const issuersCols: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 1.3fr)' },
  { key: 'kind', label: 'Kind', width: '140px' },
  { key: 'type', label: 'Type', width: '100px' },
  { key: 'server', label: 'Server', width: 'minmax(260px, 2fr)', muted: true },
  { key: 'status', label: 'Status', width: '110px' },
  { key: 'certs', label: 'Certificates', width: '110px', align: 'right' },
  { key: 'age', label: 'Age', width: '70px', muted: true },
]

function expiresBar(days: number): { pct: number; color: string } {
  // 0..90 days mapped — < 14 days red, < 30 amber, else ok
  const clamped = Math.min(365, Math.max(0, days))
  const pct = Math.round((clamped / 90) * 100)
  if (days < 14) return { pct, color: 'var(--err)' }
  if (days < 30) return { pct, color: 'var(--warn)' }
  return { pct, color: 'var(--ok)' }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'cert-manager')"
        title="cert-manager"
        :subtitle="`${CERTIFICATES.length} certificates · ${ISSUERS.length} issuers · ${CERTIFICATES.filter(c => c.status === 'Failed').length} need attention`"
        :tabs="tabs"
        :active-tab="valToTab[tab]"
        @tab="(v) => tab = tabToVal[v] || 'certs'"
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
            @click="applyOpen = true">New certificate</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div class="filters">
      <SearchInput v-model="search" placeholder="Filter…" class="search" />
      <K8sNamespaceFilter v-if="tab === 'certs'" />
      <span class="spacer" />
    </div>

    <SectionCard v-if="tab === 'certs'" glass>
      <GlassTable
        :columns="certsCols"
        :rows="filteredCerts"
        row-key="id"
        :loading="certsStatus === 'pending'"
        loading-text="Loading certificates…"
        empty-text="No certificates match.">
        <template #col-name="{ row }">
          <div class="name-cell">
            <Icon name="lock" :size="13" :color="row.status === 'Ready' ? 'var(--ok)' : row.status === 'Renewing' ? 'var(--warn)' : 'var(--err)'" />
            <span class="mono name">{{ row.name }}</span>
          </div>
        </template>
        <template #col-dns="{ row }">
          <span class="mono">{{ row.dns.join(', ') }}</span>
        </template>
        <template #col-issuer="{ row }">
          <Badge color="#5ec5ff">{{ row.issuer }}</Badge>
        </template>
        <template #col-status="{ row }">
          <K8sStatusBadge :status="row.status" :pulse="row.status !== 'Ready'" />
        </template>
        <template #col-expires="{ row }">
          <div class="exp-cell">
            <div class="bar" :style="{ background: 'var(--bg-2)' }">
              <div class="seg" :style="{ width: `${expiresBar(row.expires).pct}%`, background: expiresBar(row.expires).color }" />
            </div>
            <span class="mono dim">{{ row.expires }}d</span>
          </div>
        </template>
        <template #col-renewsIn="{ row }">
          <span class="mono" :style="{ color: row.renewsIn < 0 ? 'var(--err)' : 'inherit' }">
            {{ row.renewsIn < 0 ? '—' : `${row.renewsIn}d` }}
          </span>
        </template>
      </GlassTable>
    </SectionCard>

    <SectionCard v-else glass>
      <GlassTable
        :columns="issuersCols"
        :rows="filteredIssuers"
        row-key="id"
        :loading="issuersStatus === 'pending'"
        loading-text="Loading issuers…"
        empty-text="No issuers match.">
        <template #col-name="{ row }"><span class="mono name">{{ row.name }}</span></template>
        <template #col-kind="{ row }">
          <Badge :color="row.kind === 'ClusterIssuer' ? '#a78bff' : '#5ec5ff'">{{ row.kind }}</Badge>
        </template>
        <template #col-type="{ row }"><span class="mono">{{ row.type }}</span></template>
        <template #col-server="{ row }"><span class="mono dim">{{ row.server }}</span></template>
        <template #col-status="{ row }">
          <K8sStatusBadge :status="row.status" :pulse="row.status !== 'Ready'" />
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
.name-cell { display: flex; align-items: center; gap: 8px; }
.name { font-weight: 500; font-size: 12px; }
.mono { font-family: var(--font-mono); font-size: 12px; font-variant-numeric: tabular-nums; }
.dim { color: var(--fg-3); }

.exp-cell { display: grid; grid-template-columns: 1fr 40px; gap: 8px; align-items: center; }
.bar { height: 6px; border-radius: 999px; overflow: hidden; border: 1px solid var(--line); }
.seg { height: 100%; border-radius: 999px; transition: width 0.4s; }
</style>

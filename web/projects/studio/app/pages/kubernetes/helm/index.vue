<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GlassTableColumn } from '@bosca/ui'
import type { HelmStatus, K8sHelmRelease } from '~/composables/useK8sTypes'
import type { HelmUninstallTarget } from '~/components/kubernetes/HelmUninstallModal.vue'
import type { HelmUpgradeTarget } from '~/components/kubernetes/HelmUpgradeModal.vue'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const nsFilter = useK8sNamespaceFilter()
const toast = useToast()

const clusterId = computed(() => current.value?.id)
const clusterIdRef = computed(() => current.value?.id)
const { helmRollback } = useK8sMutations({ cluster: clusterIdRef })
const { data: helmData, status: queryStatus, refresh } = useK8sHelmReleases({ cluster: clusterId })
// Live refresh — installs / upgrades / rollbacks / uninstalls write
// `owner=helm` release Secrets; watching those re-runs the list so
// revision and status stay realtime.
useK8sResourceWatch({
  cluster: clusterId,
  kinds: ['HelmRelease'],
  onChange: refresh,
})
const HELM_RELEASES = computed(() => helmData.value ?? [])
const isLoading = computed(() => queryStatus.value === 'pending')

const upgradeTarget = ref<HelmUpgradeTarget | null>(null)
const uninstallTarget = ref<HelmUninstallTarget | null>(null)
const rollbackTarget = ref<K8sHelmRelease | null>(null)
const rollbackLoading = ref(false)

function openUpgrade(r: K8sHelmRelease) {
  upgradeTarget.value = {
    name: r.name,
    namespace: r.ns,
    repo: r.repo,
    chart: r.chart,
    chartVersion: r.chartVersion,
  }
}

function openUninstall(r: K8sHelmRelease) {
  uninstallTarget.value = { name: r.name, namespace: r.ns }
}

function openRollback(r: K8sHelmRelease) {
  if (r.revision <= 1) {
    toast.info(`${r.name} has no earlier revision to roll back to`)
    return
  }
  rollbackTarget.value = r
}

async function confirmRollback() {
  const r = rollbackTarget.value
  if (!r) return
  rollbackLoading.value = true
  try {
    await helmRollback({
      namespace: r.ns,
      name: r.name,
      toRevision: r.revision - 1,
    })
    toast.success(`Rolled ${r.name} back to revision ${r.revision - 1}`)
    rollbackTarget.value = null
    await refresh()
  } catch (err) {
    toast.error(err instanceof Error && err.message ? err.message : 'Rollback failed')
  } finally {
    rollbackLoading.value = false
  }
}

function onUpgraded() {
  upgradeTarget.value = null
  void refresh()
}
function onUninstalled() {
  uninstallTarget.value = null
  void refresh()
}

const search = ref('')
const statusFilter = ref<'all' | HelmStatus>('all')

const filtered = computed<K8sHelmRelease[]>(() => HELM_RELEASES.value.filter((r) => {
  if (!nsFilter.matches(r.ns)) return false
  if (statusFilter.value !== 'all' && r.status !== statusFilter.value) return false
  if (search.value && !`${r.name} ${r.chart} ${r.repo}`.toLowerCase().includes(search.value.toLowerCase())) return false
  return true
}))

const deployedCount = computed(() => HELM_RELEASES.value.filter(r => r.status === 'deployed').length)
const failedCount = computed(() => HELM_RELEASES.value.filter(r => r.status === 'failed').length)
const pendingCount = computed(() => HELM_RELEASES.value.filter(r => r.status === 'pending').length)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Release', width: 'minmax(220px, 2fr)' },
  { key: 'ns', label: 'Namespace', width: '140px', muted: true },
  { key: 'chart', label: 'Chart', width: 'minmax(180px, 1.5fr)' },
  { key: 'appVersion', label: 'App version', width: '110px' },
  { key: 'revision', label: 'Revision', width: '80px', align: 'right' },
  { key: 'status', label: 'Status', width: '120px' },
  { key: 'updated', label: 'Last updated', width: '110px', muted: true },
  { key: 'installed', label: 'Installed', width: '100px', muted: true },
]

const statusLabel: Record<HelmStatus, string> = {
  deployed: 'Deployed',
  pending: 'Pending',
  failed: 'Failed',
  superseded: 'Superseded',
}

function onRowClick(row: K8sHelmRelease) {
  navigateTo(`/kubernetes/helm/${encodeURIComponent(row.name)}`)
}

function rowActions(): Array<{ id: string; label: string; icon?: string; danger?: boolean; separator?: boolean }> {
  return [
    { id: 'view', label: 'View values', icon: 'code' },
    { id: 'upgrade', label: 'Upgrade…', icon: 'arrowUpRight' },
    { id: 'rollback', label: 'Rollback', icon: 'refresh' },
    { id: 'separator', label: '', separator: true },
    { id: 'uninstall', label: 'Uninstall', icon: 'trash', danger: true },
  ]
}

function onAction({ action, row }: { action: string; row: K8sHelmRelease }) {
  if (action === 'rollback') openRollback(row)
  else if (action === 'upgrade') openUpgrade(row)
  else if (action === 'uninstall') openUninstall(row)
  else if (action === 'view') navigateTo(`/kubernetes/helm/${encodeURIComponent(row.name)}`)
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Helm Releases')"
        title="Helm Releases"
        :subtitle="`${HELM_RELEASES.length} releases · ${deployedCount} deployed · ${pendingCount} pending · ${failedCount} failed`"
      >
        <template #actions>
          <Button
            icon="refresh"
            size="sm"
            :disabled="isLoading"
            @click="refresh()">Refresh</Button>
          <NuxtLink to="/kubernetes/helm/repos">
            <Button icon="database" size="sm">Repos</Button>
          </NuxtLink>
          <NuxtLink to="/kubernetes/helm/catalog">
            <Button
              primary
              icon="plus"
              size="sm"
              :accent="accent">Install chart</Button>
          </NuxtLink>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div class="filters">
      <SearchInput v-model="search" placeholder="Filter releases…" class="search" />
      <K8sNamespaceFilter />
      <div class="chips">
        <button
          v-for="s in (['all', 'deployed', 'pending', 'failed'] as const)"
          :key="s"
          class="chip"
          :class="{ on: statusFilter === s }"
          @click="statusFilter = s"
        >{{ s === 'all' ? 'All' : s }}</button>
      </div>
      <span class="spacer" />
      <span class="muted">{{ filtered.length }} of {{ HELM_RELEASES.length }}</span>
    </div>

    <SectionCard glass>
      <GlassTable
        :columns="columns"
        :rows="filtered"
        row-key="id"
        :row-actions="rowActions"
        :loading="isLoading"
        loading-text="Loading releases…"
        empty-text="No releases match the current filters."
        @row-click="onRowClick"
        @row-action="onAction"
      >
        <template #col-name="{ row }">
          <div class="name-cell">
            <span class="icon-tile" :style="{ color: accent, background: `color-mix(in oklab, ${accent} 16%, transparent)` }">
              <Icon name="archive" :size="13" />
            </span>
            <div class="name-stack">
              <span class="mono name">{{ row.name }}</span>
              <span class="mono dim">{{ row.repo }}</span>
            </div>
          </div>
        </template>
        <template #col-chart="{ row }">
          <span class="mono">{{ row.chart }}-{{ row.chartVersion }}</span>
        </template>
        <template #col-appVersion="{ row }">
          <span class="mono">v{{ row.appVersion }}</span>
        </template>
        <template #col-revision="{ row }">
          <span class="mono">{{ row.revision }}</span>
        </template>
        <template #col-status="{ row }">
          <K8sStatusBadge :status="statusLabel[row.status as HelmStatus]" :pulse="row.status !== 'deployed'" />
        </template>
      </GlassTable>
    </SectionCard>

    <HelmUpgradeModal
      :release="upgradeTarget"
      :accent="accent"
      @close="upgradeTarget = null"
      @upgraded="onUpgraded" />

    <HelmUninstallModal
      :release="uninstallTarget"
      @close="uninstallTarget = null"
      @uninstalled="onUninstalled" />

    <ConfirmModal
      v-if="rollbackTarget"
      :title="`Roll ${rollbackTarget.name} back?`"
      :confirm-label="`Roll back to revision ${rollbackTarget.revision - 1}`"
      :loading="rollbackLoading"
      @close="rollbackTarget = null"
      @confirm="confirmRollback"
    >
      <p class="rollback-text">
        This rolls release <strong class="mono">{{ rollbackTarget.ns }}/{{ rollbackTarget.name }}</strong>
        from revision <strong>{{ rollbackTarget.revision }}</strong> back to
        <strong>{{ rollbackTarget.revision - 1 }}</strong>.
      </p>
    </ConfirmModal>
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

.name-cell { display: flex; align-items: center; gap: 10px; min-width: 0; }
.icon-tile {
  width: 24px;
  height: 24px;
  border-radius: 5px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
}
.name-stack { display: flex; flex-direction: column; min-width: 0; line-height: 1.3; }
.mono { font-family: var(--font-mono); font-size: 12.5px; font-variant-numeric: tabular-nums; }
.name { font-weight: 500; }
.dim { color: var(--fg-3); font-size: 11px; }
.rollback-text { margin: 0; font-size: 13px; color: var(--fg-2); line-height: 1.5; }
.rollback-text strong { color: var(--fg-1); }
</style>

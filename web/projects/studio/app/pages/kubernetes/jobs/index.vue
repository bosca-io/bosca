<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GlassTableColumn } from '@bosca/ui'
import type { K8sWorkload } from '~/composables/useK8sTypes'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const nsFilter = useK8sNamespaceFilter()

const search = ref('')
const applyOpen = ref(false)

const { data: workloadsData, status: queryStatus, refresh } = useK8sWorkloads({
  cluster: () => current.value?.id,
})
// Live refresh — Job / CronJob runs come and go quickly, so each
// create / completion / delete re-runs the list query.
useK8sResourceWatch({
  cluster: () => current.value?.id,
  kinds: ['Job', 'CronJob'],
  onChange: refresh,
})
const isLoading = computed(() => queryStatus.value === 'pending')

const jobs = computed(() => (workloadsData.value ?? []).filter(w => w.kind === 'Job' || w.kind === 'CronJob'))
const filtered = computed(() => jobs.value.filter((j) => {
  if (!nsFilter.matches(j.ns)) return false
  if (search.value && !j.name.toLowerCase().includes(search.value.toLowerCase())) return false
  return true
}))

// Jobs and CronJobs are workloads; the workload detail page handles
// every kind. No separate /kubernetes/jobs/[id] route is needed.
function onRowClick(row: K8sWorkload) {
  navigateTo(`/kubernetes/workloads/${encodeURIComponent(row.id)}`)
}

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(220px, 1.5fr)' },
  { key: 'kind', label: 'Kind', width: '120px' },
  { key: 'ns', label: 'Namespace', width: '120px', muted: true },
  { key: 'image', label: 'Image', width: 'minmax(220px, 2fr)', muted: true },
  { key: 'status', label: 'Status', width: '110px' },
  { key: 'age', label: 'Age', width: '70px', muted: true },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Jobs & Cron')"
        title="Jobs & Cron"
        :subtitle="`${jobs.length} jobs and cron jobs`"
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
            @click="applyOpen = true">New cron job</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div class="filters">
      <SearchInput v-model="search" placeholder="Filter jobs…" class="search" />
      <K8sNamespaceFilter />
      <span class="spacer" />
      <span class="muted">{{ filtered.length }} of {{ jobs.length }}</span>
    </div>

    <SectionCard glass>
      <GlassTable
        :columns="columns"
        :rows="filtered"
        row-key="id"
        :loading="isLoading"
        loading-text="Loading jobs…"
        empty-text="No jobs match the filters."
        @row-click="onRowClick"
      >
        <template #col-name="{ row }"><span class="mono name">{{ row.name }}</span></template>
        <template #col-kind="{ row }">
          <Badge :color="row.kind === 'CronJob' ? '#a78bff' : '#5ec5ff'">{{ row.kind }}</Badge>
        </template>
        <template #col-status="{ row }">
          <K8sStatusBadge :status="row.status === 'ok' ? 'Healthy' : row.status === 'warn' ? 'Degraded' : 'Failing'" :pulse="row.status !== 'ok'" />
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
.muted { color: var(--fg-3); font-size: 12px; }
.name { font-weight: 500; font-size: 12px; }
.mono { font-family: var(--font-mono); font-size: 12px; font-variant-numeric: tabular-nums; }
</style>

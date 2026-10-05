<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GlassTableColumn } from '@bosca/ui'
import type { ConfigKind, K8sConfigResource } from '~/composables/useK8sTypes'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const nsFilter = useK8sNamespaceFilter()

const clusterId = computed(() => current.value?.id)
const { data: configData, status: queryStatus, refresh } = useK8sConfigResources({ cluster: clusterId })
// Live refresh — ConfigMap / Secret create, edit, and delete re-run
// the list query.
useK8sResourceWatch({
  cluster: clusterId,
  kinds: ['ConfigMap', 'Secret'],
  onChange: refresh,
})
const CONFIG_RESOURCES = computed(() => configData.value ?? [])
const isLoading = computed(() => queryStatus.value === 'pending')

const search = ref('')
const kindFilter = ref<'all' | ConfigKind>('all')
const applyOpen = ref(false)

const filtered = computed<K8sConfigResource[]>(() => CONFIG_RESOURCES.value.filter((r) => {
  if (!nsFilter.matches(r.ns)) return false
  if (kindFilter.value !== 'all' && r.kind !== kindFilter.value) return false
  if (search.value && !`${r.name} ${r.keys.join(' ')}`.toLowerCase().includes(search.value.toLowerCase())) return false
  return true
}))

const cmCount = computed(() => CONFIG_RESOURCES.value.filter(r => r.kind === 'ConfigMap').length)
const secretCount = computed(() => CONFIG_RESOURCES.value.filter(r => r.kind === 'Secret').length)
const managedCount = computed(() => CONFIG_RESOURCES.value.filter(r => r.managedBy).length)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(220px, 2fr)' },
  { key: 'kind', label: 'Kind', width: '160px' },
  { key: 'ns', label: 'Namespace', width: '120px', muted: true },
  { key: 'keys', label: 'Keys', width: 'minmax(220px, 2fr)' },
  { key: 'managedBy', label: 'Managed by', width: '130px' },
  { key: 'size', label: 'Size', width: '70px', align: 'right' },
  { key: 'age', label: 'Age', width: '60px', muted: true },
]

function maskedKey(name: string, k: string): string {
  if (k.length <= 4) return '••'
  return `${k.slice(0, 2)}${'●'.repeat(Math.max(0, k.length - 4))}${k.slice(-2)}`
}

function onRowClick(row: K8sConfigResource) {
  navigateTo(`/kubernetes/config/${encodeURIComponent(row.id)}`)
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Config & Secrets')"
        title="Config & Secrets"
        :subtitle="`${cmCount} ConfigMaps · ${secretCount} Secrets · ${managedCount} managed by external operators`"
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
            @click="applyOpen = true">Create</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div class="filters">
      <SearchInput v-model="search" placeholder="Filter by name or key…" class="search" />
      <K8sNamespaceFilter />
      <div class="chips">
        <button
          v-for="k in (['all', 'ConfigMap', 'Secret'] as const)"
          :key="k"
          class="chip"
          :class="{ on: kindFilter === k }"
          @click="kindFilter = k">
          {{ k === 'all' ? 'All' : k }}
        </button>
      </div>
      <span class="spacer" />
      <span class="muted">{{ filtered.length }} of {{ CONFIG_RESOURCES.length }}</span>
    </div>

    <SectionCard glass>
      <GlassTable
        :columns="columns"
        :rows="filtered"
        row-key="id"
        :loading="isLoading"
        loading-text="Loading config resources…"
        empty-text="No config resources match the current filters."
        @row-click="onRowClick"
      >
        <template #col-name="{ row }">
          <div class="name-cell">
            <Icon :name="row.kind === 'Secret' ? 'lock' : 'file'" :size="13" :color="row.kind === 'Secret' ? 'var(--brand-1, #a78bff)' : 'var(--info, #5ec5ff)'" />
            <span class="mono name">{{ row.name }}</span>
          </div>
        </template>
        <template #col-kind="{ row }">
          <Badge :color="row.kind === 'Secret' ? '#a78bff' : '#5ec5ff'">{{ row.type || row.kind }}</Badge>
        </template>
        <template #col-keys="{ row }">
          <div class="keys">
            <span v-for="k in (row.keys as string[]).slice(0, 3)" :key="k" class="key-pill mono">
              <template v-if="row.kind === 'Secret'">{{ maskedKey(row.name, k) }}</template>
              <template v-else>{{ k }}</template>
            </span>
            <span v-if="(row.keys as string[]).length > 3" class="key-pill more">+{{ (row.keys as string[]).length - 3 }}</span>
          </div>
        </template>
        <template #col-managedBy="{ row }">
          <Badge v-if="row.managedBy" color="#5ec5ff">{{ row.managedBy }}</Badge>
          <span v-else class="dim">—</span>
        </template>
        <template #col-size="{ row }">
          <span class="mono">{{ row.size }}</span>
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
}
.chip.on { background: var(--bg-3); color: var(--fg-0); }

.name-cell { display: flex; align-items: center; gap: 8px; min-width: 0; }
.name { font-weight: 500; font-size: 12px; overflow: hidden; text-overflow: ellipsis; }
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }

.keys { display: flex; flex-wrap: wrap; gap: 4px; }
.key-pill {
  display: inline-flex;
  align-items: center;
  padding: 1px 6px;
  border: 1px solid var(--line);
  border-radius: 4px;
  font-size: 10.5px;
  color: var(--fg-2);
  background: var(--bg-2);
}
.key-pill.more { color: var(--fg-3); }

.dim { color: var(--fg-3); font-size: 12px; }
</style>

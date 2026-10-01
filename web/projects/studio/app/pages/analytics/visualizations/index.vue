<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()
const vizGql = gql`query GetVisualizations { analytics { visualizations { all { id name type queryId } } } }`
const deleteGql = gql`mutation DeleteVisualization($id: UUID!) { analytics { visualizations { delete(id: $id) } } }`
interface VizRow { id: string; name: string; type: string; queryId: string | null }
const { data, status, refresh } = useAsyncQuery<{ analytics: { visualizations: { all: VizRow[] } } }>('visualizations-list', vizGql, {})
const visualizations = computed(() => data.value?.analytics?.visualizations?.all ?? [])
const deleteTarget = ref<VizRow | null>(null); const deleteLoading = ref(false)
const VIZ_TYPES: Record<string, string> = { BAR: '#3b82f6', LINE: '#34d99a', PIE: '#ff7ac6', TABLE: '#5ec5ff', MAP: '#ffb547', NUMBER: '#a78bff' }

const selectedIds = ref<Set<string>>(new Set())
const showBulkPermissions = ref(false)
const allSelected = computed(() => visualizations.value.length > 0 && selectedIds.value.size === visualizations.value.length)
const someSelected = computed(() => selectedIds.value.size > 0 && !allSelected.value)

function toggleSelect(id: string) {
  if (selectedIds.value.has(id)) selectedIds.value.delete(id)
  else selectedIds.value.add(id)
}

function toggleSelectAll() {
  selectedIds.value = allSelected.value ? new Set() : new Set(visualizations.value.map(v => v.id))
}

const columns: GlassTableColumn[] = [
  { key: '_sel', label: '', width: '28px' },
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'type', label: 'Type', width: '120px' },
]
async function confirmDelete() { if (!deleteTarget.value) return; deleteLoading.value = true; try { await gqlMutation(deleteGql, { id: deleteTarget.value.id }); deleteTarget.value = null; toast.success('Deleted'); refresh() } catch { toast.error('Failed') } finally { deleteLoading.value = false } }
</script>
<template>
  <PageShell>
    <template #header><PageHeader
      :accent="accent"
      :breadcrumb="buildBreadcrumb('Analytics', 'Settings', 'Visualizations')"
      title="Visualizations"
      :subtitle="`${visualizations.length} visualizations`"><template #actions>
        <Button
          v-if="selectedIds.size > 0"
          icon="lock"
          size="sm"
          @click="showBulkPermissions = true">Permissions ({{ selectedIds.size }})</Button>
        <Button
          primary
          icon="plus"
          size="sm"
          :accent="accent"
          @click="router.push('/analytics/visualizations/new')">New Visualization</Button>
      </template></PageHeader></template>
    <SectionCard title="Visualizations">
      <GlassTable
        :columns="columns"
        :rows="visualizations"
        :loading="status === 'pending' && visualizations.length === 0"
        empty-text="No visualizations."
        :row-actions="() => [{ id: 'open', label: 'Edit', icon: 'eye' }, { id: 'sep', label: '', separator: true }, { id: 'delete', label: 'Delete', icon: 'trash', danger: true }]"
        arrow
        @row-click="(r: any) => router.push(`/analytics/visualizations/${r.id}`)"
        @row-action="({ action, row }: any) => action === 'open' ? router.push(`/analytics/visualizations/${row.id}`) : action === 'delete' ? deleteTarget = row : null">
        <template #header-_sel>
          <Checkbox
            :model-value="allSelected"
            :indeterminate="someSelected"
            :accent="accent"
            @update:model-value="toggleSelectAll" />
        </template>
        <template #col-_sel="{ row }">
          <span @click.stop>
            <Checkbox
              :model-value="selectedIds.has(row.id)"
              :accent="accent"
              @update:model-value="() => toggleSelect(row.id)" />
          </span>
        </template>
        <template #col-name="{ row }"><span style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span></template>
        <template #col-type="{ row }"><Badge :color="VIZ_TYPES[row.type] ?? accent">{{ row.type }}</Badge></template>
      </GlassTable>
    </SectionCard>
    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete" />
    <AnalyticsBulkPermissionsModal
      v-if="showBulkPermissions"
      entity-type="visualizations"
      :entity-ids="[...selectedIds]"
      :accent="accent"
      @applied="refresh"
      @close="showBulkPermissions = false" />
  </PageShell>
</template>

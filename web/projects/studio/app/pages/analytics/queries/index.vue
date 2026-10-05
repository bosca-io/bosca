<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()
const { isAdmin, hasGroup } = usePersonas()
// Backend accepts analytics.manager for query management (including git
// backfill), so the button follows the same rule instead of admin-only.
const canManageAnalytics = computed(() => isAdmin.value || hasGroup('analytics.manager'))
const showBackfill = ref(false)

const queriesGql = gql`
  query GetAnalyticsQueries { analytics { queries { all { id key name description } } } }
`
const deleteGql = gql`
  mutation DeleteQuery($id: UUID!) { analytics { queries { delete(id: $id) } } }
`

interface AQuery { id: string; key: string; name: string; description: string | null }

const { data, status, refresh } = useAsyncQuery<{ analytics: { queries: { all: AQuery[] } } }>('analytics-queries', queriesGql, {})
const queries = computed(() => data.value?.analytics?.queries?.all ?? [])
const deleteTarget = ref<AQuery | null>(null)
const deleteLoading = ref(false)

const selectedIds = ref<Set<string>>(new Set())
const showBulkPermissions = ref(false)
const allSelected = computed(() => queries.value.length > 0 && selectedIds.value.size === queries.value.length)
const someSelected = computed(() => selectedIds.value.size > 0 && !allSelected.value)

function toggleSelect(id: string) {
  if (selectedIds.value.has(id)) selectedIds.value.delete(id)
  else selectedIds.value.add(id)
}

function toggleSelectAll() {
  selectedIds.value = allSelected.value ? new Set() : new Set(queries.value.map(q => q.id))
}

const columns: GlassTableColumn[] = [
  { key: '_sel', label: '', width: '28px' },
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'key', label: 'Key', width: '1fr', muted: true },
  { key: 'description', label: 'Description', width: '1fr', muted: true },
]

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try { await gqlMutation(deleteGql, { id: deleteTarget.value.id }); deleteTarget.value = null; toast.success('Deleted'); refresh() }
  catch { toast.error('Failed') } finally { deleteLoading.value = false }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Analytics', 'Queries')"
        title="Analytics Queries"
        :subtitle="`${queries.length} queries`">
        <template #actions>
          <Button
            v-if="selectedIds.size > 0"
            icon="lock"
            size="sm"
            @click="showBulkPermissions = true">Permissions ({{ selectedIds.size }})</Button>
          <Button
            v-if="canManageAnalytics"
            icon="git-branch"
            size="sm"
            @click="showBackfill = true">Backfill…</Button>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="router.push('/analytics/queries/new')">New Query</Button>
        </template>
      </PageHeader>
    </template>
    <SectionCard title="Queries">
      <GlassTable
        :columns="columns"
        :rows="queries"
        :loading="status === 'pending' && queries.length === 0"
        empty-text="No queries."
        :row-actions="() => [{ id: 'open', label: 'Edit', icon: 'eye' }, { id: 'sep', label: '', separator: true }, { id: 'delete', label: 'Delete', icon: 'trash', danger: true }]"
        arrow
        @row-click="(r: any) => router.push(`/analytics/queries/${r.id}`)"
        @row-action="({ action, row }: any) => action === 'open' ? router.push(`/analytics/queries/${row.id}`) : action === 'delete' ? deleteTarget = row : null">
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
        <template #col-key="{ row }"><span class="mono">{{ row.key }}</span></template>
      </GlassTable>
    </SectionCard>
    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete" />
    <AnalyticsBackfillModal
      v-if="showBackfill"
      :accent="accent"
      @close="showBackfill = false" />
    <AnalyticsBulkPermissionsModal
      v-if="showBulkPermissions"
      entity-type="queries"
      :entity-ids="[...selectedIds]"
      :accent="accent"
      @applied="refresh"
      @close="showBulkPermissions = false" />
  </PageShell>
</template>

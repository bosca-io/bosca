<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const dashboardsGql = gql`
  query GetAnalyticsDashboards {
    analytics { dashboards { all { id key name description } } }
  }
`
const addGql = gql`
  mutation AddDashboard($dashboard: AnalyticsDashboardInput!) {
    analytics { dashboards { add(dashboard: $dashboard) { id } } }
  }
`
const deleteGql = gql`
  mutation DeleteDashboard($id: UUID!) { analytics { dashboards { delete(id: $id) } } }
`

interface Dashboard { id: string; key: string; name: string; description: string | null }

const { data, status, refresh } = useAsyncQuery<{ analytics: { dashboards: { all: Dashboard[] } } }>('dashboards-list', dashboardsGql, {})
const dashboards = computed(() => data.value?.analytics?.dashboards?.all ?? [])

const showCreate = ref(false)
useCreateFromQuery(() => { showCreate.value = true })
const newKey = ref('')
const newName = ref('')
const newDesc = ref('')
const saving = ref(false)
const deleteTarget = ref<Dashboard | null>(null)
const deleteLoading = ref(false)

const selectedIds = ref<Set<string>>(new Set())
const showBulkPermissions = ref(false)
const allSelected = computed(() => dashboards.value.length > 0 && selectedIds.value.size === dashboards.value.length)
const someSelected = computed(() => selectedIds.value.size > 0 && !allSelected.value)

function toggleSelect(id: string) {
  if (selectedIds.value.has(id)) selectedIds.value.delete(id)
  else selectedIds.value.add(id)
}

function toggleSelectAll() {
  selectedIds.value = allSelected.value ? new Set() : new Set(dashboards.value.map(d => d.id))
}

const columns: GlassTableColumn[] = [
  { key: '_sel', label: '', width: '28px' },
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'key', label: 'Key', width: '1fr', muted: true },
  { key: 'description', label: 'Description', width: '1fr', muted: true },
]

async function handleCreate() {
  saving.value = true
  try {
    const result = await gqlMutation<{ analytics: { dashboards: { add: { id: string } } } }>(addGql, {
      dashboard: { key: newKey.value, name: newName.value, description: newDesc.value, visualizations: [], configuration: {} },
    })
    showCreate.value = false; toast.success('Dashboard created')
    router.push(`/analytics/dashboards/${result.analytics.dashboards.add.id}`)
  } catch { toast.error('Failed') } finally { saving.value = false }
}

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
        :breadcrumb="buildBreadcrumb('Analytics', 'Dashboards')"
        title="Dashboards"
        :subtitle="`${dashboards.length} dashboards`">
        <template #actions>
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
            @click="showCreate = true">New Dashboard</Button>
        </template>
      </PageHeader>
    </template>
    <SectionCard title="Dashboards">
      <GlassTable
        :columns="columns"
        :rows="dashboards"
        :loading="status === 'pending' && dashboards.length === 0"
        empty-text="No dashboards."
        :row-actions="() => [{ id: 'open', label: 'Edit', icon: 'pencil' }, { id: 'sep', label: '', separator: true }, { id: 'delete', label: 'Delete', icon: 'trash', danger: true }]"
        arrow
        @row-click="(r: any) => router.push(`/analytics/dashboards/${r.id}`)"
        @row-action="({ action, row }: any) => action === 'open' ? router.push(`/analytics/dashboards/${row.id}`) : action === 'delete' ? deleteTarget = row : null">
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

    <Modal
      v-if="showCreate"
      title="New Dashboard"
      icon="dashboard"
      :accent="accent"
      @close="showCreate = false">
      <TextInput
        v-model="newKey"
        label="Key"
        mono
        placeholder="dashboard-key"
        autofocus />
      <TextInput v-model="newName" label="Name" placeholder="Dashboard name" />
      <Textarea v-model="newDesc" label="Description" :rows="2" />
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showCreate = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!newKey.trim() || !newName.trim() || saving"
          @click="handleCreate">Create</Button>
      </template>
    </Modal>
    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete" />
    <AnalyticsBulkPermissionsModal
      v-if="showBulkPermissions"
      entity-type="dashboards"
      :entity-ids="[...selectedIds]"
      :accent="accent"
      @applied="refresh"
      @close="showBulkPermissions = false" />
  </PageShell>
</template>

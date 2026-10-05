<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const scriptsGql = gql`
  query GetScripts {
    scripts { all { id key name description type enabled public } }
  }
`
const deleteGql = gql`
  mutation DeleteScript($id: UUID!) { scripts { deleteScript(id: $id) } }
`

interface Script {
  id: string
  key: string
  name: string
  description: string
  type: string
  enabled: boolean
  public: boolean
}

const { data, status, refresh } = useAsyncQuery<{ scripts: { all: Script[] } }>(
  'scripts-list', scriptsGql, {},
)
const scripts = computed(() => data.value?.scripts?.all ?? [])
const deleteTarget = ref<Script | null>(null)
const deleteLoading = ref(false)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'key', label: 'Key', width: '1fr', muted: true },
  { key: 'type', label: 'Type', width: '120px' },
  { key: 'status', label: 'Status', width: '120px' },
]

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: deleteTarget.value.id })
    deleteTarget.value = null
    toast.success('Deleted')
    refresh()
  } catch {
    toast.error('Failed')
  } finally {
    deleteLoading.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Scripts')"
        title="Scripts"
        :subtitle="`${scripts.length} scripts`">
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="router.push('/scripts/new')">New Script</Button>
        </template>
      </PageHeader>
    </template>
    <SectionCard title="Scripts">
      <GlassTable
        :columns="columns"
        :rows="scripts"
        :loading="status === 'pending' && scripts.length === 0"
        empty-text="No scripts."
        :row-actions="() => [
          { id: 'open', label: 'Edit', icon: 'eye' },
          { id: 'sep', label: '', separator: true },
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        arrow
        @row-click="(r: any) => router.push(`/scripts/${r.id}`)"
        @row-action="({ action, row }: any) => action === 'open'
          ? router.push(`/scripts/${row.id}`)
          : action === 'delete' ? deleteTarget = row : null">
        <template #col-name="{ row }">
          <span style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span>
        </template>
        <template #col-key="{ row }">
          <span class="mono">{{ row.key }}</span>
        </template>
        <template #col-type="{ row }">
          <Badge>{{ row.type }}</Badge>
        </template>
        <template #col-status="{ row }">
          <Badge :color="row.enabled ? '#34d99a' : 'var(--fg-3)'">
            {{ row.enabled ? 'Enabled' : 'Disabled' }}
          </Badge>
        </template>
      </GlassTable>
    </SectionCard>
    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete" />
  </PageShell>
</template>

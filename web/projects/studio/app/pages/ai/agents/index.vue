<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, OverflowMenuItem } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const agentsGql = gql`
  query GetAgents { ai { agents { all { id key name description } } } }
`
const deleteGql = gql`
  mutation DeleteAgent($id: UUID!) { ai { agents { delete(id: $id) } } }
`

interface Agent { id: string; key: string; name: string; description: string | null }

const { data, status, refresh } = useAsyncQuery<{
  ai: { agents: { all: Agent[] } }
}>('ai-agents-list', agentsGql, {})

const agents = computed(() => data.value?.ai?.agents?.all ?? [])
const isLoading = computed(() => status.value === 'pending')
const deleteTarget = ref<Agent | null>(null)
const deleteLoading = ref(false)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'key', label: 'Key', width: '1fr', muted: true },
  { key: 'description', label: 'Description', width: '1fr', muted: true },
]

function getRowActions(): OverflowMenuItem[] {
  return [
    { id: 'open', label: 'Edit', icon: 'eye' },
    { id: 'copy', label: 'Copy ID', icon: 'copy' },
    { id: 'sep', label: '', separator: true },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  ]
}

function onRowAction(action: string, row: Agent) {
  if (action === 'open') router.push(`/ai/agents/${row.id}`)
  else if (action === 'copy') { navigator.clipboard.writeText(row.id); toast.success('ID copied') }
  else if (action === 'delete') deleteTarget.value = row
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: deleteTarget.value.id })
    deleteTarget.value = null
    toast.success('Agent deleted')
    refresh()
  } catch { toast.error('Failed to delete') }
  finally { deleteLoading.value = false }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('AI', 'Agents')"
        title="AI Agents"
        :subtitle="`${agents.length} agents`">
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="router.push('/ai/agents/new')">New Agent</Button>
        </template>
      </PageHeader>
    </template>
    <SectionCard title="Agents">
      <GlassTable
        :columns="columns"
        :rows="agents"
        :loading="isLoading && agents.length === 0"
        empty-text="No agents configured."
        :row-actions="getRowActions"
        arrow
        @row-click="(row: any) => router.push(`/ai/agents/${row.id}`)"
        @row-action="({ action, row }) => onRowAction(action, row as Agent)">
        <template #col-name="{ row }"><span style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span></template>
        <template #col-key="{ row }"><span class="mono">{{ row.key }}</span></template>
        <template #col-description="{ row }">{{ row.description ?? '—' }}</template>
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

<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()
const toolsGql = gql`query GetAgentTools { ai { agentTools { all { id key name description } } } }`
const deleteGql = gql`mutation DeleteAgentTool($id: UUID!) { ai { agentTools { delete(id: $id) } } }`
interface Tool { id: string; key: string; name: string; description: string | null }
const { data, status, refresh } = useAsyncQuery<{ ai: { agentTools: { all: Tool[] } } }>('ai-agent-tools-list', toolsGql, {})
const tools = computed(() => data.value?.ai?.agentTools?.all ?? [])
const deleteTarget = ref<Tool | null>(null)
const deleteLoading = ref(false)
const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'key', label: 'Key', width: '1fr', muted: true },
  { key: 'description', label: 'Description', width: '1fr', muted: true },
]
async function confirmDelete() {
  if (!deleteTarget.value) return; deleteLoading.value = true
  try { await gqlMutation(deleteGql, { id: deleteTarget.value.id }); deleteTarget.value = null; toast.success('Deleted'); refresh() }
  catch { toast.error('Failed') } finally { deleteLoading.value = false }
}
</script>
<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('AI', 'Agent Tools')"
        title="Agent Tools"
        :subtitle="`${tools.length} tools`">
        <template #actions><Button
          primary
          icon="plus"
          size="sm"
          :accent="accent"
          @click="router.push('/ai/agent-tools/new')">New Tool</Button></template>
      </PageHeader>
    </template>
    <SectionCard title="Tools">
      <GlassTable
        :columns="columns"
        :rows="tools"
        :loading="status === 'pending' && tools.length === 0"
        empty-text="No agent tools."
        :row-actions="() => [{ id: 'open', label: 'Edit', icon: 'eye' }, { id: 'sep', label: '', separator: true }, { id: 'delete', label: 'Delete', icon: 'trash', danger: true }]"
        arrow
        @row-click="(r: any) => router.push(`/ai/agent-tools/${r.id}`)"
        @row-action="({ action, row }: any) => action === 'open' ? router.push(`/ai/agent-tools/${row.id}`) : action === 'delete' ? deleteTarget = row : null">
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
  </PageShell>
</template>

<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const serversGql = gql`
  query GetMcpServers { ai { mcp { servers { all { id key name description transportType enabled } } } } }
`
const deleteGql = gql`
  mutation DeleteMcpServer($id: UUID!) { ai { mcp { servers { delete(id: $id) } } } }
`

interface McpServer { id: string; key: string; name: string; description: string | null; transportType: string; enabled: boolean }

const { data, status, refresh } = useAsyncQuery<{ ai: { mcp: { servers: { all: McpServer[] } } } }>('ai-mcp-servers-list', serversGql, {})
const servers = computed(() => data.value?.ai?.mcp?.servers?.all ?? [])
const deleteTarget = ref<McpServer | null>(null)
const deleteLoading = ref(false)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'key', label: 'Key', width: '1fr', muted: true },
  { key: 'transport', label: 'Transport', width: '120px' },
  { key: 'enabled', label: 'Enabled', width: '80px' },
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
        :breadcrumb="buildBreadcrumb('AI', 'MCP Servers')"
        title="MCP Servers"
        :subtitle="`${servers.length} servers`">
        <template #actions><Button
          primary
          icon="plus"
          size="sm"
          :accent="accent"
          @click="router.push('/ai/mcp-servers/new')">New Server</Button></template>
      </PageHeader>
    </template>
    <SectionCard title="MCP Servers">
      <GlassTable
        :columns="columns"
        :rows="servers"
        :loading="status === 'pending' && servers.length === 0"
        empty-text="No MCP servers."
        arrow
        @row-click="(r: any) => router.push(`/ai/mcp-servers/${r.id}`)">
        <template #col-name="{ row }"><span style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span></template>
        <template #col-key="{ row }"><span class="mono">{{ row.key }}</span></template>
        <template #col-transport="{ row }"><Badge :color="accent">{{ row.transportType }}</Badge></template>
        <template #col-enabled="{ row }"><span :class="['dot', row.enabled ? 'ok' : 'err']" /></template>
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

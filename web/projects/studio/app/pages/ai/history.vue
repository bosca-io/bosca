<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const sessionsGql = gql`
  query GetAllChatSessions { chatSessions { all { id title modified } } }
`
const deleteGql = gql`
  mutation DeleteChatSession($id: UUID!) { chatSessions { delete(id: $id) } }
`

interface Session { id: string; title: string; modified: string }

const { data, status, refresh } = useAsyncQuery<{ chatSessions: { all: Session[] } }>(
  'kit-sessions-list', sessionsGql, {}, { server: false },
)
const sessions = computed(() => data.value?.chatSessions?.all ?? [])
const deleteTarget = ref<Session | null>(null)
const deleteLoading = ref(false)

const columns: GlassTableColumn[] = [
  { key: 'title', label: 'Title', width: 'minmax(200px, 2fr)' },
  { key: 'modified', label: 'Last Modified', width: '180px', muted: true },
]

function formatDate(iso: string): string {
  try { return new Date(iso).toLocaleString() } catch { return iso }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: deleteTarget.value.id })
    deleteTarget.value = null
    toast.success('Session deleted')
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
        :breadcrumb="buildBreadcrumb('AI', 'History')"
        title="Kit History"
        :subtitle="`${sessions.length} sessions`"
      >
        <template #actions>
          <Button
            primary
            size="sm"
            icon="plus"
            :accent="accent"
            @click="router.push('/ai/chat')">New Chat</Button>
        </template>
      </PageHeader>
    </template>
    <SectionCard title="Sessions">
      <GlassTable
        :columns="columns"
        :rows="sessions"
        :loading="status === 'pending' && sessions.length === 0"
        empty-text="No Kit sessions yet."
        :row-actions="() => [
          { id: 'open', label: 'Open', icon: 'eye' },
          { id: 'sep', label: '', separator: true },
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        arrow
        @row-click="(r: any) => router.push(`/ai/chat/${r.id}`)"
        @row-action="({ action, row }: any) => action === 'open' ? router.push(`/ai/chat/${row.id}`) : action === 'delete' ? deleteTarget = row : null"
      >
        <template #col-title="{ row }"><span style="font-weight: 500; color: var(--fg-0)">{{ row.title }}</span></template>
        <template #col-modified="{ row }">{{ formatDate(row.modified) }}</template>
      </GlassTable>
    </SectionCard>
    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.title}'?`"
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete" />
  </PageShell>
</template>

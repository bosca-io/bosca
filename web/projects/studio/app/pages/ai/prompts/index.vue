<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const promptsGql = gql`
  query GetPrompts { ai { prompts { all { id key name description } } } }
`
const deleteGql = gql`
  mutation DeletePrompt($id: UUID!) { ai { prompts { delete(id: $id) } } }
`

interface Prompt { id: string; key: string; name: string; description: string | null }

const { data, status, refresh } = useAsyncQuery<{ ai: { prompts: { all: Prompt[] } } }>('ai-prompts-list', promptsGql, {})
const prompts = computed(() => data.value?.ai?.prompts?.all ?? [])
const deleteTarget = ref<Prompt | null>(null)
const deleteLoading = ref(false)

const columns: GlassTableColumn[] = [
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
        :breadcrumb="buildBreadcrumb('AI', 'Prompts')"
        title="Prompts"
        :subtitle="`${prompts.length} prompts`">
        <template #actions><Button
          primary
          icon="plus"
          size="sm"
          :accent="accent"
          @click="router.push('/ai/prompts/new')">New Prompt</Button></template>
      </PageHeader>
    </template>
    <SectionCard title="Prompts">
      <GlassTable
        :columns="columns"
        :rows="prompts"
        :loading="status === 'pending' && prompts.length === 0"
        empty-text="No prompts."
        :row-actions="() => [{ id: 'open', label: 'Edit', icon: 'eye' }, { id: 'sep', label: '', separator: true }, { id: 'delete', label: 'Delete', icon: 'trash', danger: true }]"
        arrow
        @row-click="(r: any) => router.push(`/ai/prompts/${r.id}`)"
        @row-action="({ action, row }: any) => action === 'open' ? router.push(`/ai/prompts/${row.id}`) : action === 'delete' ? deleteTarget = row : null">
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

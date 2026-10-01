<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const modelsGql = gql`
  query GetModels { ai { models { all { id key name type description } } } }
`
const deleteGql = gql`
  mutation DeleteModel($id: UUID!) { ai { models { delete(id: $id) } } }
`

interface Model { id: string; key: string; name: string; type: string; description: string | null }

const { data, status, refresh } = useAsyncQuery<{ ai: { models: { all: Model[] } } }>('ai-models-list', modelsGql, {})
const models = computed(() => data.value?.ai?.models?.all ?? [])
const deleteTarget = ref<Model | null>(null)
const deleteLoading = ref(false)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'key', label: 'Key', width: '1fr', muted: true },
  { key: 'type', label: 'Type', width: '120px' },
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
        :breadcrumb="buildBreadcrumb('AI', 'Models')"
        title="LLM Models"
        :subtitle="`${models.length} models`">
        <template #actions><Button
          primary
          icon="plus"
          size="sm"
          :accent="accent"
          @click="router.push('/ai/models/new')">New Model</Button></template>
      </PageHeader>
    </template>
    <SectionCard title="Models">
      <GlassTable
        :columns="columns"
        :rows="models"
        :loading="status === 'pending' && models.length === 0"
        empty-text="No models configured."
        :row-actions="() => [{ id: 'open', label: 'Edit', icon: 'eye' }, { id: 'sep', label: '', separator: true }, { id: 'delete', label: 'Delete', icon: 'trash', danger: true }]"
        arrow
        @row-click="(r: any) => router.push(`/ai/models/${r.id}`)"
        @row-action="({ action, row }: any) => action === 'open' ? router.push(`/ai/models/${row.id}`) : action === 'delete' ? deleteTarget = row : null">
        <template #col-name="{ row }"><span style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span></template>
        <template #col-key="{ row }"><span class="mono">{{ row.key }}</span></template>
        <template #col-type="{ row }"><Badge :color="accent">{{ row.type }}</Badge></template>
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

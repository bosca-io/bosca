<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const layersGql = gql`
  query GetAllExclusionLayers {
    exclusionLayers { all { id name description experiments { id } created } }
  }
`

const addGql = gql`
  mutation AddExclusionLayer($name: String!, $description: String!) {
    exclusionLayers { add(layer: { name: $name, description: $description }) { id } }
  }
`

const deleteGql = gql`
  mutation DeleteExclusionLayer($id: UUID!) {
    exclusionLayers { delete(id: $id) }
  }
`

interface Layer { id: string; name: string; description: string | null; experiments: { id: string }[]; created: string }

const { data, status, refresh } = useAsyncQuery<{
  exclusionLayers: { all: Layer[] }
}>('exclusion-layers', layersGql, {})

const layers = computed(() => data.value?.exclusionLayers?.all ?? [])
const isLoading = computed(() => status.value === 'pending')

const showCreate = ref(false)
const newName = ref('')
const newDesc = ref('')
const saving = ref(false)
const deleteTarget = ref<Layer | null>(null)
const deleteLoading = ref(false)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'desc', label: 'Description', width: '1fr', muted: true },
  { key: 'experiments', label: 'Experiments', width: '100px', align: 'right' },
]

async function handleCreate() {
  saving.value = true
  try {
    const result = await gqlMutation<{ exclusionLayers: { add: { id: string } } }>(addGql, { name: newName.value, description: newDesc.value })
    showCreate.value = false
    newName.value = ''
    newDesc.value = ''
    toast.success('Layer created')
    router.push(`/experiments/layers/${result.exclusionLayers.add.id}`)
  } catch {
    toast.error('Failed to create')
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: deleteTarget.value.id })
    deleteTarget.value = null
    toast.success('Layer deleted')
    refresh()
  } catch {
    toast.error('Failed to delete')
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
        :breadcrumb="buildBreadcrumb('Experiments', 'Exclusion Layers')"
        title="Exclusion Layers"
        :subtitle="`${layers.length} layers`">
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreate = true">New Layer</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Layers">
      <GlassTable
        :columns="columns"
        :rows="layers"
        :loading="isLoading && layers.length === 0"
        empty-text="No exclusion layers."
        arrow
        @row-click="(row: Layer) => router.push(`/experiments/layers/${row.id}`)"
      >
        <template #col-name="{ row }"><span style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span></template>
        <template #col-desc="{ row }">{{ row.description ?? '—' }}</template>
        <template #col-experiments="{ row }"><span class="mono tabular">{{ row.experiments?.length ?? 0 }}</span></template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="showCreate"
      title="New Exclusion Layer"
      icon="columns"
      :accent="accent"
      @close="showCreate = false">
      <TextInput
        v-model="newName"
        label="Name"
        placeholder="Layer name"
        autofocus />
      <Textarea v-model="newDesc" label="Description" :rows="2" />
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showCreate = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!newName.trim() || saving"
          @click="handleCreate">Create</Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete" />
  </PageShell>
</template>

<script setup lang="ts">
import gql from 'graphql-tag'
import JsonEditorVue from 'json-editor-vue'
import 'vanilla-jsoneditor/themes/jse-theme-dark.css'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const sourcesGql = gql`
  query GetSources { content { sources { all { id name description configuration } } } }
`
const addGql = gql`
  mutation AddSource($source: SourceInput!) { content { sources { add(source: $source) { id } } } }
`
const editGql = gql`
  mutation EditSource($id: UUID!, $source: SourceInput!) { content { sources { edit(id: $id, source: $source) { id } } } }
`
const deleteGql = gql`
  mutation DeleteSource($id: UUID!) { content { sources { delete(id: $id) } } }
`

interface Source {
  id: string
  name: string
  description: string | null
  configuration: Record<string, unknown> | null
}

const { data, status, refresh } = useAsyncQuery<{ content: { sources: { all: Source[] } } }>('cms-sources', sourcesGql, {})
const sources = computed(() => data.value?.content?.sources?.all ?? [])

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'description', label: 'Description', width: 'minmax(200px, 2fr)', muted: true },
]

const showModal = ref(false)
const editingSource = ref<Source | null>(null)
const deleteTarget = ref<Source | null>(null)
const deleteLoading = ref(false)
const saving = ref(false)

const formName = ref('')
const formDescription = ref('')
const formConfiguration = ref<Record<string, unknown>>({})

function openCreate() {
  editingSource.value = null
  formName.value = ''
  formDescription.value = ''
  formConfiguration.value = {}
  showModal.value = true
}

function openEdit(source: Source) {
  editingSource.value = source
  formName.value = source.name
  formDescription.value = source.description ?? ''
  formConfiguration.value = source.configuration ?? {}
  showModal.value = true
}

function buildInput() {
  return {
    name: formName.value,
    description: formDescription.value || null,
    configuration: formConfiguration.value,
  }
}

async function handleSave() {
  if (!formName.value.trim()) return
  saving.value = true
  try {
    if (editingSource.value) {
      await gqlMutation(editGql, { id: editingSource.value.id, source: buildInput() })
      toast.success('Source updated')
    } else {
      await gqlMutation(addGql, { source: buildInput() })
      toast.success('Source created')
    }
    showModal.value = false
    refresh()
  } catch {
    toast.error('Failed to save source')
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: deleteTarget.value.id })
    toast.success('Source deleted')
    deleteTarget.value = null
    refresh()
  } catch {
    toast.error('Failed to delete source')
  } finally {
    deleteLoading.value = false
  }
}

function onRowAction({ action, row }: { action: string; row: Source }) {
  if (action === 'edit') openEdit(row)
  else if (action === 'delete') deleteTarget.value = row
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Settings', 'Sources')"
        title="Content Sources"
        :subtitle="`${sources.length} sources`">
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New Source</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Sources">
      <GlassTable
        :columns="columns"
        :rows="sources"
        :loading="status === 'pending' && sources.length === 0"
        empty-text="No sources configured."
        :row-actions="() => [
          { id: 'edit', label: 'Edit', icon: 'pencil' },
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        @row-click="(row: Source) => openEdit(row)"
        @row-action="onRowAction"
      >
        <template #col-name="{ row }"><span style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span></template>
        <template #col-description="{ row }">{{ row.description ?? '—' }}</template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="showModal"
      :title="editingSource ? 'Edit Source' : 'New Source'"
      icon="database"
      :accent="accent"
      @close="showModal = false">
      <div class="source-form">
        <TextInput
          v-model="formName"
          label="Name"
          placeholder="Source name"
          autofocus />
        <TextInput v-model="formDescription" label="Description" placeholder="Optional description" />

        <div class="field-group">
          <label class="field-label">Configuration</label>
          <JsonEditorVue
            v-model="formConfiguration"
            class="json-editor"
            mode="text"
            :main-menu-bar="false"
            :status-bar="false"
            style="height: 200px" />
        </div>
      </div>
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showModal = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!formName.trim() || saving"
          @click="handleSave">
          {{ saving ? 'Saving…' : (editingSource ? 'Update' : 'Create') }}
        </Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      subtitle="This action cannot be undone."
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </PageShell>
</template>

<style scoped>
.source-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-2);
}
</style>

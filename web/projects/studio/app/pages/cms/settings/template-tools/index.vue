<script setup lang="ts">
import gql from 'graphql-tag'
import JsonEditorVue from 'json-editor-vue'
import 'vanilla-jsoneditor/themes/jse-theme-dark.css'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const toolsGql = gql`
  query GetTemplateAttributeTools {
    content { templateAttributeTools { all { id key name description query resultPath configuration } } }
  }
`
const addGql = gql`
  mutation AddTemplateAttributeTool($tool: TemplateAttributeToolInput!) {
    content { templateAttributeTools { add(tool: $tool) { id name } } }
  }
`
const editGql = gql`
  mutation EditTemplateAttributeTool($id: UUID!, $tool: TemplateAttributeToolInput!) {
    content { templateAttributeTools { edit(id: $id, tool: $tool) { id name } } }
  }
`
const deleteGql = gql`
  mutation DeleteTemplateAttributeTool($id: UUID!) {
    content { templateAttributeTools { delete(id: $id) } }
  }
`

interface TemplateTool {
  id: string
  key: string
  name: string
  description: string | null
  query: string | null
  resultPath: string | null
  configuration: Record<string, unknown> | null
}

const { data, status, refresh } = useAsyncQuery<{ content: { templateAttributeTools: { all: TemplateTool[] } } }>('cms-template-tools', toolsGql, {})
const tools = computed(() => data.value?.content?.templateAttributeTools?.all ?? [])

const columns: GlassTableColumn[] = [
  { key: 'key', label: 'Key', width: 'minmax(120px, 1fr)' },
  { key: 'name', label: 'Name', width: 'minmax(160px, 2fr)' },
  { key: 'description', label: 'Description', width: 'minmax(200px, 2fr)', muted: true },
]

const showModal = ref(false)
const editingTool = ref<TemplateTool | null>(null)
const deleteTarget = ref<TemplateTool | null>(null)
const deleteLoading = ref(false)
const saving = ref(false)

const formKey = ref('')
const formName = ref('')
const formDescription = ref('')
const formQuery = ref('')
const formResultPath = ref('')
const formConfiguration = ref<Record<string, unknown>>({})

function openCreate() {
  editingTool.value = null
  formKey.value = ''
  formName.value = ''
  formDescription.value = ''
  formQuery.value = ''
  formResultPath.value = ''
  formConfiguration.value = {}
  showModal.value = true
}

function openEdit(tool: TemplateTool) {
  editingTool.value = tool
  formKey.value = tool.key
  formName.value = tool.name
  formDescription.value = tool.description ?? ''
  formQuery.value = tool.query ?? ''
  formResultPath.value = tool.resultPath ?? ''
  formConfiguration.value = tool.configuration ?? {}
  showModal.value = true
}

function buildInput() {
  return {
    key: formKey.value,
    name: formName.value,
    description: formDescription.value || null,
    query: formQuery.value || null,
    resultPath: formResultPath.value || null,
    configuration: formConfiguration.value,
  }
}

async function handleSave() {
  if (!formKey.value.trim() || !formName.value.trim()) return
  saving.value = true
  try {
    if (editingTool.value) {
      await gqlMutation(editGql, { id: editingTool.value.id, tool: buildInput() })
      toast.success('Tool updated')
    } else {
      await gqlMutation(addGql, { tool: buildInput() })
      toast.success('Tool created')
    }
    showModal.value = false
    refresh()
  } catch {
    toast.error('Failed to save tool')
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: deleteTarget.value.id })
    toast.success('Tool deleted')
    deleteTarget.value = null
    refresh()
  } catch {
    toast.error('Failed to delete tool')
  } finally {
    deleteLoading.value = false
  }
}

function onRowAction({ action, row }: { action: string; row: TemplateTool }) {
  if (action === 'edit') openEdit(row)
  else if (action === 'delete') deleteTarget.value = row
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Settings', 'Template Tools')"
        title="Template Attribute Tools"
        :subtitle="`${tools.length} tools`">
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New Tool</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Tools">
      <GlassTable
        :columns="columns"
        :rows="tools"
        :loading="status === 'pending' && tools.length === 0"
        empty-text="No template attribute tools configured."
        :row-actions="() => [
          { id: 'edit', label: 'Edit', icon: 'pencil' },
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        @row-click="(row: TemplateTool) => openEdit(row)"
        @row-action="onRowAction"
      >
        <template #col-key="{ row }"><span class="mono">{{ row.key }}</span></template>
        <template #col-name="{ row }"><span style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span></template>
        <template #col-description="{ row }">{{ row.description ?? '—' }}</template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="showModal"
      :title="editingTool ? 'Edit Tool' : 'New Tool'"
      icon="wand"
      :accent="accent"
      @close="showModal = false">
      <div class="tool-form">
        <TextInput
          v-model="formKey"
          label="Key"
          placeholder="tool-key"
          autofocus />
        <TextInput v-model="formName" label="Name" placeholder="Tool Name" />
        <TextInput v-model="formDescription" label="Description" placeholder="Optional description" />
        <TextInput v-model="formResultPath" label="Result Path" placeholder="e.g. data.results" />

        <div class="field-group">
          <label class="field-label">Query</label>
          <CodeEditor
            v-model="formQuery"
            language="graphql"
            :rows="8"
            placeholder="GraphQL query..." />
        </div>

        <div class="field-group">
          <label class="field-label">Configuration</label>
          <JsonEditorVue
            v-model="formConfiguration"
            class="json-editor"
            mode="text"
            :main-menu-bar="false"
            :status-bar="false"
            style="height: 180px" />
        </div>
      </div>
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showModal = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!formKey.trim() || !formName.trim() || saving"
          @click="handleSave">
          {{ saving ? 'Saving…' : (editingTool ? 'Update' : 'Create') }}
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
.tool-form {
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

.mono {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
}
</style>

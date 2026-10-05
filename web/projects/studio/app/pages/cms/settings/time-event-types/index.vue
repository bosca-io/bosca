<script setup lang="ts">
import gql from 'graphql-tag'
import JsonEditorVue from 'json-editor-vue'
import 'vanilla-jsoneditor/themes/jse-theme-dark.css'
import type { GlassTableColumn } from '@bosca/ui'
import type { TemplateAttribute, TemplateAttributeInput, TemplateAttributeTool } from '~/types/graphql'
import { toEditable } from '~/utils/toEditable'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const typesGql = gql`
  query GetTimeEventTypes {
    timeEventTypes {
      id name description schema configuration
      attributes {
        key name description type configuration ui list location supplementaryKey
        workflows { autoRun }
        tools { id name description query resultPath }
      }
    }
  }
`

const toolsGql = gql`
  query GetTemplateAttributeTools {
    content { templateAttributeTools { all { id key name description query resultPath configuration } } }
  }
`
const addGql = gql`
  mutation AddTimeEventType($type: TimeEventTypeInput!) {
    timeEvents { addType(type: $type) { id } }
  }
`
const editGql = gql`
  mutation EditTimeEventType($id: String!, $type: TimeEventTypeInput!) {
    timeEvents { editType(id: $id, type: $type) { id } }
  }
`
const deleteGql = gql`
  mutation DeleteTimeEventType($id: String!) {
    timeEvents { deleteType(id: $id) }
  }
`
const setAttributesGql = gql`
  mutation SetTimeEventTypeAttributes($id: String!, $attrs: [TemplateAttributeInput!]!) {
    timeEvents { setTypeAttributes(typeId: $id, attributes: $attrs) { id } }
  }
`

interface TimeEventType {
  id: string
  name: string
  description: string | null
  schema: Record<string, unknown> | null
  configuration: Record<string, unknown> | null
  attributes: TemplateAttribute[]
}

interface ToolsResponse {
  content?: { templateAttributeTools?: { all?: TemplateAttributeTool[] } }
}

const { data, status, refresh } = useAsyncQuery<{ timeEventTypes: TimeEventType[] }>('cms-time-event-types', typesGql, {})
const types = computed(() => data.value?.timeEventTypes ?? [])

const { data: toolsData } = useAsyncQuery<ToolsResponse>('cms-time-event-type-tools', toolsGql, {})
const tools = computed<TemplateAttributeTool[]>(() => toolsData.value?.content?.templateAttributeTools?.all || [])

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 2fr)' },
  { key: 'description', label: 'Description', width: 'minmax(200px, 2fr)', muted: true },
  { key: 'attributes', label: 'Attributes', width: '100px', muted: true },
]

// Type CRUD
const showTypeModal = ref(false)
const editingType = ref<TimeEventType | null>(null)
const deleteTarget = ref<TimeEventType | null>(null)
const deleteLoading = ref(false)
const saving = ref(false)

const formId = ref('')
const formName = ref('')
const formDescription = ref('')
const formSchema = ref<Record<string, unknown>>({})
const formConfiguration = ref<Record<string, unknown>>({})

function openCreate() {
  editingType.value = null
  formId.value = ''
  formName.value = ''
  formDescription.value = ''
  formSchema.value = {}
  formConfiguration.value = {}
  showTypeModal.value = true
}

function openEdit(type: TimeEventType) {
  editingType.value = type
  formId.value = type.id
  formName.value = type.name
  formDescription.value = type.description ?? ''
  formSchema.value = type.schema ?? {}
  formConfiguration.value = type.configuration ?? {}
  showTypeModal.value = true
}

function buildTypeInput() {
  const input: Record<string, unknown> = {
    name: formName.value,
    description: formDescription.value || null,
    schema: formSchema.value,
    configuration: formConfiguration.value,
  }
  if (!editingType.value) {
    input.id = formId.value || undefined
  }
  return input
}

async function handleSaveType() {
  if (!formName.value.trim()) return
  saving.value = true
  try {
    if (editingType.value) {
      await gqlMutation(editGql, { id: editingType.value.id, type: buildTypeInput() })
      toast.success('Type updated')
    } else {
      await gqlMutation(addGql, { type: buildTypeInput() })
      toast.success('Type created')
    }
    showTypeModal.value = false
    refresh()
  } catch (e: unknown) {
    toast.error((e instanceof Error ? e.message : null) || 'Failed to save type')
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: deleteTarget.value.id })
    toast.success('Type deleted')
    deleteTarget.value = null
    refresh()
  } catch {
    toast.error('Failed to delete type')
  } finally {
    deleteLoading.value = false
  }
}

// Attributes management
const showAttributesModal = ref(false)
const attributesType = ref<TimeEventType | null>(null)
const editableAttributes = ref<TemplateAttribute[]>([])
const expandedAttr = ref<number | null>(null)
const savingAttributes = ref(false)

function openAttributes(type: TimeEventType) {
  attributesType.value = type
  editableAttributes.value = (type.attributes ?? []).map(
    (a) => toEditable(a as unknown as Record<string, unknown>) as unknown as TemplateAttribute,
  )
  expandedAttr.value = null
  showAttributesModal.value = true
}

function addAttribute() {
  editableAttributes.value.push({
    key: '',
    name: '',
    description: '',
    type: 'STRING',
    ui: 'INPUT',
    list: false,
    location: 'ITEM',
    supplementaryKey: '',
    configuration: null,
    workflows: [],
    tools: [],
  } as unknown as TemplateAttribute)
  expandedAttr.value = editableAttributes.value.length - 1
}

function removeAttribute(index: number) {
  editableAttributes.value.splice(index, 1)
  expandedAttr.value = null
}

async function saveAttributes() {
  if (!attributesType.value) return
  savingAttributes.value = true
  try {
    const attrs: TemplateAttributeInput[] = editableAttributes.value.map((a) => ({
      key: a.key,
      name: a.name,
      description: a.description,
      type: a.type,
      ui: a.ui,
      list: a.list,
      location: a.location,
      supplementaryKey: a.supplementaryKey,
      configuration: a.configuration,
      workflows: [],
      tools: a.tools?.map((t) => ({
        id: t.id,
        name: t.name,
        description: t.description,
        query: t.query,
        resultPath: t.resultPath,
      })),
    }))
    await gqlMutation(setAttributesGql, { id: attributesType.value.id, attrs })
    toast.success('Attributes saved')
    showAttributesModal.value = false
    refresh()
  } catch (e: unknown) {
    toast.error((e instanceof Error ? e.message : null) || 'Failed to save attributes')
  } finally {
    savingAttributes.value = false
  }
}

function onRowAction({ action, row }: { action: string; row: TimeEventType }) {
  if (action === 'edit') openEdit(row)
  else if (action === 'attributes') openAttributes(row)
  else if (action === 'delete') deleteTarget.value = row
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Settings', 'Time Event Types')"
        title="Time Event Types"
        :subtitle="`${types.length} types`">
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New Type</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Types">
      <GlassTable
        :columns="columns"
        :rows="types"
        :loading="status === 'pending' && types.length === 0"
        empty-text="No time event types configured."
        :row-actions="() => [
          { id: 'edit', label: 'Edit', icon: 'pencil' },
          { id: 'attributes', label: 'Attributes', icon: 'list' },
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        @row-click="(row: TimeEventType) => openEdit(row)"
        @row-action="onRowAction"
      >
        <template #col-name="{ row }"><span style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span></template>
        <template #col-description="{ row }">{{ row.description ?? '—' }}</template>
        <template #col-attributes="{ row }"><Badge :color="accent">{{ row.attributes?.length ?? 0 }}</Badge></template>
      </GlassTable>
    </SectionCard>

    <!-- Type Add/Edit Modal -->
    <Modal
      v-if="showTypeModal"
      :title="editingType ? 'Edit Type' : 'New Type'"
      icon="clock"
      :accent="accent"
      @close="showTypeModal = false">
      <div class="type-form">
        <TextInput
          v-if="!editingType"
          v-model="formId"
          label="ID (optional)"
          placeholder="Auto-generated if blank" />
        <TextInput
          v-model="formName"
          label="Name"
          placeholder="Type name"
          autofocus />
        <TextInput v-model="formDescription" label="Description" placeholder="Optional description" />

        <div class="field-group">
          <label class="field-label">Schema</label>
          <JsonEditorVue
            v-model="formSchema"
            class="json-editor"
            mode="text"
            :main-menu-bar="false"
            :status-bar="false"
            style="height: 160px" />
        </div>

        <div class="field-group">
          <label class="field-label">Configuration</label>
          <JsonEditorVue
            v-model="formConfiguration"
            class="json-editor"
            mode="text"
            :main-menu-bar="false"
            :status-bar="false"
            style="height: 160px" />
        </div>
      </div>
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showTypeModal = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!formName.trim() || saving"
          @click="handleSaveType">
          {{ saving ? 'Saving…' : (editingType ? 'Update' : 'Create') }}
        </Button>
      </template>
    </Modal>

    <!-- Attributes Modal -->
    <Modal
      v-if="showAttributesModal"
      :title="`Attributes: ${attributesType?.name}`"
      icon="list"
      :accent="accent"
      width="760px"
      @close="showAttributesModal = false">
      <div class="attributes-form">
        <div v-if="!editableAttributes.length" class="attrs-empty">
          No attributes defined. Add one to get started.
        </div>
        <div v-for="(attr, idx) in editableAttributes" :key="idx" class="attr-item">
          <div class="attr-header" @click="expandedAttr = expandedAttr === idx ? null : idx">
            <Icon
              name="chevron"
              :size="12"
              color="var(--fg-3)"
              :style="{ transform: expandedAttr === idx ? 'rotate(90deg)' : 'none', transition: 'transform 0.15s' }" />
            <span class="attr-label">{{ attr.name || attr.key || 'New Attribute' }}</span>
            <Badge :color="'var(--fg-3)'">{{ attr.type }}</Badge>
          </div>
          <TemplatesAttribute
            v-if="expandedAttr === idx"
            v-model:attribute="editableAttributes[idx]!"
            :tools="tools"
            :on-delete="() => removeAttribute(idx)"
          />
        </div>
        <Button
          size="sm"
          icon="plus"
          :accent="accent"
          @click="addAttribute">Add Attribute</Button>
      </div>
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showAttributesModal = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="savingAttributes"
          @click="saveAttributes">
          {{ savingAttributes ? 'Saving…' : 'Save Attributes' }}
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
.type-form {
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

.attributes-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.attrs-empty {
  font-size: 13px;
  color: var(--fg-3);
  padding: 12px 0;
}

.attr-item {
  border-radius: var(--r-sm);
  background: var(--bg-1);
  border: 1px solid var(--line);
  overflow: hidden;
}

.attr-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  cursor: pointer;
  user-select: none;
}

.attr-header:hover {
  background: var(--bg-2);
}

.attr-label {
  flex: 1;
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>

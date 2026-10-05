<script setup lang="ts">
import gql from 'graphql-tag'
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { resetFieldCounter } from '~/components/form-builder/palette-utils'
import type { SelectOption } from '@bosca/ui'

import {
  addFormSchemaPermission,
  deleteFormSchemaPermission,
  type FormSchemaInput,
  type FormSchemaPermission,
  type FormSchemaProfileMapping,
  type FormSchemaProfileMappingAttributeInput,
  type FormSchemaProfileMappingInput,
  getFormSchemaById,
  type JsonSchema,
  type ProfileVisibility,
  saveFormSchema,
  setFormSchemaPublished,
  useBoscaForms,
  type UiSchema,
  type UiSchemaNode,
  type FieldNode,
} from '@bosca/forms'

type FormSchemaType = 'SUBMISSION' | 'INTERNAL' | 'WORK_OPS'

const route = useRoute()
const router = useRouter()
const { $auth } = useNuxtApp()
const config = useRuntimeConfig()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery } = useGraphQL()
let formsState: ReturnType<typeof useBoscaForms> | null = null
if (import.meta.client) {
  formsState = useBoscaForms()
}

const isNew = computed(() => route.params.id === 'new')
const loading = ref(!isNew.value)
const saving = ref(false)

const formType = ref<FormSchemaType>('INTERNAL')
const formKey = ref('')
const formName = ref('')
const formDescription = ref('')
const formPublic = ref(false)
const formPublished = ref(false)
const publishing = ref(false)
const formConfiguration = ref<Record<string, unknown> | null>(null)

const NONE = '__none__'
const workOpsConfig = reactive({
  defaultProjectId: NONE as string,
  defaultTaskTypeId: NONE as string,
  defaultPriorityId: NONE as string,
  mappings: {
    projectId: 'projectId',
    taskTypeId: 'taskTypeId',
    statusId: 'statusId',
    priorityId: 'priorityId',
    summary: 'summary',
    descriptionMarkdown: 'descriptionMarkdown',
    assigneeProfileId: 'assigneeProfileId',
    dueDate: 'dueDate',
    startDate: 'startDate',
  } as Record<string, string>,
})

const taskFieldDescriptions: Record<string, string> = {
  projectId: 'Project',
  taskTypeId: 'Task Type',
  statusId: 'Status',
  priorityId: 'Priority',
  summary: 'Summary',
  descriptionMarkdown: 'Description',
  assigneeProfileId: 'Assignee',
  dueDate: 'Due Date',
  startDate: 'Start Date',
}

const typeOptions: SelectOption[] = [
  { label: 'Submission', value: 'SUBMISSION' },
  { label: 'Internal', value: 'INTERNAL' },
  { label: 'Work Ops', value: 'WORK_OPS' },
]

const jsonSchema = ref<JsonSchema>({
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  type: 'object',
  properties: {},
  required: [],
})
const uiSchema = ref<UiSchema>({
  version: 1,
  layout: [],
})

const selectedNodePath = ref<number[] | null>(null)
const activeTab = ref('Builder')
const showSchemaModal = ref(false)
const showGraphqlConfig = ref(false)
const showApiScriptConfig = ref(false)

const profileMappingEnabled = ref(false)
const profileMappingNameField = ref('')
const profileMappingVisibility = ref<ProfileVisibility>('USER')
const profileMappingAttributes = ref<FormSchemaProfileMappingAttributeInput[]>([])

const visibilityOptions: SelectOption[] = [
  { label: 'User', value: 'USER' },
  { label: 'Friends', value: 'FRIENDS' },
  { label: 'Friends of Friends', value: 'FRIENDS_OF_FRIENDS' },
  { label: 'Public', value: 'PUBLIC' },
  { label: 'System', value: 'SYSTEM' },
]

const allFieldKeys = computed(() => {
  const keys: string[] = []
  function walk(nodes: UiSchemaNode[]) {
    for (const n of nodes) {
      if (n.type === 'field') keys.push(n.property)
      if ((n.type === 'section' || n.type === 'row') && n.children) walk(n.children)
    }
  }
  walk(uiSchema.value.layout)
  return keys
})

const allFieldKeyOptions = computed<SelectOption[]>(() =>
  allFieldKeys.value.map(k => ({ label: k, value: k })),
)

function addProfileMappingAttribute() {
  profileMappingAttributes.value.push({ typeId: '', field: '', attributeKey: '' })
}

function removeProfileMappingAttribute(index: number) {
  profileMappingAttributes.value.splice(index, 1)
}

function buildProfileMappingInput(): FormSchemaProfileMappingInput | undefined {
  if (!profileMappingEnabled.value) return undefined
  return {
    nameField: profileMappingNameField.value,
    visibility: profileMappingVisibility.value,
    attributes: profileMappingAttributes.value.filter(a => a.typeId && a.field && a.attributeKey),
  }
}

function loadProfileMapping(mapping: FormSchemaProfileMapping | null | undefined) {
  if (!mapping) return
  profileMappingEnabled.value = true
  profileMappingNameField.value = mapping.nameField ?? ''
  profileMappingVisibility.value = mapping.visibility ?? 'USER'
  profileMappingAttributes.value = (mapping.attributes ?? []).map(a => ({ ...a }))
}

const sampleData = ref<Record<string, unknown>>({})
const isDirty = ref(false)

watch([formType, formKey, formName, formDescription, formPublic, jsonSchema, uiSchema, workOpsConfig, profileMappingEnabled, profileMappingNameField, profileMappingVisibility, profileMappingAttributes], () => {
  if (!loading.value) isDirty.value = true
}, { deep: true })

function onBeforeUnload(e: BeforeUnloadEvent) {
  if (isDirty.value) {
    e.preventDefault()
  }
}

onMounted(() => {
  window.addEventListener('beforeunload', onBeforeUnload)
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
})

const showLeaveConfirm = ref(false)
let resolveLeave: ((_value: boolean) => void) | null = null

onBeforeRouteLeave(() => {
  if (isDirty.value) {
    showLeaveConfirm.value = true
    return new Promise<boolean>((resolve) => {
      resolveLeave = resolve
    })
  }
})

function confirmLeave() {
  showLeaveConfirm.value = false
  resolveLeave?.(true)
  resolveLeave = null
}

function cancelLeave() {
  showLeaveConfirm.value = false
  resolveLeave?.(false)
  resolveLeave = null
}

// ── Permissions ──────────────────────────────────────────────
const permissions = ref<Array<{ group: { id: string; name: string }; action: string }>>([])
const permissionActions: SelectOption[] = [
  { label: 'VIEW', value: 'VIEW' },
  { label: 'EDIT', value: 'EDIT' },
  { label: 'DELETE', value: 'DELETE' },
  { label: 'MANAGE', value: 'MANAGE' },
  { label: 'LIST', value: 'LIST' },
  { label: 'EXECUTE', value: 'EXECUTE' },
]

// ── GraphQL lookups ──────────────────────────────────────────
const GET_FORM_BUILDER_DATA = gql`
  query GetFormBuilderData {
    security {
      groups {
        all(offset: 0, limit: 100) {
          id
          name
        }
      }
    }
    profiles {
      attributeTypes {
        all {
          id
          name
        }
      }
    }
  }
`
const { data: formBuilderData } = useAsyncQuery<{
  security: { groups: { all: Array<{ id: string; name: string }> } }
  profiles: { attributeTypes: { all: Array<{ id: string; name: string }> } }
}>('form-builder-data', GET_FORM_BUILDER_DATA)

const groups = computed(() => formBuilderData.value?.security?.groups?.all ?? [])
const groupOptions = computed<SelectOption[]>(() =>
  groups.value.map(g => ({ label: g.name, value: g.id })),
)
const attributeTypes = computed(() => formBuilderData.value?.profiles?.attributeTypes?.all ?? [])
const attributeTypeOptions = computed<SelectOption[]>(() =>
  attributeTypes.value.map(a => ({ label: a.name, value: a.id })),
)

const WORKOPS_LOOKUP = gql`
  query FormBuilderWorkOpsLookup {
    workOps {
      projects { all { id key name } }
      tasks {
        taskTypes { id name }
        priorities { id name }
      }
    }
  }
`
const { data: workOpsData } = useAsyncQuery<{
  workOps: {
    projects: { all: Array<{ id: string; key: string; name: string }> }
    tasks: {
      taskTypes: Array<{ id: string; name: string }>
      priorities: Array<{ id: string; name: string }>
    }
  }
}>('form-builder-workops', WORKOPS_LOOKUP)

const workOpsProjectItems = computed<SelectOption[]>(() => [
  { label: '(from form field)', value: NONE },
  ...(workOpsData.value?.workOps?.projects?.all ?? []).map(p => ({ label: `${p.key} — ${p.name}`, value: p.id })),
])
const workOpsTaskTypeItems = computed<SelectOption[]>(() => [
  { label: '(from form field)', value: NONE },
  ...(workOpsData.value?.workOps?.tasks?.taskTypes ?? []).map(t => ({ label: t.name, value: t.id })),
])
const workOpsPriorityItems = computed<SelectOption[]>(() => [
  { label: '(from form field)', value: NONE },
  ...(workOpsData.value?.workOps?.tasks?.priorities ?? []).map(p => ({ label: p.name, value: p.id })),
])

function addPermissionRow() {
  permissions.value.push({ group: { id: '', name: '' }, action: 'VIEW' })
}

async function savePermission(index: number) {
  const perm = permissions.value[index]!
  if (!perm.group.id || isNew.value) return
  const token = $auth.token ?? undefined
  try {
    await addFormSchemaPermission(
      config.public.apiUrl as string,
      route.params.id as string,
      perm.group.id,
      perm.action,
      token,
    )
  } catch (e: unknown) {
    console.error('Failed to save permission:', e)
  }
}

async function removePermission(index: number) {
  const perm = permissions.value[index]!
  if (!isNew.value && perm.group.id) {
    const token = $auth.token ?? undefined
    try {
      await deleteFormSchemaPermission(
        config.public.apiUrl as string,
        route.params.id as string,
        perm.group.id,
        perm.action,
        token,
      )
    } catch (e: unknown) {
      console.error('Failed to remove permission:', e)
      return
    }
  }
  permissions.value.splice(index, 1)
}

// ── Schema fetch ─────────────────────────────────────────────
async function fetchSchema() {
  if (isNew.value) return
  loading.value = true
  try {
    const token = $auth.token ?? undefined
    const schema = await getFormSchemaById(config.public.apiUrl as string, route.params.id as string, token)
    if (schema) {
      formType.value = schema.type ?? 'SUBMISSION'
      formKey.value = schema.key
      formName.value = schema.name
      formDescription.value = schema.description
      jsonSchema.value = schema.schema as JsonSchema
      uiSchema.value = schema.uiSchema as UiSchema
      formPublic.value = schema.public ?? false
      formPublished.value = schema.published ?? false
      formConfiguration.value = schema.configuration ?? null
      const woCfg = (schema.configuration as Record<string, unknown> | null)?.workOps as Record<string, unknown> | undefined
      if (woCfg) {
        workOpsConfig.defaultProjectId = (woCfg.projectId as string) ?? NONE
        workOpsConfig.defaultTaskTypeId = (woCfg.taskTypeId as string) ?? NONE
        workOpsConfig.defaultPriorityId = (woCfg.priorityId as string) ?? NONE
        const fm = (woCfg.fieldMappings ?? {}) as Record<string, string>
        for (const key of Object.keys(workOpsConfig.mappings)) {
          workOpsConfig.mappings[key] = fm[key] ?? key
        }
      }
      permissions.value = (schema.permissions ?? []).map((p: FormSchemaPermission) => ({
        group: { id: p.group.id, name: p.group.name },
        action: p.action,
      }))
      loadProfileMapping(schema.profileMapping)
    }
  } catch (err) {
    console.error('Failed to fetch form schema:', err)
  } finally {
    loading.value = false
    isDirty.value = false
  }
}

// ── Node helpers ─────────────────────────────────────────────
function collectAllFieldKeys(nodes: UiSchemaNode[], keys: Set<string> = new Set()): Set<string> {
  for (const n of nodes) {
    if (n.type === 'field') keys.add(n.property)
    if ((n.type === 'section' || n.type === 'row') && n.children) {
      collectAllFieldKeys(n.children, keys)
    }
  }
  return keys
}

function countAllFields(nodes: UiSchemaNode[]): number {
  let count = 0
  for (const n of nodes) {
    if (n.type === 'field') count++
    if ((n.type === 'section' || n.type === 'row') && n.children) {
      count += countAllFields(n.children)
    }
  }
  return count
}

function hasDuplicateKeys(nodes: UiSchemaNode[]): boolean {
  const keys = collectAllFieldKeys(nodes)
  return keys.size !== countAllFields(nodes)
}

function getContainerChildren(node: UiSchemaNode): UiSchemaNode[] | null {
  if (node.type === 'section' || node.type === 'row') return node.children
  return null
}

function getFieldKeys(nodes: UiSchemaNode[]): Set<string> {
  const keys = new Set<string>()
  for (const n of nodes) {
    if (n.type === 'field') keys.add(n.property)
  }
  return keys
}

function deduplicateKey(key: string, existingKeys: Set<string>): string {
  if (!existingKeys.has(key)) return key
  let i = 2
  while (existingKeys.has(`${key}_${i}`)) i++
  return `${key}_${i}`
}

function collectFieldProperties(node: UiSchemaNode): string[] {
  if (node.type === 'field') return [node.property]
  if (node.type === 'section' || node.type === 'row') {
    return node.children.flatMap(collectFieldProperties)
  }
  return []
}

function syncSchemaWithLayout() {
  const activeKeys = new Set(uiSchema.value.layout.flatMap(collectFieldProperties))
  const schemaKeys = new Set(Object.keys(jsonSchema.value.properties ?? {}))

  const orphaned = [...schemaKeys].filter(k => !activeKeys.has(k))
  const missing = [...activeKeys].filter(k => !schemaKeys.has(k))

  if (orphaned.length === 0 && missing.length === 0) return

  const props = { ...jsonSchema.value.properties }
  const required = (jsonSchema.value.required ?? []).filter(r => !orphaned.includes(r))
  for (const key of orphaned) Reflect.deleteProperty(props, key)
  for (const key of missing) props[key] = { type: 'string' }
  jsonSchema.value = { ...jsonSchema.value, properties: props, required }
}

function removeSchemaProperties(properties: string[]) {
  if (properties.length === 0) return
  const props = { ...jsonSchema.value.properties }
  const required = (jsonSchema.value.required ?? []).filter(r => !properties.includes(r))
  for (const key of properties) Reflect.deleteProperty(props, key)
  jsonSchema.value = { ...jsonSchema.value, properties: props, required }
}

// ── Node operations ──────────────────────────────────────────
function addNode(node: UiSchemaNode) {
  addNodeAt(node, null, null)
}

function addNodeAt(node: UiSchemaNode, index: number | null, parentIndex: number | null) {
  const layout = JSON.parse(JSON.stringify(uiSchema.value.layout)) as UiSchemaNode[]

  const targetArray = parentIndex !== null
    ? getContainerChildren(layout[parentIndex]!) ?? layout
    : layout

  const existingKeys = getFieldKeys(targetArray)
  if (node.type === 'field') {
    node = { ...node, property: deduplicateKey(node.property, existingKeys) }
  }

  if (index !== null && index >= 0) {
    targetArray.splice(index, 0, node)
  } else {
    targetArray.push(node)
  }

  uiSchema.value = { ...uiSchema.value, layout }

  if (node.type === 'field') {
    if (node.property && !jsonSchema.value.properties?.[node.property]) {
      jsonSchema.value = {
        ...jsonSchema.value,
        properties: {
          ...jsonSchema.value.properties,
          [node.property]: { type: 'string' },
        },
      }
    }
  }
}

const siblingFieldKeys = computed<string[]>(() => {
  if (!selectedNodePath.value) return []
  const path = selectedNodePath.value
  const currentNode = getNodeAtPath(path)
  if (!currentNode || currentNode.type !== 'field') return []

  let siblings: UiSchemaNode[]
  if (path.length === 1) {
    siblings = uiSchema.value.layout
  } else {
    siblings = getContainerChildren(uiSchema.value.layout[path[0]!]!) ?? []
  }
  return siblings
    .filter((n, i) => n.type === 'field' && !(path.length === 1 ? i === path[0]! : i === path[1]!))
    .map(n => (n as FieldNode).property)
})

function removeNodeByPath(path: number[]) {
  const layout = JSON.parse(JSON.stringify(uiSchema.value.layout)) as UiSchemaNode[]
  let removed: UiSchemaNode | undefined
  if (path.length === 1) {
    removed = layout.splice(path[0]!, 1)[0]
  } else if (path.length === 2) {
    const children = getContainerChildren(layout[path[0]!]!)
    if (children) removed = children.splice(path[1]!, 1)[0]
  }
  uiSchema.value = { ...uiSchema.value, layout }
  if (removed) {
    removeSchemaProperties(collectFieldProperties(removed))
  }
  selectedNodePath.value = null
}

function updateLayout(layout: UiSchemaNode[]) {
  uiSchema.value = { ...uiSchema.value, layout }
}

function getNodeAtPath(path: number[]): UiSchemaNode | null {
  if (path.length === 1) {
    return uiSchema.value.layout[path[0]!] ?? null
  } else if (path.length === 2) {
    return getContainerChildren(uiSchema.value.layout[path[0]!]!)?.[path[1]!] ?? null
  }
  return null
}

function updateNodeAtPath(path: number[], node: UiSchemaNode) {
  const layout = JSON.parse(JSON.stringify(uiSchema.value.layout)) as UiSchemaNode[]
  let oldNode: UiSchemaNode | undefined
  if (path.length === 1) {
    oldNode = layout[path[0]!]
    layout[path[0]!] = node
  } else if (path.length === 2) {
    const children = getContainerChildren(layout[path[0]!]!)
    if (children) {
      oldNode = children[path[1]!]
      children[path[1]!] = node
    }
  }
  uiSchema.value = { ...uiSchema.value, layout }

  if (oldNode?.type === 'field' && node.type === 'field' && oldNode.property !== node.property) {
    const props = { ...jsonSchema.value.properties }
    const oldPropSchema = props[oldNode.property]
    Reflect.deleteProperty(props, oldNode.property)
    props[node.property] = oldPropSchema ?? { type: 'string' }
    const required = (jsonSchema.value.required ?? []).map(r => r === oldNode!.property ? node.property : r)
    jsonSchema.value = { ...jsonSchema.value, properties: props, required }
  }
}

const selectedNode = computed(() => {
  if (!selectedNodePath.value) return null
  return getNodeAtPath(selectedNodePath.value)
})

// ── Save / Publish ───────────────────────────────────────────
const saveError = ref('')

async function handleSave() {
  saveError.value = ''
  if (!formKey.value || !formName.value) {
    saveError.value = 'Key and name are required.'
    return
  }
  if (hasDuplicateKeys(uiSchema.value.layout)) {
    saveError.value = 'Duplicate field keys found. Please fix before saving.'
    return
  }

  saving.value = true
  try {
    const token = $auth.token ?? undefined
    syncSchemaWithLayout()
    let configuration: Record<string, unknown> | undefined
    if (formType.value === 'WORK_OPS') {
      const fieldMappings: Record<string, string> = {}
      for (const [taskField, formField] of Object.entries(workOpsConfig.mappings)) {
        if (formField && formField !== taskField) {
          fieldMappings[taskField] = formField
        }
      }
      const wo: Record<string, unknown> = {}
      if (workOpsConfig.defaultProjectId && workOpsConfig.defaultProjectId !== NONE) wo.projectId = workOpsConfig.defaultProjectId
      if (workOpsConfig.defaultTaskTypeId && workOpsConfig.defaultTaskTypeId !== NONE) wo.taskTypeId = workOpsConfig.defaultTaskTypeId
      if (workOpsConfig.defaultPriorityId && workOpsConfig.defaultPriorityId !== NONE) wo.priorityId = workOpsConfig.defaultPriorityId
      if (Object.keys(fieldMappings).length > 0) wo.fieldMappings = fieldMappings
      configuration = { workOps: wo }
    }
    const input: FormSchemaInput = {
      type: formType.value,
      key: formKey.value,
      name: formName.value,
      description: formDescription.value || undefined,
      schema: jsonSchema.value,
      uiSchema: uiSchema.value,
      configuration,
      public: formPublic.value,
      profileMapping: buildProfileMappingInput(),
    }
    const result = await saveFormSchema(config.public.apiUrl as string, input, token)
    formsState?.invalidateSchema(input.key)
    isDirty.value = false
    if (isNew.value) {
      await router.replace(`/forms/builder/${result.id}`)
    }
  } catch (err) {
    console.error('Failed to save form schema:', err)
  } finally {
    saving.value = false
  }
}

const showPublishConfirm = ref(false)

async function handleTogglePublished() {
  showPublishConfirm.value = false
  publishing.value = true
  try {
    const token = $auth.token ?? undefined
    const result = await setFormSchemaPublished(
      config.public.apiUrl as string,
      route.params.id as string,
      !formPublished.value,
      token,
    )
    formPublished.value = result.published
    formsState?.invalidateSchema(formKey.value)
  } catch (err) {
    console.error('Failed to update publish state:', err)
  } finally {
    publishing.value = false
  }
}

// ── Work Ops scaffold ────────────────────────────────────────
function scaffoldWorkOpsFields() {
  jsonSchema.value = {
    $schema: 'https://json-schema.org/draft/2020-12/schema',
    type: 'object',
    properties: {
      projectId: { type: 'string' },
      taskTypeId: { type: 'string' },
      priorityId: { type: 'string' },
      summary: { type: 'string', minLength: 1, maxLength: 255 },
      descriptionMarkdown: { type: 'string' },
    },
    required: ['projectId', 'summary'],
  }
  uiSchema.value = {
    version: 1,
    layout: [
      { type: 'field', property: 'projectId', control: 'workops-select', label: 'Project', workOpsEntity: 'projects', placeholder: 'Select a project…' } as FieldNode,
      {
        type: 'row',
        children: [
          { type: 'field', property: 'taskTypeId', control: 'workops-select', label: 'Task Type', workOpsEntity: 'taskTypes', col: 6 } as FieldNode,
          { type: 'field', property: 'priorityId', control: 'workops-select', label: 'Priority', workOpsEntity: 'priorities', col: 6 } as FieldNode,
        ],
      },
      { type: 'field', property: 'summary', control: 'text-input', label: 'Summary', placeholder: 'What needs to be done?' } as FieldNode,
      { type: 'field', property: 'descriptionMarkdown', control: 'textarea', label: 'Description', placeholder: 'Describe the task (Markdown supported)…' } as FieldNode,
    ],
  }
}

const formFieldKeys = computed(() => {
  const keys = new Set<string>()
  function walk(nodes: UiSchemaNode[]) {
    for (const n of nodes) {
      if (n.type === 'field') keys.add(n.property)
      if ((n.type === 'section' || n.type === 'row') && n.children) walk(n.children)
    }
  }
  walk(uiSchema.value.layout)
  return [...keys]
})

const formFieldSelectItems = computed<SelectOption[]>(() => [
  { label: '(default)', value: NONE },
  ...formFieldKeys.value.map(k => ({ label: k, value: k })),
])

// ── Tab config ───────────────────────────────────────────────
const tabs = computed(() => {
  const t = ['Builder', 'Preview']
  if (formType.value === 'WORK_OPS') t.push('Work Ops')
  t.push('Profile Mapping')
  if (!isNew.value) t.push('Permissions')
  return t
})

onMounted(() => {
  resetFieldCounter()
  fetchSchema()
})
</script>

<template>
  <PageShell class="builder-shell">
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Forms', 'Builder', isNew ? 'New' : formName || 'Edit')"
        :title="isNew ? 'New Form' : formName || 'Edit Form'"
        :tabs="tabs"
        :active-tab="activeTab"
        @tab="(t) => activeTab = t"
      >
        <template #actions>
          <Button
            primary
            icon="save"
            size="sm"
            :accent="accent"
            :disabled="saving"
            @click="handleSave">
            {{ saving ? 'Saving…' : 'Save' }}
          </Button>
          <Button
            v-if="!isNew"
            :icon="formPublished ? 'eye' : 'globe'"
            size="sm"
            :accent="formPublished ? '#ffb547' : '#34d99a'"
            @click="showPublishConfirm = true"
          >
            {{ formPublished ? 'Unpublish' : 'Publish' }}
          </Button>
          <Button icon="code" size="sm" @click="showSchemaModal = true">JSON</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loading" class="loading-state">Loading…</div>

    <template v-else>
      <!-- Form metadata bar -->
      <div class="meta-bar">
        <Select
          v-model="formType"
          :options="typeOptions"
          label="Type"
          :accent="accent"
        />
        <TextInput
          v-model="formKey"
          label="Key"
          placeholder="form.organization.attributes"
          mono
          :disabled="!isNew"
        />
        <TextInput
          v-model="formName"
          label="Name"
          placeholder="Organization Profile"
        />
        <TextInput
          v-model="formDescription"
          label="Description"
          placeholder="Optional description…"
        />
        <div class="meta-switch">
          <Switch v-model="formPublic" label="Public" :accent="accent" />
        </div>
      </div>

      <div v-if="saveError" class="save-error">
        <Icon name="alert" :size="13" color="var(--err)" />
        <span>{{ saveError }}</span>
        <button class="save-error-dismiss" @click="saveError = ''">
          <Icon name="x" :size="11" color="var(--fg-3)" />
        </button>
      </div>

      <!-- Builder tab -->
      <div v-if="activeTab === 'Builder'" class="builder-layout">
        <FormBuilderFieldPalette
          class="builder-palette"
          @add-node="addNode"
        />

        <FormBuilderLayoutCanvas
          class="builder-canvas"
          :layout="uiSchema.layout"
          :selected-path="selectedNodePath"
          :accent="accent"
          @select="(p) => selectedNodePath = p"
          @remove="removeNodeByPath"
          @update:layout="updateLayout"
          @add:node="(node, index, parentIndex) => addNodeAt(node, index, parentIndex)"
        />

        <FormBuilderNodeEditor
          v-if="selectedNode"
          class="builder-editor"
          :node="selectedNode"
          :schema="jsonSchema"
          :sibling-field-keys="siblingFieldKeys"
          :accent="accent"
          @update:node="(n) => updateNodeAtPath(selectedNodePath!, n)"
          @update:schema="(s) => jsonSchema = s"
          @open:graphql-config="showGraphqlConfig = true"
          @open:api-script-config="showApiScriptConfig = true"
        />
      </div>

      <!-- Preview tab -->
      <div v-else-if="activeTab === 'Preview'" class="tab-content">
        <div class="preview-form">
          <BoscaForm
            v-model="sampleData"
            :schema="jsonSchema"
            :ui-schema="uiSchema"
          />
        </div>
        <SectionCard title="Form Data" glass>
          <pre class="schema-pre">{{ JSON.stringify(sampleData, null, 2) }}</pre>
        </SectionCard>
      </div>

      <!-- Work Ops tab -->
      <div v-else-if="activeTab === 'Work Ops'" class="tab-content tab-narrow">
        <div class="workops-header">
          <div>
            <div class="workops-title">Work Ops Configuration</div>
            <p class="workops-desc">
              Submitting this form creates a Work Ops task. Use workops-select fields
              in the builder to let users pick projects, task types, and priorities — or set defaults below.
            </p>
          </div>
          <Button
            v-if="uiSchema.layout.length === 0"
            primary
            icon="wand"
            size="sm"
            :accent="accent"
            @click="scaffoldWorkOpsFields"
          >
            Scaffold Task Fields
          </Button>
        </div>

        <SectionCard title="Default Values" glass>
          <p class="card-hint">
            Used when the form does not include a field for that value.
          </p>
          <div class="defaults-grid">
            <Select
              v-model="workOpsConfig.defaultProjectId"
              :options="workOpsProjectItems"
              label="Project"
              :accent="accent" />
            <Select
              v-model="workOpsConfig.defaultTaskTypeId"
              :options="workOpsTaskTypeItems"
              label="Task Type"
              :accent="accent" />
            <Select
              v-model="workOpsConfig.defaultPriorityId"
              :options="workOpsPriorityItems"
              label="Priority"
              :accent="accent" />
          </div>
        </SectionCard>

        <SectionCard title="Field Mappings" glass>
          <p class="card-hint">
            Maps each task field to a form field key.
          </p>
          <div class="mappings-list">
            <div
              v-for="taskField in Object.keys(workOpsConfig.mappings)"
              :key="taskField"
              class="mapping-row"
            >
              <div class="mapping-label">
                <span class="mapping-name">{{ taskFieldDescriptions[taskField] ?? taskField }}</span>
                <span class="mapping-key">({{ taskField }})</span>
              </div>
              <Select
                :model-value="workOpsConfig.mappings[taskField]"
                :options="formFieldSelectItems"
                :accent="accent"
                @update:model-value="(v: string | string[] | null | undefined) => workOpsConfig.mappings[taskField] = (!v || v === NONE) ? taskField : String(v)"
              />
              <Badge
                v-if="formFieldKeys.includes(workOpsConfig.mappings[taskField]!)"
                color="#34d99a"
              >
                Matched
              </Badge>
              <Badge v-else color="#ffb547">
                No match
              </Badge>
            </div>
          </div>
        </SectionCard>

        <SectionCard v-if="formFieldKeys.length > 0" title="Form Fields" glass>
          <div class="field-pills">
            <Badge
              v-for="key in formFieldKeys"
              :key="key"
              :color="Object.values(workOpsConfig.mappings).includes(key) ? '#34d99a' : 'var(--fg-3)'"
            >
              {{ key }}
            </Badge>
          </div>
          <p class="card-hint">
            Green fields are mapped to task fields. Unmatched fields will be stored as custom-field values.
          </p>
        </SectionCard>
      </div>

      <!-- Profile Mapping tab -->
      <div v-else-if="activeTab === 'Profile Mapping'" class="tab-content tab-narrow">
        <p class="tab-desc">
          Configure how form field values are mapped to a profile when an anonymous user submits this form.
        </p>
        <Switch
          v-model="profileMappingEnabled"
          label="Enable profile mapping for anonymous submissions"
          :accent="accent"
        />

        <template v-if="profileMappingEnabled">
          <SectionCard title="Profile Settings" glass>
            <div class="profile-fields">
              <Select
                v-model="profileMappingNameField"
                :options="allFieldKeyOptions"
                label="Name Field"
                placeholder="Select a form field"
                :accent="accent"
              />
              <Select
                v-model="profileMappingVisibility"
                :options="visibilityOptions"
                label="Profile Visibility"
                :accent="accent"
              />
            </div>
          </SectionCard>

          <SectionCard title="Profile Attributes" glass>
            <template #right>
              <Button icon="plus" size="sm" @click="addProfileMappingAttribute">Add Attribute</Button>
            </template>
            <p class="card-hint">
              Map form fields to profile attribute types.
            </p>
            <div v-if="profileMappingAttributes.length > 0" class="attr-list">
              <div
                v-for="(attr, index) in profileMappingAttributes"
                :key="index"
                class="attr-row"
              >
                <Select
                  v-model="attr.typeId"
                  :options="attributeTypeOptions"
                  label="Attribute Type"
                  placeholder="Select type"
                  :accent="accent"
                />
                <Select
                  v-model="attr.field"
                  :options="allFieldKeyOptions"
                  label="Form Field"
                  placeholder="Select field"
                  :accent="accent"
                />
                <TextInput
                  v-model="attr.attributeKey"
                  label="Attribute Key"
                  placeholder="email"
                />
                <button class="attr-delete" @click="removeProfileMappingAttribute(index)">
                  <Icon name="trash" :size="13" color="var(--err)" />
                </button>
              </div>
            </div>
            <p v-else class="card-hint">
              No attribute mappings configured. Add mappings to capture email, phone, or other contact details.
            </p>
          </SectionCard>
        </template>
      </div>

      <!-- Permissions tab -->
      <div v-else-if="activeTab === 'Permissions'" class="tab-content tab-narrow">
        <div class="perms-header">
          <div class="perms-title">Permissions</div>
          <Button icon="plus" size="sm" @click="addPermissionRow">Add</Button>
        </div>
        <div v-if="permissions.length > 0" class="perms-list">
          <div v-for="(perm, index) in permissions" :key="index" class="perm-row">
            <Select
              v-model="perm.group.id"
              :options="groupOptions"
              placeholder="Select Group"
              :accent="accent"
            />
            <Select
              v-model="perm.action"
              :options="permissionActions"
              :accent="accent"
            />
            <Button
              icon="save"
              size="sm"
              :accent="accent"
              :disabled="!perm.group.id"
              @click="savePermission(index)">
              Save
            </Button>
            <button class="perm-delete" @click="removePermission(index)">
              <Icon name="trash" :size="13" color="var(--err)" />
            </button>
          </div>
        </div>
        <p v-else class="card-hint">
          No permissions configured. The form schema uses default access rules.
        </p>
      </div>
    </template>

    <!-- JSON Schema modal -->
    <Modal
      v-if="showSchemaModal"
      title="JSON Schema"
      icon="code"
      :accent="accent"
      width="900px"
      @close="showSchemaModal = false"
    >
      <div class="schema-grid">
        <div>
          <div class="schema-label">Validation Schema</div>
          <pre class="schema-pre">{{ JSON.stringify(jsonSchema, null, 2) }}</pre>
        </div>
        <div>
          <div class="schema-label">UI Schema</div>
          <pre class="schema-pre">{{ JSON.stringify(uiSchema, null, 2) }}</pre>
        </div>
      </div>
    </Modal>

    <!-- Publish confirm modal -->
    <Modal
      v-if="showPublishConfirm"
      :title="`${formPublished ? 'Unpublish' : 'Publish'} form &quot;${formName}&quot;?`"
      :icon="formPublished ? 'eye' : 'globe'"
      :accent="formPublished ? '#ffb547' : '#34d99a'"
      @close="showPublishConfirm = false"
    >
      <p class="confirm-text">
        {{
          formPublished
            ? 'This form will no longer be available for public submissions.'
            : 'This form will become available for submissions.'
        }}
      </p>
      <template #footer>
        <Button @click="showPublishConfirm = false">Cancel</Button>
        <Button
          primary
          :accent="formPublished ? '#ffb547' : '#34d99a'"
          :disabled="publishing"
          @click="handleTogglePublished"
        >
          {{ formPublished ? 'Unpublish' : 'Publish' }}
        </Button>
      </template>
    </Modal>

    <!-- Leave confirm modal -->
    <Modal
      v-if="showLeaveConfirm"
      title="Unsaved Changes"
      icon="alert"
      accent="var(--err)"
      @close="cancelLeave"
    >
      <p class="confirm-text">You have unsaved changes. Are you sure you want to leave?</p>
      <template #footer>
        <Button @click="cancelLeave">Stay</Button>
        <Button primary accent="var(--err)" @click="confirmLeave">Leave</Button>
      </template>
    </Modal>

    <!-- Config modals (teleported) -->
    <FormBuilderGraphqlSelectConfigModal
      v-if="showGraphqlConfig && selectedNode?.type === 'field'"
      :node="(selectedNode as FieldNode)"
      :accent="accent"
      @update:node="(n) => updateNodeAtPath(selectedNodePath!, n)"
      @close="showGraphqlConfig = false"
    />

    <FormBuilderApiScriptSelectConfigModal
      v-if="showApiScriptConfig && selectedNode?.type === 'field'"
      :node="(selectedNode as FieldNode)"
      :accent="accent"
      @update:node="(n) => updateNodeAtPath(selectedNodePath!, n)"
      @close="showApiScriptConfig = false"
    />
  </PageShell>
</template>

<style scoped>
/* The builder is a full-bleed editor: the meta bar's rule and the three-pane
   dividers run edge to edge, and each tab supplies its own 22px padding. Left
   to PageShell's default page padding, every child ends up double-inset. */
.builder-shell :deep(.page-content) {
  padding: 0;
  gap: 0;
}

.save-error {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  margin: 0 22px;
  background: color-mix(in oklch, var(--err) 10%, transparent);
  border: 1px solid color-mix(in oklch, var(--err) 30%, transparent);
  border-radius: var(--r-sm);
  font-size: 12.5px;
  color: var(--err);
}

.save-error span {
  flex: 1;
}

.save-error-dismiss {
  padding: 2px;
  border-radius: var(--r-xs);
}

.save-error-dismiss:hover {
  background: color-mix(in oklch, var(--err) 15%, transparent);
}

.loading-state {
  color: var(--fg-3);
  text-align: center;
  padding: 60px 0;
  font-size: 14px;
}

/* ── Meta bar ───────────────────────────────────────── */
.meta-bar {
  display: flex;
  align-items: flex-end;
  gap: 14px;
  padding: 14px 22px;
  border-bottom: 1px solid var(--line);
  flex-wrap: wrap;
}

.meta-bar > * {
  flex: 1;
  min-width: 120px;
}

.meta-switch {
  display: flex;
  align-items: center;
  padding-bottom: 4px;
  flex: 0 0 auto;
  min-width: auto;
}

/* ── Builder layout ─────────────────────────────────── */
.builder-layout {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.builder-palette {
  width: 210px;
  flex: 0 0 210px;
  border-right: 1px solid var(--line);
  padding: 14px;
  overflow-y: auto;
}

.builder-canvas {
  flex: 1;
  padding: 14px;
  overflow-y: auto;
}

.builder-editor {
  width: 280px;
  flex: 0 0 280px;
  border-left: 1px solid var(--line);
  padding: 14px;
  overflow-y: auto;
}

/* ── Tab content ────────────────────────────────────── */
.tab-content {
  padding: 22px;
  overflow-y: auto;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.tab-narrow {
  max-width: 720px;
}

.tab-desc {
  font-size: 13px;
  color: var(--fg-3);
  margin: 0;
}

/* ── Preview ────────────────────────────────────────── */
.preview-form {
  max-width: 640px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  padding: 24px;
}

/* ── Work Ops ───────────────────────────────────────── */
.workops-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.workops-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-0);
}

.workops-desc {
  font-size: 12px;
  color: var(--fg-3);
  margin: 4px 0 0;
}

.defaults-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}

.mappings-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.mapping-row {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 12px;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 40%, transparent);
}

.mapping-row:last-child {
  border-bottom: none;
}

.mapping-label {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.mapping-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-1);
}

.mapping-key {
  font-size: 11px;
  font-family: 'Geist Mono', monospace;
  color: var(--fg-3);
}

.field-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.card-hint {
  font-size: 12px;
  color: var(--fg-3);
  margin: 0;
}

/* ── Profile Mapping ────────────────────────────────── */
.profile-fields {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.attr-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.attr-row {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr auto;
  gap: 10px;
  align-items: flex-end;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
}

.attr-delete,
.perm-delete {
  padding: 6px;
  border-radius: var(--r-xs);
  transition: background 0.12s;
}

.attr-delete:hover,
.perm-delete:hover {
  background: color-mix(in oklch, var(--err) 12%, transparent);
}

/* ── Permissions ────────────────────────────────────── */
.perms-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.perms-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-0);
}

.perms-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.perm-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.perm-row > :first-child {
  flex: 1;
}

/* ── Schema modal ───────────────────────────────────── */
.schema-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.schema-label {
  font-size: 10.5px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--fg-3);
  margin-bottom: 8px;
}

.schema-pre {
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 12px;
  font-family: 'Geist Mono', monospace;
  font-size: 11.5px;
  color: var(--fg-1);
  overflow: auto;
  max-height: 400px;
  margin: 0;
}

.confirm-text {
  font-size: 13px;
  color: var(--fg-2);
  margin: 0;
}
</style>

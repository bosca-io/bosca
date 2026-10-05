<script lang="ts" setup>
import JsonEditorVue from 'json-editor-vue'
import Sortable from 'sortablejs'
import gql from 'graphql-tag'
import {
  GuideType,
  type GuideTemplate,
  type TemplateAttribute,
  type TemplateAttributeInput,
  type TemplateAttributeTool,
} from '~/types/graphql'
import { toEditable } from '~/utils/toEditable'

interface GuideTemplateResponse {
  content?: { metadata?: { guideTemplate?: GuideTemplate } }
}

interface ToolsResponse {
  content?: { templateAttributeTools?: { all?: TemplateAttributeTool[] } }
}

interface DocTemplatesResponse {
  content?: { documentTemplates?: { all?: Array<{ metadata?: { id: string; version: number; name: string } }> } }
}

interface EditableAttribute extends TemplateAttribute {
  __id?: string
  workflows?: Array<{ autoRun: boolean }>
}

const getGuideTemplateQuery = gql`
  query GetGuideTemplate($id: UUID!, $version: Int!) {
    content {
      metadata(id: $id, version: $version) {
        id version name
        guideTemplate {
          rrule type configuration defaultAttributes
          attributes {
            configuration description key list location name supplementaryKey type ui
            tools { id name description query resultPath }
            workflows { autoRun }
          }
          steps {
            id
            metadata { id version name }
            modules { id metadata { id version name } }
          }
        }
      }
    }
  }
`

const getToolsQuery = gql`
  query GetTemplateAttributeTools {
    content { templateAttributeTools { all { id key name description query resultPath configuration } } }
  }
`

const findDocTemplatesQuery = gql`
  query FindDocTemplates {
    content { documentTemplates { all { metadata { id version name } } } }
  }
`

const setRruleMut = gql`
  mutation SetGuideRrule($id: UUID!, $version: Int!, $rrule: String!) {
    content { metadata { guideTemplate(metadataId: $id, metadataVersion: $version) { setRrule(rrule: $rrule) { id } } } }
  }
`

const setTypeMut = gql`
  mutation SetGuideType($id: UUID!, $version: Int!, $type: GuideType!) {
    content { metadata { guideTemplate(metadataId: $id, metadataVersion: $version) { setType(guideType: $type) { id } } } }
  }
`

const setDefaultAttrsMut = gql`
  mutation SetGuideDefaultAttrs($id: UUID!, $version: Int!, $attributes: JSON!) {
    content { metadata { guideTemplate(metadataId: $id, metadataVersion: $version) { setDefaultAttributes(attributes: $attributes) { id } } } }
  }
`

const setConfigMut = gql`
  mutation SetGuideConfig($id: UUID!, $version: Int!, $configuration: JSON!) {
    content { metadata { guideTemplate(metadataId: $id, metadataVersion: $version) { setConfiguration(configuration: $configuration) { id } } } }
  }
`

const setAttrsMut = gql`
  mutation SetGuideAttrs($id: UUID!, $version: Int!, $attributes: [TemplateAttributeInput!]!) {
    content { metadata { guideTemplate(metadataId: $id, metadataVersion: $version) { setAttributes(attributes: $attributes) { id } } } }
  }
`

const addStepMut = gql`
  mutation AddGuideStep($id: UUID!, $version: Int!, $stepMetadataId: UUID!, $stepMetadataVersion: Int) {
    content { metadata { guideTemplate(metadataId: $id, metadataVersion: $version) { addStep(stepMetadataId: $stepMetadataId, stepMetadataVersion: $stepMetadataVersion) } } }
  }
`

const removeStepMut = gql`
  mutation RemoveGuideStep($id: UUID!, $version: Int!, $stepId: Long!) {
    content { metadata { guideTemplate(metadataId: $id, metadataVersion: $version) { removeStep(stepId: $stepId) } } }
  }
`

const reorderStepsMut = gql`
  mutation ReorderGuideSteps($id: UUID!, $version: Int!, $stepIds: [Long!]!) {
    content { metadata { guideTemplate(metadataId: $id, metadataVersion: $version) { reorderSteps(stepIds: $stepIds) } } }
  }
`

const props = defineProps<{
  metadataId: string
  metadataVersion: number
}>()

const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const metadataId = computed(() => props.metadataId)
const metadataVersion = computed(() => props.metadataVersion)

const { data: templateData, refresh } = useAsyncQuery<GuideTemplateResponse>('guide-template', getGuideTemplateQuery, {
  id: metadataId, version: metadataVersion,
})
const { data: toolsData } = useAsyncQuery<ToolsResponse>('guide-tools', getToolsQuery, {})
const { data: docTemplatesData } = useAsyncQuery<DocTemplatesResponse>('guide-doc-templates', findDocTemplatesQuery, {})

const guideTemplate = computed(() => templateData.value?.content?.metadata?.guideTemplate)
const tools = computed<TemplateAttributeTool[]>(() => toolsData.value?.content?.templateAttributeTools?.all || [])
const documentTemplates = computed(() =>
  docTemplatesData.value?.content?.documentTemplates?.all?.map((t) => t.metadata) || []
)

const template = ref<GuideTemplate | null>(null)
const saving = ref(false)
const selectedStepMetadata = ref<string | null>(null)

const guideTypeOptions = [
  { value: GuideType.Calendar, label: 'Calendar' },
  { value: GuideType.CalendarProgress, label: 'Calendar Progress' },
  { value: GuideType.Linear, label: 'Linear' },
  { value: GuideType.LinearProgress, label: 'Linear Progress' },
]

const stepDocOptions = computed(() =>
  documentTemplates.value.map((t) => ({ value: t?.id ?? '', label: t?.name ?? '' }))
)

const activeTab = ref('Attributes')
const tabs = computed(() => {
  const t = ['Type', 'Default Attributes', 'Configuration', 'Attributes', 'Steps']
  if (template.value?.type === GuideType.Calendar || template.value?.type === GuideType.CalendarProgress) {
    t.unshift('Recurrence')
  }
  return t
})

const attrsRef = useTemplateRef<HTMLElement>('attrsRef')
let attrsSortable: Sortable | null = null

onUnmounted(() => { attrsSortable?.destroy() })

const expandedAttr = ref<number | null>(null)

watch(guideTemplate, (v) => { if (v) bindTemplate() })

async function bindTemplate() {
  const gt = guideTemplate.value
  if (!gt) return
  template.value = reactive(toEditable(gt)!) as GuideTemplate

  await nextTick()
  let id = 0
  for (const a of template.value.attributes) { (a as EditableAttribute).__id = (id++).toString() }

  attrsSortable?.destroy()
  if (attrsRef.value) {
    attrsSortable = Sortable.create(attrsRef.value, {
      handle: '.drag-handle',
      onEnd: (evt) => {
        if (evt.oldIndex !== undefined && evt.newIndex !== undefined) {
          const item = template.value!.attributes.splice(evt.oldIndex, 1)[0]
          if (item) template.value!.attributes.splice(evt.newIndex, 0, item)
        }
      },
    })
  }
}

function onAddAttribute() {
  template.value!.attributes.push({
    key: '', name: '', description: '', type: 'STRING', supplementaryKey: '',
    ui: 'INPUT', list: false, location: 'ITEM', configuration: null, tools: [],
  } as TemplateAttribute)
  expandedAttr.value = template.value!.attributes.length - 1
}

function onDeleteAttribute(index: number) {
  template.value!.attributes.splice(index, 1)
  expandedAttr.value = null
}

async function saveAndRefresh(fn: () => Promise<void>, label: string) {
  saving.value = true
  try {
    await fn()
    toast.success(`${label} saved`)
    await refresh()
  } catch (e: unknown) { toast.error((e instanceof Error ? e.message : null) || `Failed to save ${label.toLowerCase()}`) }
  finally { saving.value = false }
}

function onSaveRrule() {
  return saveAndRefresh(() =>
    gqlMutation(setRruleMut, { id: props.metadataId, version: props.metadataVersion, rrule: template.value?.rrule || '' }),
    'Recurrence')
}

function onSaveType() {
  return saveAndRefresh(() =>
    gqlMutation(setTypeMut, { id: props.metadataId, version: props.metadataVersion, type: template.value?.type || GuideType.Linear }),
    'Guide type')
}

function onSaveDefaultAttributes() {
  return saveAndRefresh(() =>
    gqlMutation(setDefaultAttrsMut, { id: props.metadataId, version: props.metadataVersion, attributes: template.value?.defaultAttributes || {} }),
    'Default attributes')
}

function onSaveConfiguration() {
  return saveAndRefresh(() =>
    gqlMutation(setConfigMut, { id: props.metadataId, version: props.metadataVersion, configuration: template.value?.configuration || {} }),
    'Configuration')
}

function onSaveAttributes() {
  const attrs: TemplateAttributeInput[] = template.value!.attributes.map(a => ({
    configuration: a.configuration, description: a.description, key: a.key,
    list: a.list, location: a.location, name: a.name, supplementaryKey: a.supplementaryKey,
    type: a.type, ui: a.ui,
    tools: a.tools?.map(t => ({ id: t.id, name: t.name, description: t.description, query: t.query, resultPath: t.resultPath })),
    workflows: (a as EditableAttribute).workflows?.map((w) => ({ autoRun: w.autoRun, workflowId: (w as unknown as { workflowId?: string }).workflowId ?? '' })) ?? [],
  }))
  return saveAndRefresh(() =>
    gqlMutation(setAttrsMut, { id: props.metadataId, version: props.metadataVersion, attributes: attrs }),
    'Attributes')
}

async function onAddStep() {
  const meta = documentTemplates.value.find((t) => t?.id === selectedStepMetadata.value)
  if (!meta) return
  saving.value = true
  try {
    await gqlMutation(addStepMut, {
      id: props.metadataId, version: props.metadataVersion,
      stepMetadataId: meta.id, stepMetadataVersion: meta.version,
    })
    selectedStepMetadata.value = null
    await refresh()
    toast.success('Step added')
  } catch (e: unknown) { toast.error((e instanceof Error ? e.message : null) || 'Failed') }
  finally { saving.value = false }
}

async function onDeleteStep(stepId: number) {
  saving.value = true
  try {
    await gqlMutation(removeStepMut, { id: props.metadataId, version: props.metadataVersion, stepId })
    await refresh()
    toast.success('Step removed')
  } catch (e: unknown) { toast.error((e instanceof Error ? e.message : null) || 'Failed') }
  finally { saving.value = false }
}

async function onSaveStepOrder() {
  saving.value = true
  try {
    const stepIds = template.value?.steps.map(s => s.id) || []
    await gqlMutation(reorderStepsMut, { id: props.metadataId, version: props.metadataVersion, stepIds })
    toast.success('Step order saved')
  } catch (e: unknown) { toast.error((e instanceof Error ? e.message : null) || 'Failed') }
  finally { saving.value = false }
}

function onSave() {
  if (activeTab.value === 'Recurrence') onSaveRrule()
  else if (activeTab.value === 'Type') onSaveType()
  else if (activeTab.value === 'Attributes') onSaveAttributes()
  else if (activeTab.value === 'Default Attributes') onSaveDefaultAttributes()
  else if (activeTab.value === 'Configuration') onSaveConfiguration()
}

defineExpose({ save: onSave, saving, activeTab, addAttribute: onAddAttribute })

onMounted(() => { if (guideTemplate.value) bindTemplate() })
</script>

<template>
  <div v-if="template" class="template-editor">
    <Tabs v-model="activeTab" :tabs="tabs" />

    <!-- Recurrence -->
    <div v-if="activeTab === 'Recurrence'" class="tab-body">
      <TemplatesRRuleEditor v-if="template" :model-value="template.rrule ?? undefined" @update:model-value="(v: string) => { if (template) template.rrule = v }" />
    </div>

    <!-- Type -->
    <div v-else-if="activeTab === 'Type'" class="tab-body">
      <Select v-model="template.type" :options="guideTypeOptions" label="Guide Type" />
    </div>

    <!-- Default Attributes -->
    <div v-else-if="activeTab === 'Default Attributes'" class="tab-body">
      <JsonEditorVue
        v-model="template.defaultAttributes"
        :main-menu-bar="false"
        :navigation-bar="false"
        class="json-editor" />
    </div>

    <!-- Configuration -->
    <div v-else-if="activeTab === 'Configuration'" class="tab-body">
      <JsonEditorVue
        v-model="template.configuration"
        :main-menu-bar="false"
        :navigation-bar="false"
        class="json-editor" />
    </div>

    <!-- Attributes -->
    <div v-else-if="activeTab === 'Attributes'" class="tab-body">
      <div v-if="!template.attributes.length" class="empty-state">No attributes defined. Add one to get started.</div>
      <div ref="attrsRef">
        <div v-for="(attribute, index) in template.attributes" :key="(attribute as EditableAttribute).__id" class="sortable-item">
          <div class="sortable-header" @click="expandedAttr = expandedAttr === index ? null : index">
            <Icon
              name="sort"
              :size="14"
              color="var(--fg-3)"
              class="drag-handle"
              @click.stop />
            <Icon
              name="chevron"
              :size="12"
              color="var(--fg-3)"
              :style="{ transform: expandedAttr === index ? 'rotate(90deg)' : 'none', transition: 'transform 0.15s' }" />
            <span class="sortable-label">{{ attribute.name || attribute.key || 'New Attribute' }}</span>
            <Badge :color="'var(--fg-3)'">{{ attribute.type }}</Badge>
          </div>
          <TemplatesAttribute
            v-if="expandedAttr === index"
            v-model:attribute="template.attributes[index]!"
            :tools="tools"
            :on-delete="() => onDeleteAttribute(index)"
          />
        </div>
      </div>
    </div>

    <!-- Steps -->
    <div v-else-if="activeTab === 'Steps'" class="tab-body">
      <div v-if="!template.steps?.length" class="empty-state">No steps defined.</div>
      <div v-for="step in template.steps" :key="step.id" class="step-row">
        <span class="step-name">{{ step.metadata?.name || 'Unnamed Step' }}</span>
        <span class="step-id mono">{{ step.metadata?.id }}</span>
        <span style="flex: 1" />
        <button class="step-delete" @click="onDeleteStep(step.id)">
          <Icon name="trash" :size="12" color="var(--err)" />
        </button>
      </div>

      <div class="step-add-row">
        <Select
          v-model="selectedStepMetadata"
          :options="stepDocOptions"
          label="Add Step"
          searchable
          placeholder="Search document templates…"
        />
        <Button
          size="sm"
          icon="plus"
          :disabled="!selectedStepMetadata"
          @click="onAddStep">Add</Button>
      </div>
      <Button
        size="sm"
        icon="save"
        :disabled="saving"
        @click="onSaveStepOrder">Save Step Order</Button>
    </div>

  </div>
  <div v-else class="loading">Loading template…</div>
</template>

<style scoped>
.template-editor {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 960px;
}

.tab-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.json-editor {
  border-radius: var(--r-sm);
  min-height: 200px;
}

.sortable-item {
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  overflow: hidden;
  margin-bottom: 6px;
}

.sortable-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  cursor: pointer;
  transition: background 0.15s;
}

.sortable-header:hover {
  background: color-mix(in oklch, var(--brand-2) 4%, transparent);
}

.drag-handle { cursor: grab; }

.sortable-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
  flex: 1;
}

.tab-actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}

.empty-state {
  padding: 24px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
}

.step-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  margin-bottom: 6px;
}

.step-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
}

.step-id {
  font-size: 11px;
  color: var(--fg-3);
}

.step-delete {
  width: 24px;
  height: 24px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0.5;
  transition: opacity 0.15s;
}

.step-delete:hover {
  opacity: 1;
  background: color-mix(in oklch, var(--err) 10%, transparent);
}

.step-add-row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.step-add-row > :first-child { flex: 1; }

.loading {
  padding: 40px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}
</style>

<script lang="ts" setup>
import JsonEditorVue from 'json-editor-vue'
import Sortable from 'sortablejs'
import gql from 'graphql-tag'
import type {
  DocumentTemplate,
  DocumentTemplateContainer,
  DocumentTemplateContainerInput,
  TemplateAttribute,
  TemplateAttributeInput,
  TemplateAttributeTool,
} from '~/types/graphql'
import { toEditable } from '~/utils/toEditable'

interface DocTemplateResponse {
  content?: { metadata?: { documentTemplate?: DocumentTemplate } }
}

interface ToolsResponse {
  content?: { templateAttributeTools?: { all?: TemplateAttributeTool[] } }
}

interface EditableAttribute extends TemplateAttribute {
  __id?: string
  workflows?: Array<{ autoRun: boolean }>
}

interface EditableContainer extends DocumentTemplateContainer {
  __id?: string
}

const getDocumentTemplateQuery = gql`
  query GetDocTemplate($id: UUID!, $version: Int!) {
    content {
      metadata(id: $id, version: $version) {
        id version name
        documentTemplate {
          defaultAttributes configuration content
          containers {
            id name description type supplementaryKey
            tools { id name description query resultPath }
            workflows { autoRun }
            renderers { name configuration }
            filters
          }
          attributes {
            configuration description key list location name supplementaryKey type ui
            workflows { autoRun }
            tools { id name description query resultPath }
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

const setDefaultAttrsMut = gql`
  mutation SetDocDefaultAttrs($id: UUID!, $version: Int!, $attributes: JSON!) {
    content { metadata { documentTemplate(metadataId: $id, metadataVersion: $version) { setDefaultAttributes(attributes: $attributes) { id } } } }
  }
`

const setConfigMut = gql`
  mutation SetDocConfig($id: UUID!, $version: Int!, $configuration: JSON!) {
    content { metadata { documentTemplate(metadataId: $id, metadataVersion: $version) { setConfiguration(configuration: $configuration) { id } } } }
  }
`

const _setContentMut = gql`
  mutation SetDocContent($id: UUID!, $version: Int!, $content: JSON!) {
    content { metadata { documentTemplate(metadataId: $id, metadataVersion: $version) { setContent(content: $content) { id } } } }
  }
`

const setAttrsMut = gql`
  mutation SetDocAttrs($id: UUID!, $version: Int!, $attributes: [TemplateAttributeInput!]!) {
    content { metadata { documentTemplate(metadataId: $id, metadataVersion: $version) { setAttributes(attributes: $attributes) { id } } } }
  }
`

const setContainersMut = gql`
  mutation SetDocContainers($id: UUID!, $version: Int!, $containers: [DocumentTemplateContainerInput!]!) {
    content { metadata { documentTemplate(metadataId: $id, metadataVersion: $version) { setContainers(containers: $containers) { id } } } }
  }
`

const props = defineProps<{
  metadataId: string
  metadataVersion: number
}>()

const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const { data: templateData } = useAsyncQuery<DocTemplateResponse>('doc-template', getDocumentTemplateQuery, {
  id: props.metadataId,
  version: props.metadataVersion,
})
const { data: toolsData } = useAsyncQuery<ToolsResponse>('doc-tools', getToolsQuery, {})

const documentTemplate = computed(() => templateData.value?.content?.metadata?.documentTemplate)
const tools = computed<TemplateAttributeTool[]>(() => toolsData.value?.content?.templateAttributeTools?.all || [])

const template = ref<DocumentTemplate | null>(null)
const activeTab = ref('Attributes')
const tabs = ['Default Attributes', 'Configuration', 'Containers', 'Attributes']
const saving = ref(false)

const attrsRef = useTemplateRef<HTMLElement>('attrsRef')
const containersRef = useTemplateRef<HTMLElement>('containersRef')
let attrsSortable: Sortable | null = null
let containersSortable: Sortable | null = null

onUnmounted(() => {
  attrsSortable?.destroy()
  containersSortable?.destroy()
})

watch(documentTemplate, (v) => {
  if (v) bindTemplate()
})

const expandedAttr = ref<number | null>(null)
const expandedContainer = ref<number | null>(null)

async function bindTemplate() {
  const ct = documentTemplate.value
  if (!ct) return
  template.value = reactive(toEditable(ct)!) as DocumentTemplate

  await nextTick()

  let id = 0
  for (const a of template.value.attributes) { (a as EditableAttribute).__id = (id++).toString() }
  for (const c of template.value.containers) { (c as EditableContainer).__id = (id++).toString() }

  attrsSortable?.destroy()
  containersSortable?.destroy()

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

  if (containersRef.value) {
    containersSortable = Sortable.create(containersRef.value, {
      handle: '.drag-handle',
      onEnd: (evt) => {
        if (evt.oldIndex !== undefined && evt.newIndex !== undefined) {
          const item = template.value!.containers.splice(evt.oldIndex, 1)[0]
          if (item) template.value!.containers.splice(evt.newIndex, 0, item)
        }
      },
    })
  }
}

function onAddAttribute() {
  template.value!.attributes.push({
    key: '', name: '', description: '', type: 'STRING', supplementaryKey: '',
    ui: 'INPUT', list: false, location: 'ITEM', configuration: null, workflows: [], tools: [],
  } as TemplateAttribute)
  expandedAttr.value = template.value!.attributes.length - 1
}

function onDeleteAttribute(index: number) {
  template.value!.attributes.splice(index, 1)
  expandedAttr.value = null
}

function onAddContainer() {
  template.value!.containers.push({
    id: '', name: '', description: '', supplementaryKey: '',
    type: 'STANDARD', tools: [], workflows: [], renderers: [], filters: [],
  } as DocumentTemplateContainer)
  expandedContainer.value = template.value!.containers.length - 1
}

function onDeleteContainer(index: number) {
  template.value!.containers.splice(index, 1)
  expandedContainer.value = null
}

async function onSaveDefaultAttributes() {
  saving.value = true
  try {
    await gqlMutation(setDefaultAttrsMut, {
      id: props.metadataId, version: props.metadataVersion,
      attributes: template.value?.defaultAttributes || {},
    })
    toast.success('Default attributes saved')
  } catch (e: unknown) { toast.error((e instanceof Error ? e.message : null) || 'Failed to save') }
  finally { saving.value = false }
}

async function onSaveConfiguration() {
  saving.value = true
  try {
    await gqlMutation(setConfigMut, {
      id: props.metadataId, version: props.metadataVersion,
      configuration: template.value?.configuration || {},
    })
    toast.success('Configuration saved')
  } catch (e: unknown) { toast.error((e instanceof Error ? e.message : null) || 'Failed to save') }
  finally { saving.value = false }
}

async function onSaveAttributes() {
  saving.value = true
  try {
    const attrs: TemplateAttributeInput[] = template.value!.attributes.map(a => ({
      configuration: a.configuration, description: a.description, key: a.key,
      list: a.list, location: a.location, name: a.name, supplementaryKey: a.supplementaryKey,
      type: a.type, ui: a.ui,
      workflows: (a as EditableAttribute).workflows?.map((w) => ({ autoRun: w.autoRun, workflowId: (w as unknown as { workflowId?: string }).workflowId ?? '' })) ?? [],
      tools: a.tools?.map(t => ({ id: t.id, name: t.name, description: t.description, query: t.query, resultPath: t.resultPath })),
    }))
    await gqlMutation(setAttrsMut, { id: props.metadataId, version: props.metadataVersion, attributes: attrs })
    toast.success('Attributes saved')
  } catch (e: unknown) { toast.error((e instanceof Error ? e.message : null) || 'Failed to save') }
  finally { saving.value = false }
}

async function onSaveContainers() {
  saving.value = true
  try {
    const containers: DocumentTemplateContainerInput[] = template.value!.containers.map(c => ({
      containerType: c.type, id: c.id, name: c.name, supplementaryKey: c.supplementaryKey,
      description: c.description,
      tools: c.tools?.map(t => ({ id: t.id, name: t.name, description: t.description, query: t.query, resultPath: t.resultPath })),
      workflows: c.workflows?.map(w => ({ autoRun: w.autoRun, workflowId: (w as unknown as { workflowId?: string }).workflowId ?? '' })) || [],
      renderers: c.renderers?.map(r => ({ name: r.name, configuration: r.configuration })),
      filters: c.filters,
    }))
    await gqlMutation(setContainersMut, { id: props.metadataId, version: props.metadataVersion, containers })
    toast.success('Containers saved')
  } catch (e: unknown) { toast.error((e instanceof Error ? e.message : null) || 'Failed to save') }
  finally { saving.value = false }
}

function onSave() {
  if (activeTab.value === 'Attributes') onSaveAttributes()
  else if (activeTab.value === 'Containers') onSaveContainers()
  else if (activeTab.value === 'Default Attributes') onSaveDefaultAttributes()
  else if (activeTab.value === 'Configuration') onSaveConfiguration()
}

defineExpose({ save: onSave, saving, activeTab, addAttribute: onAddAttribute, addContainer: onAddContainer })

onMounted(() => {
  if (documentTemplate.value) bindTemplate()
})
</script>

<template>
  <div v-if="template" class="template-editor">
    <Tabs v-model="activeTab" :tabs="tabs" />

    <!-- Default Attributes -->
    <div v-if="activeTab === 'Default Attributes'" class="tab-body">
      <JsonEditorVue
        v-model="template.defaultAttributes"
        :main-menu-bar="false"
        :navigation-bar="false"
        class="json-editor"
      />
    </div>

    <!-- Configuration -->
    <div v-else-if="activeTab === 'Configuration'" class="tab-body">
      <JsonEditorVue
        v-model="template.configuration"
        :main-menu-bar="false"
        :navigation-bar="false"
        class="json-editor"
      />
    </div>

    <!-- Containers -->
    <div v-else-if="activeTab === 'Containers'" class="tab-body">
      <div v-if="!template.containers.length" class="empty-state">No containers defined. Add one to get started.</div>
      <div ref="containersRef">
        <div v-for="(container, index) in template.containers" :key="(container as EditableContainer).__id" class="sortable-item">
          <div class="sortable-header" @click="expandedContainer = expandedContainer === index ? null : index">
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
              :style="{ transform: expandedContainer === index ? 'rotate(90deg)' : 'none', transition: 'transform 0.15s' }" />
            <span class="sortable-label">{{ container.name || 'Container' }}</span>
            <Badge :color="'var(--fg-3)'">{{ container.type }}</Badge>
          </div>
          <TemplatesContainer
            v-if="expandedContainer === index"
            v-model:container="template.containers[index]!"
            :tools="tools"
            :on-delete="() => onDeleteContainer(index)"
          />
        </div>
      </div>
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

.drag-handle {
  cursor: grab;
}

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

.loading {
  padding: 40px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}
</style>

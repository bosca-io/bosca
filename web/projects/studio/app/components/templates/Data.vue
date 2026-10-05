<script lang="ts" setup>
import JsonEditorVue from 'json-editor-vue'
import Sortable from 'sortablejs'
import gql from 'graphql-tag'
import type {
  DataTemplate,
  TemplateAttribute,
  TemplateAttributeInput,
  TemplateAttributeTool,
} from '~/types/graphql'
import { toEditable } from '~/utils/toEditable'

interface DataTemplateResponse {
  content?: { metadata?: { dataTemplate?: DataTemplate } }
}

interface ToolsResponse {
  content?: { templateAttributeTools?: { all?: TemplateAttributeTool[] } }
}

interface EditableAttribute extends TemplateAttribute {
  __id?: string
  workflows?: Array<{ autoRun: boolean }>
}

const getDataTemplateQuery = gql`
  query GetDataTemplate($id: UUID!, $version: Int!) {
    content {
      metadata(id: $id, version: $version) {
        id version name
        dataTemplate {
          defaultAttributes
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
  mutation SetDataDefaultAttrs($id: UUID!, $version: Int!, $attributes: JSON!) {
    content { metadata { dataTemplate(metadataId: $id, metadataVersion: $version) { setDefaultAttributes(attributes: $attributes) { id } } } }
  }
`

const setAttrsMut = gql`
  mutation SetDataAttrs($id: UUID!, $version: Int!, $attributes: [TemplateAttributeInput!]!) {
    content { metadata { dataTemplate(metadataId: $id, metadataVersion: $version) { setAttributes(attributes: $attributes) { id } } } }
  }
`

const props = defineProps<{
  metadataId: string
  metadataVersion: number
}>()

const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const { data: templateData } = useAsyncQuery<DataTemplateResponse>('data-template', getDataTemplateQuery, {
  id: props.metadataId, version: props.metadataVersion,
})
const { data: toolsData } = useAsyncQuery<ToolsResponse>('data-tools', getToolsQuery, {})

const dataTemplate = computed(() => templateData.value?.content?.metadata?.dataTemplate)
const tools = computed<TemplateAttributeTool[]>(() => toolsData.value?.content?.templateAttributeTools?.all || [])

const template = ref<DataTemplate | null>(null)
const activeTab = ref('Attributes')
const tabs = ['Default Attributes', 'Attributes']
const saving = ref(false)

const attrsRef = useTemplateRef<HTMLElement>('attrsRef')
let attrsSortable: Sortable | null = null

onUnmounted(() => { attrsSortable?.destroy() })

const expandedAttr = ref<number | null>(null)

watch(dataTemplate, (v) => { if (v) bindTemplate() }, { immediate: true })

async function bindTemplate() {
  const ct = dataTemplate.value
  if (!ct) return
  template.value = reactive(toEditable(ct)!) as DataTemplate

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
    ui: 'INPUT', list: false, location: 'ITEM', configuration: null, workflows: [], tools: [],
  } as TemplateAttribute)
  expandedAttr.value = template.value!.attributes.length - 1
}

function onDeleteAttribute(index: number) {
  template.value!.attributes.splice(index, 1)
  expandedAttr.value = null
}

async function onSaveDefaultAttributes() {
  saving.value = true
  try {
    await gqlMutation(setDefaultAttrsMut, { id: props.metadataId, version: props.metadataVersion, attributes: template.value?.defaultAttributes || {} })
    toast.success('Default attributes saved')
  } catch (e: unknown) { toast.error((e instanceof Error ? e.message : null) || 'Failed') }
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
  } catch (e: unknown) { toast.error((e instanceof Error ? e.message : null) || 'Failed') }
  finally { saving.value = false }
}

function onSave() {
  if (activeTab.value === 'Attributes') onSaveAttributes()
  else if (activeTab.value === 'Default Attributes') onSaveDefaultAttributes()
}

defineExpose({ save: onSave, saving, activeTab, addAttribute: onAddAttribute })
</script>

<template>
  <div v-if="template" class="template-editor">
    <Tabs v-model="activeTab" :tabs="tabs" />

    <div v-if="activeTab === 'Default Attributes'" class="tab-body">
      <JsonEditorVue
        v-model="template.defaultAttributes"
        :main-menu-bar="false"
        :navigation-bar="false"
        class="json-editor" />
    </div>

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

.loading {
  padding: 40px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}
</style>

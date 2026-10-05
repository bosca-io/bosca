<script lang="ts" setup>
import JsonEditorVue from 'json-editor-vue'
import Sortable from 'sortablejs'
import gql from 'graphql-tag'
import type {
  CollectionTemplate,
  Ordering,
  OrderingInput,
  TemplateAttribute,
  TemplateAttributeInput,
  TemplateAttributeTool,
} from '~/types/graphql'
import { toEditable } from '~/utils/toEditable'

interface CollTemplateResponse {
  content?: { metadata?: { collectionTemplate?: CollectionTemplate } }
}

interface ToolsResponse {
  content?: { templateAttributeTools?: { all?: TemplateAttributeTool[] } }
}

/** Extended attribute with internal drag-sort ID and workflow data */
interface EditableAttribute extends TemplateAttribute {
  __id?: string
  workflows?: Array<{ autoRun: boolean }>
}

/** Extended ordering/filter with internal drag-sort ID */
interface EditableOrdering extends Ordering {
  __id?: string
}

interface EditableFilter {
  name: string
  filter: string
  __id?: string
}

const getCollectionTemplateQuery = gql`
  query GetCollTemplate($id: UUID!, $version: Int!) {
    content {
      metadata(id: $id, version: $version) {
        id version name
        collectionTemplate {
          defaultAttributes configuration
          filters { filters { name filter } }
          ordering { type field location order path }
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
  mutation SetCollDefaultAttrs($id: UUID!, $version: Int!, $attributes: JSON!) {
    content { metadata { collectionTemplate(metadataId: $id, metadataVersion: $version) { setDefaultAttributes(attributes: $attributes) { id } } } }
  }
`

const setConfigMut = gql`
  mutation SetCollConfig($id: UUID!, $version: Int!, $configuration: JSON!) {
    content { metadata { collectionTemplate(metadataId: $id, metadataVersion: $version) { setConfiguration(configuration: $configuration) { id } } } }
  }
`

const setFiltersMut = gql`
  mutation SetCollFilters($id: UUID!, $version: Int!, $filters: CollectionTemplateFiltersInput!) {
    content { metadata { collectionTemplate(metadataId: $id, metadataVersion: $version) { setFilters(filters: $filters) { id } } } }
  }
`

const setOrderingMut = gql`
  mutation SetCollOrdering($id: UUID!, $version: Int!, $ordering: [OrderingInput!]!) {
    content { metadata { collectionTemplate(metadataId: $id, metadataVersion: $version) { setOrdering(ordering: $ordering) { id } } } }
  }
`

const setAttrsMut = gql`
  mutation SetCollAttrs($id: UUID!, $version: Int!, $attributes: [TemplateAttributeInput!]!) {
    content { metadata { collectionTemplate(metadataId: $id, metadataVersion: $version) { setAttributes(attributes: $attributes) { id } } } }
  }
`

const props = defineProps<{
  metadataId: string
  metadataVersion: number
}>()

const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const { data: templateData } = useAsyncQuery<CollTemplateResponse>('coll-template', getCollectionTemplateQuery, {
  id: props.metadataId, version: props.metadataVersion,
})
const { data: toolsData } = useAsyncQuery<ToolsResponse>('coll-tools', getToolsQuery, {})

const collectionTemplate = computed(() => templateData.value?.content?.metadata?.collectionTemplate)
const tools = computed<TemplateAttributeTool[]>(() => toolsData.value?.content?.templateAttributeTools?.all || [])

const template = ref<CollectionTemplate | null>(null)
const activeTab = ref('Attributes')
const tabs = ['Default Attributes', 'Configuration', 'Filters', 'Ordering', 'Attributes']
const saving = ref(false)

const attrsRef = useTemplateRef<HTMLElement>('attrsRef')
const filtersRef = useTemplateRef<HTMLElement>('filtersRef')
const orderingRef = useTemplateRef<HTMLElement>('orderingRef')
let attrsSortable: Sortable | null = null
let filtersSortable: Sortable | null = null
let orderingSortable: Sortable | null = null

onUnmounted(() => {
  attrsSortable?.destroy()
  filtersSortable?.destroy()
  orderingSortable?.destroy()
})

const expandedAttr = ref<number | null>(null)
const expandedFilter = ref<number | null>(null)
const expandedOrdering = ref<number | null>(null)

watch(collectionTemplate, (v) => { if (v) bindTemplate() })

async function bindTemplate() {
  const ct = collectionTemplate.value
  if (!ct) return
  template.value = reactive(toEditable(ct)!) as CollectionTemplate
  if (!template.value.ordering) template.value.ordering = []
  if (!template.value.filters) template.value.filters = { filters: [] }
  else if (!template.value.filters.filters) template.value.filters.filters = []

  await nextTick()

  let id = 0
  for (const a of template.value.attributes) { (a as EditableAttribute).__id = (id++).toString() }
  for (const o of template.value.ordering!) { (o as EditableOrdering).__id = (id++).toString() }
  for (const f of template.value.filters!.filters) { (f as EditableFilter).__id = (id++).toString() }

  attrsSortable?.destroy()
  filtersSortable?.destroy()
  orderingSortable?.destroy()

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

  if (filtersRef.value) {
    filtersSortable = Sortable.create(filtersRef.value, {
      handle: '.drag-handle',
      onEnd: (evt) => {
        if (evt.oldIndex !== undefined && evt.newIndex !== undefined) {
          const item = template.value!.filters!.filters.splice(evt.oldIndex, 1)[0]
          if (item) template.value!.filters!.filters.splice(evt.newIndex, 0, item)
        }
      },
    })
  }

  if (orderingRef.value) {
    orderingSortable = Sortable.create(orderingRef.value, {
      handle: '.drag-handle',
      onEnd: (evt) => {
        if (evt.oldIndex !== undefined && evt.newIndex !== undefined) {
          const item = template.value!.ordering!.splice(evt.oldIndex, 1)[0]
          if (item) template.value!.ordering!.splice(evt.newIndex, 0, item)
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

function onAddFilter() {
  if (!template.value!.filters) template.value!.filters = { filters: [] }
  template.value!.filters!.filters.push({ name: '', filter: '' } as EditableFilter)
  expandedFilter.value = template.value!.filters!.filters.length - 1
}

function onDeleteFilter(index: number) {
  template.value!.filters!.filters.splice(index, 1)
  expandedFilter.value = null
}

function onAddOrdering() {
  if (!template.value!.ordering) template.value!.ordering = []
  template.value!.ordering!.push({ location: 'ITEM', order: 'ASCENDING', type: 'STRING' } as Ordering)
  expandedOrdering.value = template.value!.ordering!.length - 1
}

function onDeleteOrdering(index: number) {
  template.value!.ordering!.splice(index, 1)
  expandedOrdering.value = null
}

async function onSaveDefaultAttributes() {
  saving.value = true
  try {
    await gqlMutation(setDefaultAttrsMut, { id: props.metadataId, version: props.metadataVersion, attributes: template.value?.defaultAttributes || {} })
    toast.success('Default attributes saved')
  } catch (e: unknown) { toast.error((e instanceof Error ? e.message : null) || 'Failed') }
  finally { saving.value = false }
}

async function onSaveConfiguration() {
  saving.value = true
  try {
    await gqlMutation(setConfigMut, { id: props.metadataId, version: props.metadataVersion, configuration: template.value?.configuration || {} })
    toast.success('Configuration saved')
  } catch (e: unknown) { toast.error((e instanceof Error ? e.message : null) || 'Failed') }
  finally { saving.value = false }
}

async function onSaveFilters() {
  saving.value = true
  try {
    await gqlMutation(setFiltersMut, {
      id: props.metadataId, version: props.metadataVersion,
      filters: { filters: template.value?.filters?.filters?.map(f => ({ name: f.name, filter: f.filter })) || [] },
    })
    toast.success('Filters saved')
  } catch (e: unknown) { toast.error((e instanceof Error ? e.message : null) || 'Failed') }
  finally { saving.value = false }
}

async function onSaveOrdering() {
  saving.value = true
  try {
    const ordering: OrderingInput[] = (template.value?.ordering || []).map(o => ({
      field: o.field, location: o.location, order: o.order, path: o.path, type: o.type,
    }))
    await gqlMutation(setOrderingMut, { id: props.metadataId, version: props.metadataVersion, ordering })
    toast.success('Ordering saved')
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
  else if (activeTab.value === 'Filters') onSaveFilters()
  else if (activeTab.value === 'Ordering') onSaveOrdering()
  else if (activeTab.value === 'Default Attributes') onSaveDefaultAttributes()
  else if (activeTab.value === 'Configuration') onSaveConfiguration()
}

defineExpose({ save: onSave, saving, activeTab, addAttribute: onAddAttribute, addFilter: onAddFilter, addOrdering: onAddOrdering })

onMounted(() => { if (collectionTemplate.value) bindTemplate() })
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

    <div v-else-if="activeTab === 'Configuration'" class="tab-body">
      <JsonEditorVue
        v-model="template.configuration"
        :main-menu-bar="false"
        :navigation-bar="false"
        class="json-editor" />
    </div>

    <div v-else-if="activeTab === 'Filters'" class="tab-body">
      <div v-if="!template.filters?.filters?.length" class="empty-state">No filters defined. Add one to get started.</div>
      <div ref="filtersRef">
        <div v-for="(filter, index) in template.filters?.filters" :key="(filter as EditableFilter).__id" class="sortable-item">
          <div class="sortable-header" @click="expandedFilter = expandedFilter === index ? null : index">
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
              :style="{ transform: expandedFilter === index ? 'rotate(90deg)' : 'none', transition: 'transform 0.15s' }" />
            <span class="sortable-label">{{ filter.name || 'New Filter' }}</span>
          </div>
          <TemplatesFilter
            v-if="expandedFilter === index"
            v-model:filter="template.filters!.filters[index]!"
            :on-delete="() => onDeleteFilter(index)"
          />
        </div>
      </div>
    </div>

    <div v-else-if="activeTab === 'Ordering'" class="tab-body">
      <div v-if="!template.ordering?.length" class="empty-state">No ordering rules defined. Add one to get started.</div>
      <div ref="orderingRef">
        <div v-for="(order, index) in template.ordering" :key="(order as EditableOrdering).__id" class="sortable-item">
          <div class="sortable-header" @click="expandedOrdering = expandedOrdering === index ? null : index">
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
              :style="{ transform: expandedOrdering === index ? 'rotate(90deg)' : 'none', transition: 'transform 0.15s' }" />
            <span class="sortable-label">{{ order.field || order.path?.join('.') || 'New Rule' }}</span>
            <Badge :color="'var(--fg-3)'">{{ order.order }}</Badge>
          </div>
          <TemplatesOrdering
            v-if="expandedOrdering === index"
            v-model:ordering="template.ordering![index]!"
            :on-delete="() => onDeleteOrdering(index)"
          />
        </div>
      </div>
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

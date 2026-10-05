<script lang="ts" setup>
import {
  DocumentTemplateContainerType,
  type DocumentTemplateContainer,
  type TemplateAttributeTool,
} from '~/types/graphql'

const props = defineProps<{
  tools: TemplateAttributeTool[]
  onDelete: () => void
}>()

const container = defineModel<DocumentTemplateContainer>('container', { required: true })

const typeOptions = Object.values(DocumentTemplateContainerType).map(t => ({ value: t, label: t }))

const attr = reactive({
  id: container.value.id,
  name: container.value.name,
  supplementaryKey: container.value.supplementaryKey || '',
  type: container.value.type,
  description: container.value.description,
  tools: (container.value.tools || []).map(t => ({ ...t, name: t.name ?? '', description: t.description ?? '', query: t.query ?? '', resultPath: t.resultPath ?? '' })),
  renderers: (container.value.renderers || []).map(r => ({
    name: r.name,
    configuration: r.configuration ? JSON.stringify(r.configuration) : '',
  })),
  filters: [...(container.value.filters || [])] as string[],
})

const toolsOptions = computed(() =>
  props.tools?.map(t => ({ value: t.id, label: t.name })) || []
)

function onToolSelected(index: number, toolId: string) {
  const selected = props.tools.find(t => t.id === toolId)
  if (selected && attr.tools[index]) {
    attr.tools[index] = { id: selected.id, name: '', description: '', query: '', resultPath: '' }
  }
}

function onClearTool(index: number) {
  if (attr.tools[index]) {
    attr.tools[index] = { id: null, name: '', description: '', query: '', resultPath: '' }
  }
}

function addTool() {
  attr.tools.push({ id: null, name: '', description: '', query: '', resultPath: '' })
}

function removeTool(index: number) {
  attr.tools.splice(index, 1)
}

function addRenderer() {
  attr.renderers.push({ name: '', configuration: '' })
}

function removeRenderer(index: number) {
  attr.renderers.splice(index, 1)
}

function addFilter() {
  attr.filters.push('')
}

function removeFilter(index: number) {
  attr.filters.splice(index, 1)
}

const expandedTool = ref<number | null>(null)

function syncToModel() {
  container.value.id = attr.id
  container.value.name = attr.name
  container.value.description = attr.description || ''
  container.value.supplementaryKey = attr.supplementaryKey === '' ? null : attr.supplementaryKey
  container.value.type = attr.type
  container.value.tools = attr.tools
  container.value.renderers = attr.renderers
    .filter(r => r.name.trim() !== '')
    .map(r => ({
      name: r.name,
      configuration: r.configuration ? (() => { try { return JSON.parse(r.configuration) } catch { return null } })() : null,
    }))
  container.value.filters = attr.filters.filter(f => f.trim() !== '')
}

watch(attr, syncToModel)
</script>

<template>
  <div class="container-editor">
    <div class="container-grid">
      <TextInput
        v-model="attr.id"
        label="ID"
        mono
        placeholder="container-id" />
      <TextInput v-model="attr.name" label="Name" placeholder="Container Name" />
      <Select v-model="attr.type" :options="typeOptions" label="Type" />
      <TextInput
        v-model="attr.supplementaryKey"
        label="Supplementary Key"
        mono
        placeholder="optional" />
    </div>

    <TextInput v-model="attr.description" label="Description" placeholder="Describe this container…" />

    <!-- Tools -->
    <div class="sub-section">
      <div class="section-header">
        <span class="section-label">Tools</span>
        <Button size="sm" icon="plus" @click="addTool">Add</Button>
      </div>
      <div v-for="(tool, index) in attr.tools" :key="index" class="sub-item">
        <div class="sub-item-header" @click="expandedTool = expandedTool === index ? null : index">
          <Icon
            name="chevron"
            :size="12"
            color="var(--fg-3)"
            :style="{ transform: expandedTool === index ? 'rotate(90deg)' : 'none', transition: 'transform 0.15s' }" />
          <span class="sub-item-name">{{ tool.name || 'Tool' }}</span>
          <span style="flex: 1" />
          <button class="sub-item-delete" @click.stop="removeTool(index)">
            <Icon name="trash" :size="12" color="var(--err)" />
          </button>
        </div>
        <div v-if="expandedTool === index" class="sub-item-body">
          <Select
            :model-value="tool.id ?? ''"
            :options="[{ value: '', label: '— Custom tool —' }, ...toolsOptions]"
            label="Pre-defined Tool"
            @update:model-value="(val: string | string[] | null | undefined) => typeof val === 'string' && val ? onToolSelected(index, val) : onClearTool(index)"
          />
          <template v-if="!tool.id">
            <TextInput v-model="tool.name" label="Name" placeholder="Tool name" />
            <TextInput v-model="tool.description" label="Description" placeholder="Description" />
            <Textarea
              v-model="tool.query"
              label="GraphQL Query"
              :rows="4"
              mono
              placeholder="query { ... }" />
            <TextInput
              v-model="tool.resultPath"
              label="Result Path"
              mono
              placeholder="data.content.metadata" />
          </template>
        </div>
      </div>
    </div>

    <!-- Renderers (only for METADATA type) -->
    <div v-if="attr.type === 'METADATA'" class="sub-section">
      <div class="section-header">
        <span class="section-label">Renderers</span>
        <Button size="sm" icon="plus" @click="addRenderer">Add</Button>
      </div>
      <div v-for="(r, index) in attr.renderers" :key="index" class="renderer-row">
        <TextInput v-model="r.name" label="Name" placeholder="Renderer name" />
        <TextInput
          v-model="r.configuration"
          label="Configuration (JSON)"
          mono
          placeholder="{}" />
        <button class="renderer-delete" @click="removeRenderer(index)">
          <Icon name="trash" :size="12" color="var(--err)" />
        </button>
      </div>
    </div>

    <!-- Search Filters (only for METADATA type) -->
    <div v-if="attr.type === 'METADATA'" class="sub-section">
      <div class="section-header">
        <span class="section-label">Search Filters</span>
        <Button size="sm" icon="plus" @click="addFilter">Add</Button>
      </div>
      <div v-for="(_, index) in attr.filters" :key="index" class="filter-row">
        <TextInput v-model="attr.filters[index]" mono placeholder="e.g. traitIds = blog-post" />
        <button class="renderer-delete" @click="removeFilter(index)">
          <Icon name="trash" :size="12" color="var(--err)" />
        </button>
      </div>
    </div>

    <div class="delete-row">
      <Button size="sm" icon="trash" @click="onDelete">Delete Container</Button>
    </div>
  </div>
</template>

<style scoped>
.container-editor {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  border-top: 1px solid var(--line);
  background: color-mix(in oklch, var(--bg-2) 50%, transparent);
}

.container-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.sub-section {
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  overflow: hidden;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
}

.section-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-2);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  flex: 1;
}

.sub-item {
  border-top: 1px solid var(--line);
}

.sub-item-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  cursor: pointer;
  transition: background 0.15s;
}

.sub-item-header:hover {
  background: color-mix(in oklch, var(--brand-2) 4%, transparent);
}

.sub-item-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-1);
}

.sub-item-delete {
  width: 22px;
  height: 22px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: opacity 0.15s;
}

.sub-item-header:hover .sub-item-delete { opacity: 1; }
.sub-item-delete:hover { background: color-mix(in oklch, var(--err) 10%, transparent); }

.sub-item-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border-top: 1px solid var(--line);
  background: var(--bg-2);
}

.renderer-row,
.filter-row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 8px 12px;
  border-top: 1px solid var(--line);
}

.renderer-row > :first-child,
.filter-row > :first-child {
  flex: 1;
}

.renderer-row > :nth-child(2) {
  flex: 1;
}

.renderer-delete {
  width: 28px;
  height: 28px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-bottom: 2px;
}

.renderer-delete:hover {
  background: color-mix(in oklch, var(--err) 10%, transparent);
}

.delete-row {
  display: flex;
  justify-content: flex-end;
}
</style>

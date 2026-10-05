<script lang="ts" setup>
import JsonEditorVue from 'json-editor-vue'
import {
  AttributeLocation,
  AttributeType,
  AttributeUiType,
  type TemplateAttribute,
  type TemplateAttributeTool,
} from '~/types/graphql'

const props = defineProps<{
  tools: TemplateAttributeTool[]
  onDelete: () => void
}>()

const attribute = defineModel<TemplateAttribute>('attribute', { required: true })

const typeOptions = Object.values(AttributeType).map(t => ({ value: t, label: t }))
const uiOptions = Object.values(AttributeUiType).map(t => ({ value: t, label: t }))
const locationOptions = Object.values(AttributeLocation).map(t => ({ value: t, label: t }))

const attr = reactive({
  configuration: attribute.value.configuration,
  description: attribute.value.description || '',
  key: attribute.value.key,
  list: attribute.value.list,
  location: attribute.value.location,
  name: attribute.value.name,
  supplementaryKey: attribute.value.supplementaryKey || '',
  type: attribute.value.type,
  ui: attribute.value.ui,
  tools: (attribute.value.tools || []).map(t => ({ ...t, name: t.name ?? '', description: t.description ?? '', query: t.query ?? '', resultPath: t.resultPath ?? '' })),
})

const toolsOptions = computed(() =>
  props.tools?.map(t => ({ value: t.id, label: t.name })) || []
)

function onToolSelected(index: number, toolId: string) {
  const selected = props.tools.find(t => t.id === toolId)
  if (selected) {
    attr.tools[index] = { id: selected.id, name: '', description: '', query: '', resultPath: '' }
  }
}

function onClearTool(index: number) {
  attr.tools[index] = { id: null, name: '', description: '', query: '', resultPath: '' }
}

function addTool() {
  attr.tools.push({ id: null, name: '', description: '', query: '', resultPath: '' })
}

function removeTool(index: number) {
  attr.tools.splice(index, 1)
}

const showConfig = ref(false)
const expandedTool = ref<number | null>(null)

const presets = [
  {
    label: '16/9 Image',
    value: {
      actions: [
        { icon: 'i-lucide-maximize', name: 'Maximize', action: 'maximize' },
        { icon: 'i-lucide-zoom-in', name: 'Zoom In', action: 'zoomin' },
        { icon: 'i-lucide-zoom-out', name: 'Zoom Out', action: 'zoomout' },
        { icon: 'i-lucide-copy', name: 'Copy', action: 'copy' },
        { icon: 'i-lucide-clipboard-paste', name: 'Paste', action: 'paste' },
      ],
      targetSize: [740, 416],
      aspectRatios: [
        { icon: 'i-lucide-rectangle-horizontal', name: '16/9', value: 1.77777777778, default: true },
      ],
      relationship: 'image.featured',
    },
  },
]

function onPresetSelected(presetValue: string | string[] | null | undefined) {
  if (typeof presetValue !== 'string') return
  const preset = presets.find(p => p.label === presetValue)
  if (preset) attr.configuration = preset.value
}

function syncToModel() {
  attribute.value.configuration = attr.configuration
  attribute.value.description = attr.description || ''
  attribute.value.key = attr.key
  attribute.value.list = attr.list
  attribute.value.location = attr.location
  attribute.value.name = attr.name
  attribute.value.supplementaryKey = attr.supplementaryKey === '' ? null : attr.supplementaryKey
  attribute.value.type = attr.type
  attribute.value.ui = attr.ui
  attribute.value.tools = attr.tools
}

watch(attr, syncToModel)
</script>

<template>
  <div class="attr-editor">
    <div class="attr-grid">
      <TextInput
        v-model="attr.key"
        label="Key"
        mono
        placeholder="attribute_key" />
      <TextInput v-model="attr.name" label="Name" placeholder="Display Name" />
      <Select v-model="attr.type" :options="typeOptions" label="Type" />
      <Select v-model="attr.ui" :options="uiOptions" label="UI Widget" />
      <Select v-model="attr.location" :options="locationOptions" label="Location" />
      <TextInput
        v-model="attr.supplementaryKey"
        label="Supplementary Key"
        mono
        placeholder="optional" />
    </div>

    <Textarea
      v-model="attr.description"
      label="Description"
      :rows="2"
      placeholder="Describe this attribute…" />

    <div class="attr-row">
      <Switch :model-value="attr.list" label="Is List" @update:model-value="attr.list = $event" />
    </div>

    <!-- Tools -->
    <div class="tools-section">
      <div class="section-header">
        <span class="section-label">Tools</span>
        <Button size="sm" icon="plus" @click="addTool">Add Tool</Button>
      </div>
      <div v-for="(tool, index) in attr.tools" :key="index" class="tool-card">
        <div class="tool-header" @click="expandedTool = expandedTool === index ? null : index">
          <Icon
            name="chevron"
            :size="12"
            color="var(--fg-3)"
            :style="{ transform: expandedTool === index ? 'rotate(90deg)' : 'none', transition: 'transform 0.15s' }" />
          <span class="tool-name">{{ tool.name || (tool.id ? toolsOptions.find(t => t.value === tool.id)?.label : 'New Tool') || 'Tool' }}</span>
          <span style="flex: 1" />
          <button class="tool-delete" @click.stop="removeTool(index)">
            <Icon name="trash" :size="12" color="var(--err)" />
          </button>
        </div>
        <div v-if="expandedTool === index" class="tool-body">
          <Select
            :model-value="tool.id ?? ''"
            :options="[{ value: '', label: '— Custom tool —' }, ...toolsOptions]"
            label="Pre-defined Tool"
            @update:model-value="(val: string | string[] | null | undefined) => typeof val === 'string' && val ? onToolSelected(index, val) : onClearTool(index)"
          />
          <template v-if="!tool.id">
            <TextInput v-model="tool.name" label="Name" placeholder="Tool name" />
            <TextInput v-model="tool.description" label="Description" placeholder="What does this tool do?" />
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

    <!-- Configuration -->
    <div class="config-section">
      <div class="section-header" style="cursor: pointer" @click="showConfig = !showConfig">
        <Icon
          name="chevron"
          :size="12"
          color="var(--fg-3)"
          :style="{ transform: showConfig ? 'rotate(90deg)' : 'none', transition: 'transform 0.15s' }" />
        <span class="section-label">Configuration</span>
      </div>
      <div v-if="showConfig" class="config-body">
        <Select
          model-value=""
          :options="[{ value: '', label: '— Apply a preset —' }, ...presets.map(p => ({ value: p.label, label: p.label }))]"
          label="Preset"
          @update:model-value="onPresetSelected"
        />
        <JsonEditorVue
          v-model="attr.configuration"
          :main-menu-bar="false"
          :navigation-bar="false"
          class="json-editor"
        />
      </div>
    </div>

    <div class="delete-row">
      <Button size="sm" icon="trash" @click="onDelete">Delete Attribute</Button>
    </div>
  </div>
</template>

<style scoped>
.attr-editor {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  border-top: 1px solid var(--line);
  background: color-mix(in oklch, var(--bg-2) 50%, transparent);
}

.attr-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.attr-row {
  display: flex;
  align-items: center;
  gap: 14px;
}

.tools-section,
.config-section {
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

.tool-card {
  border-top: 1px solid var(--line);
}

.tool-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  cursor: pointer;
  transition: background 0.15s;
}

.tool-header:hover {
  background: color-mix(in oklch, var(--brand-2) 4%, transparent);
}

.tool-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-1);
}

.tool-delete {
  width: 22px;
  height: 22px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: opacity 0.15s;
}

.tool-header:hover .tool-delete { opacity: 1; }
.tool-delete:hover { background: color-mix(in oklch, var(--err) 10%, transparent); }

.tool-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border-top: 1px solid var(--line);
  background: var(--bg-2);
}

.config-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border-top: 1px solid var(--line);
}

.json-editor {
  border-radius: var(--r-sm);
  min-height: 120px;
}

.delete-row {
  display: flex;
  justify-content: flex-end;
}
</style>

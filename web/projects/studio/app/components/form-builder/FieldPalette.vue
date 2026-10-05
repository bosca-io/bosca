<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import Sortable from 'sortablejs'
import type { UiSchemaNode } from '@bosca/forms'
import { nextFieldKey } from './palette-utils'

const emit = defineEmits<{
  addNode: [node: UiSchemaNode]
}>()

const fieldControls = [
  { label: 'Text Input', icon: 'form', control: 'text-input', schemaType: 'string' },
  { label: 'Text Area', icon: 'doc', control: 'textarea', schemaType: 'string' },
  { label: 'Number', icon: 'grid9', control: 'number-input', schemaType: 'integer' },
  { label: 'Select', icon: 'chevronDown', control: 'select', schemaType: 'string' },
  { label: 'GraphQL Select', icon: 'database', control: 'graphql-select', schemaType: 'string' },
  { label: 'API Script Select', icon: 'code', control: 'api-script-select', schemaType: 'string' },
  { label: 'Work Ops Select', icon: 'kanban', control: 'workops-select', schemaType: 'string' },
  { label: 'Checkbox', icon: 'check', control: 'checkbox', schemaType: 'boolean' },
  { label: 'Switch', icon: 'check', control: 'switch', schemaType: 'boolean' },
  { label: 'Date Picker', icon: 'calendar', control: 'date-picker', schemaType: 'string' },
  { label: 'Tags', icon: 'tag', control: 'tag-input', schemaType: 'array' },
  { label: 'Radio Group', icon: 'target', control: 'radio-group', schemaType: 'string' },
  { label: 'Color Picker', icon: 'wand', control: 'color-picker', schemaType: 'string' },
  { label: 'Image Upload', icon: 'image', control: 'image-upload', schemaType: 'string' },
  { label: 'File Upload', icon: 'upload', control: 'file-upload', schemaType: 'string' },
]

const layoutControls = [
  { label: 'Section', icon: 'container', nodeType: 'section' },
  { label: 'Row', icon: 'columns', nodeType: 'row' },
]

const displayControls = [
  { label: 'Alert', icon: 'alert', control: 'alert' },
  { label: 'Divider', icon: 'list', control: 'divider' },
  { label: 'Heading', icon: 'heading2', control: 'heading' },
  { label: 'Help Text', icon: 'info', control: 'help-text' },
]

function addField(ctrl: (typeof fieldControls)[0]) {
  const key = nextFieldKey()
  emit('addNode', {
    type: 'field',
    property: key,
    control: ctrl.control,
    label: ctrl.label,
  } as UiSchemaNode)
}

function addLayout(type: string) {
  if (type === 'Section') {
    emit('addNode', {
      type: 'section',
      label: 'New Section',
      children: [],
    } as UiSchemaNode)
  } else if (type === 'Row') {
    emit('addNode', {
      type: 'row',
      children: [],
    } as UiSchemaNode)
  }
}

function addDisplay(ctrl: (typeof displayControls)[0]) {
  emit('addNode', {
    type: 'display',
    control: ctrl.control,
    text: ctrl.label === 'Divider' ? undefined : `${ctrl.label} text`,
    variant: ctrl.control === 'alert' ? 'info' : undefined,
  } as UiSchemaNode)
}

const fieldListRef = ref<HTMLElement | null>(null)
const layoutListRef = ref<HTMLElement | null>(null)
const displayListRef = ref<HTMLElement | null>(null)

const sortableInstances: Sortable[] = []

function initSortables() {
  const group = { name: 'layout-nodes', pull: 'clone' as const, put: false as const }
  const opts: Sortable.Options = {
    group,
    sort: false,
    animation: 150,
    ghostClass: 'palette-ghost',
  }

  if (fieldListRef.value) {
    sortableInstances.push(Sortable.create(fieldListRef.value, opts))
  }
  if (layoutListRef.value) {
    sortableInstances.push(Sortable.create(layoutListRef.value, opts))
  }
  if (displayListRef.value) {
    sortableInstances.push(Sortable.create(displayListRef.value, opts))
  }
}

onMounted(initSortables)
onUnmounted(() => sortableInstances.forEach(s => s.destroy()))
</script>

<template>
  <div class="palette">
    <div class="palette-heading">Data Fields</div>
    <div ref="fieldListRef" class="palette-list">
      <div
        v-for="ctrl in fieldControls"
        :key="ctrl.control"
        data-palette-type="field"
        :data-control="ctrl.control"
        :data-label="ctrl.label"
        :data-schema-type="ctrl.schemaType"
        class="palette-item"
        @click="addField(ctrl)"
      >
        <Icon :name="ctrl.icon" :size="13" color="var(--fg-3)" />
        <span>{{ ctrl.label }}</span>
      </div>
    </div>

    <div class="palette-heading">Layout</div>
    <div ref="layoutListRef" class="palette-list">
      <div
        v-for="ctrl in layoutControls"
        :key="ctrl.label"
        data-palette-type="layout"
        :data-node-type="ctrl.nodeType"
        :data-label="ctrl.label"
        class="palette-item"
        @click="addLayout(ctrl.label)"
      >
        <Icon :name="ctrl.icon" :size="13" color="var(--fg-3)" />
        <span>{{ ctrl.label }}</span>
      </div>
    </div>

    <div class="palette-heading">Display</div>
    <div ref="displayListRef" class="palette-list">
      <div
        v-for="ctrl in displayControls"
        :key="ctrl.control"
        data-palette-type="display"
        :data-control="ctrl.control"
        :data-label="ctrl.label"
        class="palette-item"
        @click="addDisplay(ctrl)"
      >
        <Icon :name="ctrl.icon" :size="13" color="var(--fg-3)" />
        <span>{{ ctrl.label }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.palette {
  overflow-y: auto;
}

.palette-heading {
  font-size: 10.5px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--fg-3);
  margin-bottom: 6px;
  margin-top: 14px;
}

.palette-heading:first-child {
  margin-top: 0;
}

.palette-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
  margin-bottom: 4px;
}

.palette-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: var(--r-xs);
  font-size: 12.5px;
  color: var(--fg-1);
  cursor: grab;
  transition: background 0.12s;
}

.palette-item:active {
  cursor: grabbing;
}

.palette-item:hover {
  background: var(--bg-3);
}

.palette-ghost {
  opacity: 0.3;
}
</style>

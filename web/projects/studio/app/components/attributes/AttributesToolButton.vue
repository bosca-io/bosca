<script lang="ts" setup>
import type { AttributeState } from '~/utils/editor/attribute'
import type { TemplateAttributeTool } from '~/utils/editor/tool'

const props = defineProps<{
  attribute: AttributeState
  editable: boolean
  toolsEnabled: boolean

  onRunTool: (_attribute: AttributeState, _tool: TemplateAttributeTool) => void
}>()

const tools = computed(() => props.attribute.tools ?? [])
const hasTools = computed(() => tools.value.length > 0)

// A property may expose several tools, but it always shows a single button.
// One tool runs immediately; multiple tools open a picker so the titlebar
// never accumulates a button per tool.
const pickerOpen = ref(false)

const buttonTitle = computed(() => {
  const list = tools.value
  if (list.length === 1) {
    const tool = list[0]!
    return tool.description || tool.name || 'Run tool'
  }
  return 'Run a tool'
})

const popoverTitle = computed(() => {
  const list = tools.value
  if (list.length === 1) return list[0]!.name || 'Run tool'
  return 'Tools'
})

const popoverHint = computed(() => {
  if (props.attribute.loading.value) return 'Running…'
  if (!props.toolsEnabled) return "Tools aren't available here."
  return tools.value.length > 1 ? 'Click to choose a tool.' : 'Click to run.'
})

function runTool(tool: TemplateAttributeTool) {
  if (props.attribute.loading.value) return
  props.onRunTool(props.attribute, tool)
}

function onClick() {
  if (props.attribute.loading.value || !props.toolsEnabled) return
  const list = tools.value
  if (list.length === 1) {
    runTool(list[0]!)
  } else if (list.length > 1) {
    pickerOpen.value = true
  }
}

function onPick(tool: TemplateAttributeTool) {
  pickerOpen.value = false
  runTool(tool)
}
</script>

<template>
  <template v-if="editable && hasTools">
    <Popover
      trigger="mouseenter"
      placement="bottom"
      :interactive="false"
      :delay="150"
    >
      <template #trigger>
        <button
          class="tool-btn"
          :title="buttonTitle"
          :disabled="attribute.loading.value || !toolsEnabled"
          @click="onClick"
        >
          <Icon
            :name="attribute.loading.value ? 'spinner' : 'sparkles'"
            :size="16"
            :class="{ 'tool-spin': attribute.loading.value }"
            :color="toolsEnabled ? 'var(--brand-2)' : 'var(--fg-3)'"
          />
        </button>
      </template>
      <div class="tool-popover">
        <div class="tool-popover-title">
          <Icon name="sparkles" :size="16" /> {{ popoverTitle }}
        </div>
        <p
          v-if="tools.length === 1 && tools[0]?.description"
          class="tool-popover-text"
        >
          {{ tools[0]?.description }}
        </p>
        <ul v-else-if="tools.length > 1" class="tool-popover-list">
          <li v-for="(tool, i) in tools" :key="tool.name ?? i" class="tool-popover-item">
            <span class="tool-popover-name">{{ tool.name || 'Tool' }}</span>
            <span v-if="tool.description" class="tool-popover-desc">{{ tool.description }}</span>
          </li>
        </ul>
        <p class="tool-popover-hint">{{ popoverHint }}</p>
      </div>
    </Popover>

    <AttributesToolPickerModal
      v-if="pickerOpen"
      :tools="tools"
      @select="onPick"
      @close="pickerOpen = false"
    />
  </template>
</template>

<style scoped>
.tool-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 4px;
  background: transparent;
  border: none;
  cursor: pointer;
  transition: background 0.12s;
}

.tool-btn:hover:not(:disabled) {
  background: color-mix(in oklch, var(--brand-2) 12%, transparent);
}

.tool-btn:disabled {
  cursor: not-allowed;
}

.tool-spin {
  animation: tool-spin 0.9s linear infinite;
}

@keyframes tool-spin {
  to { transform: rotate(360deg); }
}

.tool-popover {
  max-width: 320px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 6px;
}

.tool-popover-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-0);
}

.tool-popover-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--fg-2);
}

.tool-popover-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tool-popover-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.tool-popover-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-1);
}

.tool-popover-desc {
  font-size: 12.5px;
  line-height: 1.4;
  color: var(--fg-3);
}

.tool-popover-hint {
  margin: 0;
  font-size: 12px;
  color: var(--fg-3);
}
</style>

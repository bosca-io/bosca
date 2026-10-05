<script lang="ts" setup>
import type { TemplateAttributeTool } from '~/utils/editor/tool'

defineProps<{
  tools: TemplateAttributeTool[]
}>()

const emit = defineEmits<{
  select: [_tool: TemplateAttributeTool]
  close: []
}>()
</script>

<template>
  <Modal
    title="Run a tool"
    subtitle="Choose which tool to run for this property"
    icon="sparkles"
    accent="var(--brand-2)"
    width="480px"
    @close="emit('close')"
  >
    <div class="tool-list">
      <button
        v-for="(tool, i) in tools"
        :key="tool.name ?? i"
        class="tool-row"
        @click="emit('select', tool)"
      >
        <span class="tool-icon">
          <Icon name="sparkles" :size="14" color="var(--brand-2)" />
        </span>
        <span class="tool-text">
          <span class="tool-name">{{ tool.name || 'Tool' }}</span>
          <span v-if="tool.description" class="tool-desc">{{ tool.description }}</span>
        </span>
      </button>
    </div>
  </Modal>
</template>

<style scoped>
.tool-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 400px;
  overflow-y: auto;
  padding: 2px;
}

.tool-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  cursor: pointer;
  text-align: left;
  transition: border-color 0.12s;
}

.tool-row:hover {
  border-color: var(--brand-2);
}

.tool-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}

.tool-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.tool-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-1);
}

.tool-desc {
  font-size: 12px;
  color: var(--fg-3);
}
</style>

<script setup lang="ts">
import { NodeResizer } from '@vue-flow/node-resizer'
import '@vue-flow/node-resizer/dist/style.css'
import { ref } from 'vue'

/**
 * A visual group frame on the pipeline canvas (Vue Flow `type: 'group'` node). Purely an editor
 * annotation — it carries no execution semantics. Member nodes are re-parented to it (Vue Flow
 * `parentNode`) so they move with it; collapsing hides the members and shrinks the frame to its header.
 * Rendered behind functional nodes (lower z-index, set on the flow node).
 */
const props = defineProps<{
  id: string
  data: { label: string, collapsed: boolean, color: string | null, memberCount: number }
  selected: boolean
}>()
const emit = defineEmits<{
  (e: 'update:label', value: string): void
  (e: 'toggle-collapse'): void
  (e: 'remove'): void
}>()

const accent = () => props.data.color || 'var(--accent, #f59e0b)'

const editing = ref(false)
const draft = ref('')
function startEdit() {
  draft.value = props.data.label
  editing.value = true
}
function commitEdit() {
  if (!editing.value) return
  editing.value = false
  emit('update:label', draft.value.trim())
}
</script>

<template>
  <div
    class="group-node"
    :class="{ collapsed: data.collapsed, selected }"
    :style="{ '--group-accent': accent() }"
  >
    <!-- Resize handles only when expanded; collapsed frame is header-sized. -->
    <NodeResizer
      v-if="!data.collapsed"
      :min-width="180"
      :min-height="120"
      :color="accent()"
    />

    <header class="group-header">
      <button
        class="group-btn"
        :title="data.collapsed ? 'Expand group' : 'Collapse group'"
        @pointerdown.stop
        @click.stop="emit('toggle-collapse')"
      >
        <Icon
          :name="data.collapsed ? 'chevron-right' : 'chevron-down'"
          :size="14"
        />
      </button>

      <input
        v-if="editing"
        ref="labelInput"
        v-model="draft"
        class="group-label-input"
        placeholder="Describe this group…"
        @pointerdown.stop
        @keydown.enter.prevent="commitEdit"
        @keydown.esc.prevent="editing = false"
        @blur="commitEdit"
      >
      <span
        v-else
        class="group-label"
        :title="data.collapsed ? `${data.memberCount} node(s) hidden — double-click to rename` : 'Double-click to rename'"
        @dblclick.stop="startEdit"
      >
        {{ data.label || 'Group' }}
        <span
          v-if="data.collapsed"
          class="group-count"
        >{{ data.memberCount }}</span>
      </span>

      <button
        class="group-btn group-remove"
        title="Remove group (keeps the nodes)"
        @pointerdown.stop
        @click.stop="emit('remove')"
      >
        <Icon
          name="x"
          :size="12"
        />
      </button>
    </header>
  </div>
</template>

<style scoped>
.group-node {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  border: 1.5px dashed var(--group-accent);
  border-radius: 12px;
  background: color-mix(in oklch, var(--group-accent) 7%, transparent);
  display: flex;
  flex-direction: column;
}
.group-node.selected {
  border-style: solid;
  box-shadow: 0 0 0 2px color-mix(in oklch, var(--group-accent) 40%, transparent);
}
.group-node.collapsed {
  height: auto;
  background: color-mix(in oklch, var(--group-accent) 14%, transparent);
}
.group-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 6px;
  /* The header is the drag handle for the whole frame; the body is transparent to clicks so nodes
     behind a (non-collapsed) frame stay interactive. */
  cursor: grab;
}
.group-label {
  flex: 1;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.01em;
  color: var(--text, #e2e8f0);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.group-count {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 999px;
  background: color-mix(in oklch, var(--group-accent) 30%, transparent);
  color: var(--text, #e2e8f0);
}
.group-label-input {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--text, #e2e8f0);
  background: var(--surface, #0f172a);
  border: 1px solid var(--group-accent);
  border-radius: 6px;
  padding: 2px 6px;
}
.group-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--muted, #94a3b8);
  cursor: pointer;
}
.group-btn:hover {
  background: color-mix(in oklch, var(--group-accent) 22%, transparent);
  color: var(--text, #e2e8f0);
}
.group-remove:hover {
  color: var(--danger, #f87171);
}
</style>

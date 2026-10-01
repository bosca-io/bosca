<script setup lang="ts">
import { DragHandle } from '@tiptap/extension-drag-handle-vue-3'
import type { Editor } from '@tiptap/vue-3'

// `editable` must come in as a prop: the editor starts read-only until the
// collaboration doc syncs and is flipped via setEditable(), which a child
// component reading `editor.isEditable` never re-renders for.
defineProps<{
  editor: Editor
  editable: boolean
}>()
</script>

<template>
  <DragHandle :editor="editor" :tippy-options="{ offset: [0, 4] }">
    <div v-if="editable" class="drag-handle">
      <Icon name="grip" :size="14" color="var(--fg-3)" />
    </div>
  </DragHandle>
</template>

<style scoped>
.drag-handle {
  background: var(--bg-2);
  border-radius: 6px;
  cursor: grab;
  padding: 4px 2px;
  display: flex;
  align-items: center;
}

.drag-handle:hover {
  background: var(--bg-3);
}
</style>

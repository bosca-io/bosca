<script setup lang="ts">
import { CommandItems } from '~/utils/editor/commanditems'
import { BubbleMenu } from '@tiptap/vue-3'
import type { Editor } from '@tiptap/vue-3'

defineProps<{
  editor: Editor
}>()

function isItemActive(item: typeof CommandItems[number], editor: Editor) {
  if (item.name) return editor.isActive(item.name, item.attributes)
  if (item.attributes) return editor.isActive(item.attributes)
  return false
}
</script>

<template>
  <BubbleMenu
    class="bubble-menu-bar"
    :tippy-options="{ duration: 100, offset: [0, 20] }"
    :editor="editor"
  >
    <div class="bubble-menu-inner">
      <button
        v-for="(item, index) in CommandItems"
        :key="index.toString() + '-' + item.name"
        class="bubble-btn"
        :class="{ 'bubble-btn--active': isItemActive(item, editor) }"
        @click="item.command({ editor })"
      >
        <Icon :name="item.icon" :size="14" />
      </button>
    </div>
  </BubbleMenu>
</template>

<style scoped>
.bubble-menu-bar {
  display: flex;
  flex-wrap: nowrap;
}

.bubble-menu-inner {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 4px;
  background: color-mix(in srgb, var(--bg-1) 70%, transparent);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid var(--line);
  border-radius: 8px;
  box-shadow: 0 8px 24px -8px rgba(0, 0, 0, 0.5);
  margin-left: 8px;
}

.bubble-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  color: var(--fg-2);
  background: transparent;
  border: none;
  cursor: pointer;
}

.bubble-btn:hover {
  background: var(--bg-2);
  color: var(--fg-0);
}

.bubble-btn--active {
  background: var(--bg-3);
  color: var(--fg-0);
}
</style>

<script lang="ts">
import { hideAll } from 'tippy.js'
import type { Editor } from '@tiptap/core'
import type { CommandItem } from '~/utils/editor/commanditems'

export default {
  props: {
    items: { type: Array as () => CommandItem[], required: true },
    editor: { type: Object as () => Editor, required: true },
    command: { type: Function, required: true },
  },

  data() {
    return { selection: 0 }
  },

  watch: {
    items() {
      this.selection = 0
    },
  },

  methods: {
    onKeyDown({ event }: { event: KeyboardEvent }) {
      switch (event.key) {
        case 'ArrowUp':
        case 'ArrowLeft':
          this.upHandler()
          return true
        case 'ArrowDown':
        case 'ArrowRight':
          this.downHandler()
          return true
        case 'Enter':
          this.enterHandler()
          return true
      }
      return false
    },

    upHandler() {
      this.selection = ((this.selection + this.items.length) - 1) % this.items.length
    },

    downHandler() {
      this.selection = (this.selection + 1) % this.items.length
    },

    enterHandler() {
      this.onSelect(this.selection)
    },

    onSelect(index: number) {
      const item = this.items[index]
      if (item) {
        this.command(item)
      }
      hideAll()
    },
  },
}
</script>

<template>
  <div class="command-list">
    <div
      v-if="items.length"
      class="command-list-inner"
      @mouseenter="selection = -1"
    >
      <button
        v-for="(item, index) in items"
        :key="index.toString() + '-' + item.label"
        class="command-item"
        :class="{
          'command-item--selected': index === selection,
          'command-item--active': item.isActive({ editor }),
        }"
        @click="onSelect(index)"
      >
        <Icon :name="item.icon" :size="14" color="var(--fg-2)" />
        <span class="command-item-label">{{ item.label }}</span>
        <span class="spacer" />
        <Icon
          v-if="item.isActive({ editor })"
          name="check"
          :size="14"
          color="var(--ok)"
        />
      </button>
    </div>
    <div v-else class="command-empty">
      No result
    </div>
  </div>
</template>

<style scoped>
.command-list {
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 4px;
  box-shadow: 0 8px 24px -8px rgba(0, 0, 0, 0.5);
  min-width: 200px;
  max-height: 280px;
  overflow-y: auto;
  margin-left: 16px;
}

.command-list-inner {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.command-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 6px;
  font-size: 13px;
  color: var(--fg-1);
  background: transparent;
  border: 1px solid transparent;
  cursor: pointer;
  width: 100%;
  text-align: left;
}

.command-item:hover {
  background: var(--bg-2);
}

.command-item--selected {
  background: var(--bg-3);
  border-color: var(--line);
}

.command-item--active {
  font-weight: 600;
  font-style: italic;
}

.command-item-label {
  flex: 1;
}

.spacer {
  flex: 1;
}

.command-empty {
  padding: 8px 12px;
  font-size: 12px;
  color: var(--fg-3);
}
</style>

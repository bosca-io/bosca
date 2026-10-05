<script lang="ts">
import { hideAll } from 'tippy.js'
import type { MentionItem } from '~/utils/editor/mentionSuggestion'

const ENTITY_ICONS: Record<string, string> = {
  metadata: 'file',
  collection: 'folder',
  profile: 'user',
}

export default {
  props: {
    items: { type: Array as () => MentionItem[], required: true },
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
    iconFor(entityType: string) {
      return ENTITY_ICONS[entityType] || 'link'
    },

    onKeyDown({ event }: { event: KeyboardEvent }) {
      switch (event.key) {
        case 'ArrowUp':
          this.upHandler()
          return true
        case 'ArrowDown':
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
        this.command({ id: item.id, label: item.label, entityType: item.entityType })
      }
      hideAll()
    },
  },
}
</script>

<template>
  <div class="mention-list">
    <div
      v-if="items.length"
      class="mention-list-inner"
      @mouseenter="selection = -1"
    >
      <button
        v-for="(item, index) in items"
        :key="item.id"
        class="mention-item"
        :class="{ 'mention-item--selected': index === selection }"
        @click="onSelect(index)"
      >
        <Icon :name="iconFor(item.entityType)" :size="13" color="var(--fg-3)" />
        <span class="mention-item-label">{{ item.label }}</span>
        <span class="mention-item-type">{{ item.entityType }}</span>
      </button>
    </div>
    <div v-else class="mention-empty">
      No results found
    </div>
  </div>
</template>

<style scoped>
.mention-list {
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 4px;
  box-shadow: 0 8px 24px -8px rgba(0, 0, 0, 0.5);
  min-width: 200px;
  max-height: 240px;
  overflow-y: auto;
}

.mention-list-inner {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.mention-item {
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

.mention-item:hover {
  background: var(--bg-2);
}

.mention-item--selected {
  background: var(--bg-3);
  border-color: var(--line);
}

.mention-item-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mention-item-type {
  font-size: 10px;
  color: var(--fg-4);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  flex-shrink: 0;
}

.mention-empty {
  padding: 8px 12px;
  font-size: 12px;
  color: var(--fg-3);
}
</style>

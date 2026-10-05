<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import Sortable from 'sortablejs'
import { BLOCK_TYPES } from './library-utils'

const emit = defineEmits<{
  'add-block': [type: string]
}>()

const contentBlocks = BLOCK_TYPES.filter(b => b.group === 'content')
const layoutBlocks = BLOCK_TYPES.filter(b => b.group === 'layout')
const uiBlocks = BLOCK_TYPES.filter(b => b.group === 'ui')

const contentListRef = ref<HTMLElement | null>(null)
const layoutListRef = ref<HTMLElement | null>(null)
const uiListRef = ref<HTMLElement | null>(null)

const sortableInstances: Sortable[] = []

function initSortables() {
  const group = { name: 'library-blocks', pull: 'clone' as const, put: false as const }
  const opts: Sortable.Options = {
    group,
    sort: false,
    animation: 150,
    ghostClass: 'palette-ghost',
  }

  if (contentListRef.value) {
    sortableInstances.push(Sortable.create(contentListRef.value, opts))
  }
  if (layoutListRef.value) {
    sortableInstances.push(Sortable.create(layoutListRef.value, opts))
  }
  if (uiListRef.value) {
    sortableInstances.push(Sortable.create(uiListRef.value, opts))
  }
}

onMounted(initSortables)
onUnmounted(() => sortableInstances.forEach(s => s.destroy()))
</script>

<template>
  <div class="palette">
    <div class="palette-heading">Content</div>
    <div ref="contentListRef" class="palette-list">
      <div
        v-for="block in contentBlocks"
        :key="block.type"
        :data-block-type="block.type"
        class="palette-item"
        @click="emit('add-block', block.type)"
      >
        <Icon :name="block.icon" :size="13" color="var(--fg-3)" />
        <span>{{ block.label }}</span>
      </div>
    </div>

    <div class="palette-heading">Layout</div>
    <div ref="layoutListRef" class="palette-list">
      <div
        v-for="block in layoutBlocks"
        :key="block.type"
        :data-block-type="block.type"
        class="palette-item"
        @click="emit('add-block', block.type)"
      >
        <Icon :name="block.icon" :size="13" color="var(--fg-3)" />
        <span>{{ block.label }}</span>
      </div>
    </div>

    <div class="palette-heading">UI</div>
    <div ref="uiListRef" class="palette-list">
      <div
        v-for="block in uiBlocks"
        :key="block.type"
        :data-block-type="block.type"
        class="palette-item"
        @click="emit('add-block', block.type)"
      >
        <Icon :name="block.icon" :size="13" color="var(--fg-3)" />
        <span>{{ block.label }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.palette {
  overflow-y: auto;
  padding: 14px;
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

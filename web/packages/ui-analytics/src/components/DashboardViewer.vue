<script setup lang="ts">
import { computed } from 'vue'
import type { GridItem } from '../grid/types'
import { maxRow } from '../grid/layout'

const props = withDefaults(defineProps<{
  layout: GridItem[]
  columns?: number
  cellHeight?: number
  gap?: number
}>(), {
  columns: 12,
  cellHeight: 60,
  gap: 8,
})

const rows = computed(() => maxRow(props.layout))
</script>

<template>
  <div
    class="dashboard-viewer"
    :style="{
      '--columns': columns,
      '--cell-height': `${cellHeight}px`,
      '--grid-gap': `${gap}px`,
      '--rows': rows,
    }"
  >
    <div
      v-for="item in layout"
      :key="item.id"
      class="dashboard-viewer__item"
      :style="{
        gridColumn: `${item.x + 1} / span ${item.w}`,
        gridRow: `${item.y + 1} / span ${item.h}`,
      }"
    >
      <slot name="item" :item="item" />
    </div>
  </div>
</template>

<style scoped>
.dashboard-viewer {
  display: grid;
  grid-template-columns: repeat(var(--columns), 1fr);
  grid-auto-rows: var(--cell-height);
  gap: var(--grid-gap);
  min-height: calc(var(--rows) * var(--cell-height) + max(0, var(--rows) - 1) * var(--grid-gap));
}

.dashboard-viewer__item {
  background: var(--bg-1, #1a1c22);
  border: 1px solid var(--line, #2a2d35);
  border-radius: var(--r-sm, 8px);
  overflow: hidden;
  min-height: 0;
}

@media (max-width: 768px) {
  .dashboard-viewer {
    grid-template-columns: 1fr;
    grid-auto-rows: auto;
  }

  .dashboard-viewer__item {
    grid-column: 1 / -1 !important;
    grid-row: auto !important;
    min-height: 200px;
  }
}
</style>

<script setup lang="ts">
import { ref, computed, toRef } from 'vue'
import type { GridItem, GridConfig } from '../grid/types'
import { DEFAULT_CONFIG } from '../grid/types'
import { maxRow } from '../grid/layout'
import { useGridDrag } from '../composables/useGridDrag'
import { useGridResize } from '../composables/useGridResize'

const props = withDefaults(defineProps<{
  layout: GridItem[]
  columns?: number
  cellHeight?: number
  gap?: number
  lockedIds?: string[]
}>(), {
  columns: 12,
  cellHeight: 60,
  gap: 8,
  lockedIds: () => [],
})

const emit = defineEmits<{
  'update:layout': [layout: GridItem[]]
}>()

const containerEl = ref<HTMLElement | null>(null)

const config = computed<GridConfig>(() => ({
  columns: props.columns ?? DEFAULT_CONFIG.columns,
  cellHeight: props.cellHeight ?? DEFAULT_CONFIG.cellHeight,
  gap: props.gap ?? DEFAULT_CONFIG.gap,
}))

const layoutRef = toRef(props, 'layout')
const lockedSet = computed(() => new Set(props.lockedIds))

function commitLayout(newLayout: GridItem[]) {
  emit('update:layout', newLayout)
}

const {
  dragging,
  previewLayout: dragPreview,
  startDrag,
  onPointerMove: onDragMove,
  endDrag,
} = useGridDrag(layoutRef, config, containerEl, commitLayout, lockedSet)

const {
  resizing,
  previewLayout: resizePreview,
  startResize,
  onPointerMove: onResizeMove,
  endResize,
} = useGridResize(layoutRef, config, containerEl, commitLayout, lockedSet)

const activeLayout = computed(() => {
  return dragPreview.value ?? resizePreview.value ?? props.layout
})

const rows = computed(() => Math.max(maxRow(activeLayout.value), 4))

function onPointerMove(e: PointerEvent) {
  if (dragging.value) onDragMove(e)
  if (resizing.value) onResizeMove(e)
}

function onPointerUp() {
  if (dragging.value) endDrag()
  if (resizing.value) endResize()
}

function itemStyle(item: GridItem) {
  if (dragging.value?.itemId === item.id) {
    const d = dragging.value
    return {
      position: 'absolute' as const,
      left: `${d.startLeft + d.translateX}px`,
      top: `${d.startTop + d.translateY}px`,
      width: `${d.startWidth}px`,
      height: `${d.startHeight}px`,
      zIndex: 100,
      opacity: '0.85',
      cursor: 'grabbing',
    }
  }
  if (resizing.value?.itemId === item.id) {
    const r = resizing.value
    return {
      '--gc': `${item.x + 1} / span ${r.w}`,
      '--gr': `${item.y + 1} / span ${r.h}`,
    }
  }
  return {
    '--gc': `${item.x + 1} / span ${item.w}`,
    '--gr': `${item.y + 1} / span ${item.h}`,
  }
}

function placeholderStyle() {
  if (!dragging.value) return null
  return {
    '--gc': `${dragging.value.col + 1} / span ${props.layout.find(i => i.id === dragging.value!.itemId)?.w ?? 1}`,
    '--gr': `${dragging.value.row + 1} / span ${props.layout.find(i => i.id === dragging.value!.itemId)?.h ?? 1}`,
  }
}
</script>

<template>
  <div
    ref="containerEl"
    class="dashboard-grid"
    :style="{
      '--columns': config.columns,
      '--cell-height': `${config.cellHeight}px`,
      '--grid-gap': `${config.gap}px`,
      '--rows': rows,
    }"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerUp"
  >
    <!-- Placeholder showing drop target -->
    <div
      v-if="dragging && placeholderStyle()"
      class="dashboard-grid__placeholder"
      :style="placeholderStyle()!"
    />

    <!-- Grid items -->
    <div
      v-for="item in activeLayout"
      :key="item.id"
      class="dashboard-grid__item"
      :class="{ 'dashboard-grid__item--dragging': dragging?.itemId === item.id }"
      :style="itemStyle(item)"
    >
      <div
        v-if="!lockedIds.includes(item.id)"
        class="dashboard-grid__drag-handle"
        @pointerdown.prevent="(e) => startDrag(e, item.id)"
      >
        <slot name="handle" :item="item">
          <span class="dashboard-grid__grip" />
        </slot>
      </div>

      <div class="dashboard-grid__content">
        <slot name="item" :item="item" />
      </div>

      <div
        v-if="!lockedIds.includes(item.id)"
        class="dashboard-grid__resize-handle"
        @pointerdown.prevent="(e) => startResize(e, item.id)"
      />
    </div>
  </div>
</template>

<style scoped>
.dashboard-grid {
  display: grid;
  grid-template-columns: repeat(var(--columns), 1fr);
  grid-auto-rows: var(--cell-height);
  gap: var(--grid-gap);
  position: relative;
  min-height: calc(var(--rows) * var(--cell-height) + (var(--rows) - 1) * var(--grid-gap));
  user-select: none;
}

.dashboard-grid__item {
  grid-column: var(--gc);
  grid-row: var(--gr);
  position: relative;
  background: var(--bg-1, #1a1c22);
  border: 1px solid var(--line, #2a2d35);
  border-radius: var(--r-sm, 8px);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  transition: box-shadow 0.15s;
}

.dashboard-grid__item:hover {
  box-shadow: 0 0 0 1px color-mix(in oklch, var(--brand-2, #5ec5ff) 30%, transparent);
}

.dashboard-grid__item--dragging {
  box-shadow: 0 8px 32px -8px rgba(0, 0, 0, 0.5);
  pointer-events: none;
}

.dashboard-grid__drag-handle {
  height: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: grab;
  flex-shrink: 0;
  background: var(--bg-2, #22252b);
  touch-action: none;
  transition: background 0.15s;
}

.dashboard-grid__drag-handle:hover {
  background: color-mix(in oklch, var(--brand-2, #5ec5ff) 10%, var(--bg-2, #22252b));
}

.dashboard-grid__drag-handle:active {
  cursor: grabbing;
}

.dashboard-grid__grip {
  width: 20px;
  height: 2px;
  border-radius: 1px;
  background: var(--fg-4, #3a3d45);
}

.dashboard-grid__content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.dashboard-grid__resize-handle {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 16px;
  height: 16px;
  cursor: nwse-resize;
  touch-action: none;
  background:
    linear-gradient(135deg, transparent 50%, var(--fg-4, #3a3d45) 50%, var(--fg-4, #3a3d45) calc(50% + 1px), transparent calc(50% + 1px)) no-repeat bottom right / 12px 12px,
    linear-gradient(135deg, transparent 50%, var(--fg-4, #3a3d45) 50%, var(--fg-4, #3a3d45) calc(50% + 1px), transparent calc(50% + 1px)) no-repeat bottom right / 8px 8px,
    linear-gradient(135deg, transparent 50%, var(--fg-4, #3a3d45) 50%, var(--fg-4, #3a3d45) calc(50% + 1px), transparent calc(50% + 1px)) no-repeat bottom right / 4px 4px;
  transition: opacity 0.15s;
}

.dashboard-grid__resize-handle:hover {
  opacity: 1;
  background:
    linear-gradient(135deg, transparent 50%, var(--brand-2, #5ec5ff) 50%, var(--brand-2, #5ec5ff) calc(50% + 1px), transparent calc(50% + 1px)) no-repeat bottom right / 12px 12px,
    linear-gradient(135deg, transparent 50%, var(--brand-2, #5ec5ff) 50%, var(--brand-2, #5ec5ff) calc(50% + 1px), transparent calc(50% + 1px)) no-repeat bottom right / 8px 8px,
    linear-gradient(135deg, transparent 50%, var(--brand-2, #5ec5ff) 50%, var(--brand-2, #5ec5ff) calc(50% + 1px), transparent calc(50% + 1px)) no-repeat bottom right / 4px 4px;
}

.dashboard-grid__placeholder {
  grid-column: var(--gc);
  grid-row: var(--gr);
  background: color-mix(in oklch, var(--brand-2, #5ec5ff) 10%, transparent);
  border: 2px dashed color-mix(in oklch, var(--brand-2, #5ec5ff) 40%, transparent);
  border-radius: var(--r-sm, 8px);
  pointer-events: none;
}
</style>

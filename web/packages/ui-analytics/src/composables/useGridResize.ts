import { ref, type Ref } from 'vue'
import type { GridItem, GridConfig } from '../grid/types'
import { clamp, resolveCollisions } from '../grid/layout'

export interface ResizeState {
  itemId: string
  w: number
  h: number
}

export function useGridResize(
  layout: Ref<GridItem[]>,
  config: Ref<GridConfig>,
  containerEl: Ref<HTMLElement | null>,
  onCommit: (newLayout: GridItem[]) => void,
  lockedIds?: Ref<Set<string>>,
) {
  const resizing = ref<ResizeState | null>(null)
  const previewLayout = ref<GridItem[] | null>(null)

  let startPointerX = 0
  let startPointerY = 0
  let startW = 0
  let startH = 0
  let cellWidth = 0
  let rafId = 0
  let lastW = -1
  let lastH = -1

  function startResize(e: PointerEvent, itemId: string) {
    const el = containerEl.value
    if (!el) return

    const item = layout.value.find(i => i.id === itemId)
    if (!item) return

    const rect = el.getBoundingClientRect()
    cellWidth = (rect.width - (config.value.columns - 1) * config.value.gap) / config.value.columns

    startPointerX = e.clientX
    startPointerY = e.clientY
    startW = item.w
    startH = item.h
    lastW = item.w
    lastH = item.h

    resizing.value = { itemId, w: item.w, h: item.h }
    previewLayout.value = [...layout.value]

    containerEl.value?.setPointerCapture(e.pointerId)
  }

  function onPointerMove(e: PointerEvent) {
    if (!resizing.value) return

    const dx = e.clientX - startPointerX
    const dy = e.clientY - startPointerY

    if (rafId) return
    rafId = requestAnimationFrame(() => {
      rafId = 0
      if (!resizing.value) return

      const item = layout.value.find(i => i.id === resizing.value!.itemId)
      if (!item) return

      const step = cellWidth + config.value.gap
      const newW = clamp(startW + Math.round(dx / step), 1, config.value.columns - item.x)
      const newH = clamp(startH + Math.round(dy / (config.value.cellHeight + config.value.gap)), 1, 50)

      if (newW === lastW && newH === lastH) return
      lastW = newW
      lastH = newH

      resizing.value.w = newW
      resizing.value.h = newH

      const resized: GridItem = { ...item, w: newW, h: newH }
      previewLayout.value = resolveCollisions(layout.value, resized, config.value.columns, lockedIds?.value)
    })
  }

  function endResize() {
    if (!resizing.value) return
    if (rafId) {
      cancelAnimationFrame(rafId)
      rafId = 0
    }
    if (previewLayout.value) {
      onCommit(previewLayout.value)
    }
    resizing.value = null
    previewLayout.value = null
  }

  return {
    resizing,
    previewLayout,
    startResize,
    onPointerMove,
    endResize,
  }
}

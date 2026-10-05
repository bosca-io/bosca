import { ref, type Ref } from 'vue'
import type { GridItem, GridConfig } from '../grid/types'
import { clamp, resolveCollisions } from '../grid/layout'

export interface DragState {
  itemId: string
  startLeft: number
  startTop: number
  startWidth: number
  startHeight: number
  translateX: number
  translateY: number
  col: number
  row: number
}

export function useGridDrag(
  layout: Ref<GridItem[]>,
  config: Ref<GridConfig>,
  containerEl: Ref<HTMLElement | null>,
  onCommit: (newLayout: GridItem[]) => void,
  lockedIds?: Ref<Set<string>>,
) {
  const dragging = ref<DragState | null>(null)
  const previewLayout = ref<GridItem[] | null>(null)

  let startPointerX = 0
  let startPointerY = 0
  let itemStartX = 0
  let itemStartY = 0
  let containerRect: DOMRect | null = null
  let cellWidth = 0
  let rafId = 0
  let lastCol = -1
  let lastRow = -1

  function startDrag(e: PointerEvent, itemId: string) {
    const el = containerEl.value
    if (!el) return

    const item = layout.value.find(i => i.id === itemId)
    if (!item) return

    containerRect = el.getBoundingClientRect()
    cellWidth = (containerRect.width - (config.value.columns - 1) * config.value.gap) / config.value.columns

    const itemEl = (e.currentTarget as HTMLElement).closest('.dashboard-grid__item') as HTMLElement | null
    const itemRect = itemEl?.getBoundingClientRect()

    startPointerX = e.clientX
    startPointerY = e.clientY
    itemStartX = item.x
    itemStartY = item.y
    lastCol = item.x
    lastRow = item.y

    dragging.value = {
      itemId,
      startLeft: itemRect ? itemRect.left - containerRect.left : 0,
      startTop: itemRect ? itemRect.top - containerRect.top : 0,
      startWidth: itemRect?.width ?? 0,
      startHeight: itemRect?.height ?? 0,
      translateX: 0,
      translateY: 0,
      col: item.x,
      row: item.y,
    }
    previewLayout.value = [...layout.value]

    containerEl.value?.setPointerCapture(e.pointerId)
  }

  function onPointerMove(e: PointerEvent) {
    if (!dragging.value) return

    const dx = e.clientX - startPointerX
    const dy = e.clientY - startPointerY

    dragging.value.translateX = dx
    dragging.value.translateY = dy

    if (rafId) return
    rafId = requestAnimationFrame(() => {
      rafId = 0
      if (!dragging.value || !containerRect) return

      const step = cellWidth + config.value.gap
      const newCol = clamp(
        Math.round(itemStartX + dx / step),
        0,
        config.value.columns - (layout.value.find(i => i.id === dragging.value!.itemId)?.w ?? 1),
      )
      const newRow = clamp(
        Math.round(itemStartY + dy / (config.value.cellHeight + config.value.gap)),
        0,
        999,
      )

      if (newCol === lastCol && newRow === lastRow) return
      lastCol = newCol
      lastRow = newRow

      dragging.value.col = newCol
      dragging.value.row = newRow

      const item = layout.value.find(i => i.id === dragging.value!.itemId)!
      const moved: GridItem = { ...item, x: newCol, y: newRow }
      previewLayout.value = resolveCollisions(layout.value, moved, config.value.columns, lockedIds?.value)
    })
  }

  function endDrag() {
    if (!dragging.value) return
    if (rafId) {
      cancelAnimationFrame(rafId)
      rafId = 0
    }
    if (previewLayout.value) {
      onCommit(previewLayout.value)
    }
    dragging.value = null
    previewLayout.value = null
  }

  return {
    dragging,
    previewLayout,
    startDrag,
    onPointerMove,
    endDrag,
  }
}

import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'
import { useGridDrag } from './useGridDrag'
import type { GridItem, GridConfig } from '../grid/types'

function makeLayout(): GridItem[] {
  return [
    { id: 'a', x: 0, y: 0, w: 4, h: 2 },
    { id: 'b', x: 4, y: 0, w: 4, h: 2 },
  ]
}

function makeConfig(): GridConfig {
  return { columns: 12, cellHeight: 60, gap: 8 }
}

function makeContainerEl() {
  const el = document.createElement('div')
  el.getBoundingClientRect = () => ({
    x: 0, y: 0, width: 1200, height: 600,
    top: 0, right: 1200, bottom: 600, left: 0,
    toJSON: () => ({}),
  })
  el.setPointerCapture = vi.fn()
  return el
}

function makeDragEvent(overrides: Partial<PointerEvent> = {}): PointerEvent {
  return {
    clientX: 0,
    clientY: 0,
    pointerId: 1,
    currentTarget: document.createElement('div'),
    ...overrides,
  } as unknown as PointerEvent
}

describe('useGridDrag', () => {
  it('initializes with null state', () => {
    const { dragging, previewLayout } = useGridDrag(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(null),
      vi.fn(),
    )
    expect(dragging.value).toBeNull()
    expect(previewLayout.value).toBeNull()
  })

  it('does nothing when container is null', () => {
    const { startDrag, dragging } = useGridDrag(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(null),
      vi.fn(),
    )
    startDrag(makeDragEvent(), 'a')
    expect(dragging.value).toBeNull()
  })

  it('does nothing for unknown item id', () => {
    const el = makeContainerEl()
    const { startDrag, dragging } = useGridDrag(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(el),
      vi.fn(),
    )
    const handle = document.createElement('div')
    const gridItem = document.createElement('div')
    gridItem.classList.add('dashboard-grid__item')
    gridItem.appendChild(handle)
    startDrag(makeDragEvent({ currentTarget: handle } as any), 'unknown')
    expect(dragging.value).toBeNull()
  })

  it('sets drag state on startDrag', () => {
    const el = makeContainerEl()
    const handle = document.createElement('div')
    const gridItem = document.createElement('div')
    gridItem.classList.add('dashboard-grid__item')
    gridItem.appendChild(handle)

    const { startDrag, dragging } = useGridDrag(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(el),
      vi.fn(),
    )
    startDrag(makeDragEvent({ currentTarget: handle, clientX: 100, clientY: 50 } as any), 'a')
    expect(dragging.value).not.toBeNull()
    expect(dragging.value!.itemId).toBe('a')
  })

  it('endDrag resets state', () => {
    const el = makeContainerEl()
    const commit = vi.fn()
    const handle = document.createElement('div')
    const gridItem = document.createElement('div')
    gridItem.classList.add('dashboard-grid__item')
    gridItem.appendChild(handle)

    const { startDrag, endDrag, dragging, previewLayout } = useGridDrag(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(el),
      commit,
    )
    startDrag(makeDragEvent({ currentTarget: handle } as any), 'a')
    endDrag()
    expect(dragging.value).toBeNull()
    expect(previewLayout.value).toBeNull()
    expect(commit).toHaveBeenCalled()
  })

  it('endDrag is no-op when not dragging', () => {
    const commit = vi.fn()
    const { endDrag } = useGridDrag(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(null),
      commit,
    )
    endDrag()
    expect(commit).not.toHaveBeenCalled()
  })

  it('onPointerMove is no-op when not dragging', () => {
    const { onPointerMove } = useGridDrag(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(null),
      vi.fn(),
    )
    onPointerMove(makeDragEvent({ clientX: 200, clientY: 200 }))
  })

  it('updates translate on pointer move', () => {
    const el = makeContainerEl()
    const handle = document.createElement('div')
    const gridItem = document.createElement('div')
    gridItem.classList.add('dashboard-grid__item')
    gridItem.appendChild(handle)

    const { startDrag, onPointerMove, dragging } = useGridDrag(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(el),
      vi.fn(),
    )
    startDrag(makeDragEvent({ currentTarget: handle, clientX: 100, clientY: 50 } as any), 'a')
    onPointerMove(makeDragEvent({ clientX: 200, clientY: 100 }))
    expect(dragging.value!.translateX).toBe(100)
    expect(dragging.value!.translateY).toBe(50)
  })

  it('updates grid position via requestAnimationFrame', () => {
    const el = makeContainerEl()
    const handle = document.createElement('div')
    const gridItem = document.createElement('div')
    gridItem.classList.add('dashboard-grid__item')
    gridItem.appendChild(handle)

    const { startDrag, onPointerMove, dragging, previewLayout } = useGridDrag(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(el),
      vi.fn(),
    )
    startDrag(makeDragEvent({ currentTarget: handle, clientX: 0, clientY: 0 } as any), 'a')

    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => { cb(0); return 0 })
    const step = (1200 - 11 * 8) / 12 + 8
    onPointerMove(makeDragEvent({ clientX: step * 3, clientY: 68 * 2 }))
    vi.unstubAllGlobals()

    expect(dragging.value!.col).toBeGreaterThan(0)
    expect(previewLayout.value).not.toBeNull()
  })

  it('cancels pending raf on endDrag', () => {
    const el = makeContainerEl()
    const handle = document.createElement('div')
    const gridItem = document.createElement('div')
    gridItem.classList.add('dashboard-grid__item')
    gridItem.appendChild(handle)
    const cancelSpy = vi.fn()
    vi.stubGlobal('cancelAnimationFrame', cancelSpy)
    vi.stubGlobal('requestAnimationFrame', () => 42)

    const commit = vi.fn()
    const { startDrag, onPointerMove, endDrag } = useGridDrag(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(el),
      commit,
    )
    startDrag(makeDragEvent({ currentTarget: handle, clientX: 0, clientY: 0 } as any), 'a')
    onPointerMove(makeDragEvent({ clientX: 200, clientY: 200 }))
    endDrag()

    vi.unstubAllGlobals()

    expect(cancelSpy).toHaveBeenCalledWith(42)
    expect(commit).toHaveBeenCalled()
  })

  it('does not update preview when position unchanged', () => {
    const el = makeContainerEl()
    const handle = document.createElement('div')
    const gridItem = document.createElement('div')
    gridItem.classList.add('dashboard-grid__item')
    gridItem.appendChild(handle)

    const { startDrag, onPointerMove, dragging } = useGridDrag(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(el),
      vi.fn(),
    )
    startDrag(makeDragEvent({ currentTarget: handle, clientX: 0, clientY: 0 } as any), 'a')

    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => { cb(0); return 0 })
    onPointerMove(makeDragEvent({ clientX: 0, clientY: 0 }))
    vi.unstubAllGlobals()

    expect(dragging.value!.col).toBe(0)
    expect(dragging.value!.row).toBe(0)
  })
})

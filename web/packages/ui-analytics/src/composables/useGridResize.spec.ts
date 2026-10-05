import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'
import { useGridResize } from './useGridResize'
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

function makePointerEvent(overrides: Partial<PointerEvent> = {}): PointerEvent {
  return {
    clientX: 0,
    clientY: 0,
    pointerId: 1,
    ...overrides,
  } as unknown as PointerEvent
}

describe('useGridResize', () => {
  it('initializes with null state', () => {
    const { resizing, previewLayout } = useGridResize(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(null),
      vi.fn(),
    )
    expect(resizing.value).toBeNull()
    expect(previewLayout.value).toBeNull()
  })

  it('does nothing when container is null', () => {
    const { startResize, resizing } = useGridResize(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(null),
      vi.fn(),
    )
    startResize(makePointerEvent(), 'a')
    expect(resizing.value).toBeNull()
  })

  it('does nothing for unknown item id', () => {
    const el = makeContainerEl()
    const { startResize, resizing } = useGridResize(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(el),
      vi.fn(),
    )
    startResize(makePointerEvent(), 'unknown')
    expect(resizing.value).toBeNull()
  })

  it('sets resize state on startResize', () => {
    const el = makeContainerEl()
    const { startResize, resizing } = useGridResize(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(el),
      vi.fn(),
    )
    startResize(makePointerEvent({ clientX: 100, clientY: 50 }), 'a')
    expect(resizing.value).not.toBeNull()
    expect(resizing.value!.itemId).toBe('a')
    expect(resizing.value!.w).toBe(4)
    expect(resizing.value!.h).toBe(2)
  })

  it('endResize commits and resets state', () => {
    const el = makeContainerEl()
    const commit = vi.fn()
    const { startResize, endResize, resizing, previewLayout } = useGridResize(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(el),
      commit,
    )
    startResize(makePointerEvent(), 'a')
    endResize()
    expect(resizing.value).toBeNull()
    expect(previewLayout.value).toBeNull()
    expect(commit).toHaveBeenCalled()
  })

  it('endResize is no-op when not resizing', () => {
    const commit = vi.fn()
    const { endResize } = useGridResize(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(null),
      commit,
    )
    endResize()
    expect(commit).not.toHaveBeenCalled()
  })

  it('onPointerMove is no-op when not resizing', () => {
    const { onPointerMove } = useGridResize(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(null),
      vi.fn(),
    )
    onPointerMove(makePointerEvent({ clientX: 200, clientY: 200 }))
  })

  it('updates resize dimensions via requestAnimationFrame', () => {
    const el = makeContainerEl()
    const layout = ref(makeLayout())
    const config = ref(makeConfig())
    const { startResize, onPointerMove, resizing, previewLayout } = useGridResize(
      layout,
      config,
      ref(el),
      vi.fn(),
    )
    startResize(makePointerEvent({ clientX: 100, clientY: 50 }), 'a')

    const step = (1200 - 11 * 8) / 12 + 8
    onPointerMove(makePointerEvent({ clientX: 100 + step * 2, clientY: 50 + 68 * 2 }))

    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => { cb(0); return 0 })
    onPointerMove(makePointerEvent({ clientX: 100 + step * 2, clientY: 50 + 68 * 2 }))
    vi.unstubAllGlobals()

    expect(resizing.value!.w).toBeGreaterThanOrEqual(1)
    expect(previewLayout.value).not.toBeNull()
  })

  it('does not update when item not found during move', () => {
    const el = makeContainerEl()
    const layout = ref(makeLayout())
    const { startResize, onPointerMove, resizing } = useGridResize(
      layout,
      ref(makeConfig()),
      ref(el),
      vi.fn(),
    )
    startResize(makePointerEvent({ clientX: 0, clientY: 0 }), 'a')

    layout.value = layout.value.filter(i => i.id !== 'a')

    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => { cb(0); return 0 })
    onPointerMove(makePointerEvent({ clientX: 200, clientY: 200 }))
    vi.unstubAllGlobals()

    expect(resizing.value!.w).toBe(4)
  })

  it('cancels pending raf on endResize', () => {
    const el = makeContainerEl()
    const cancelSpy = vi.fn()
    vi.stubGlobal('cancelAnimationFrame', cancelSpy)
    vi.stubGlobal('requestAnimationFrame', () => 42)

    const commit = vi.fn()
    const { startResize, onPointerMove, endResize } = useGridResize(
      ref(makeLayout()),
      ref(makeConfig()),
      ref(el),
      commit,
    )
    startResize(makePointerEvent(), 'a')
    onPointerMove(makePointerEvent({ clientX: 200, clientY: 200 }))
    endResize()

    vi.unstubAllGlobals()

    expect(cancelSpy).toHaveBeenCalledWith(42)
    expect(commit).toHaveBeenCalled()
  })
})

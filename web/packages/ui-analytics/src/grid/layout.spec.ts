import { describe, it, expect } from 'vitest'
import { collides, compact, resolveCollisions, clamp, maxRow } from './layout'
import type { GridItem } from './types'

function item(id: string, x: number, y: number, w: number, h: number): GridItem {
  return { id, x, y, w, h }
}

describe('collides', () => {
  it('returns false for same item', () => {
    const a = item('a', 0, 0, 2, 2)
    expect(collides(a, a)).toBe(false)
  })

  it('detects overlapping items', () => {
    expect(collides(item('a', 0, 0, 2, 2), item('b', 1, 1, 2, 2))).toBe(true)
  })

  it('returns false when items are side by side horizontally', () => {
    expect(collides(item('a', 0, 0, 2, 2), item('b', 2, 0, 2, 2))).toBe(false)
  })

  it('returns false when items are stacked vertically', () => {
    expect(collides(item('a', 0, 0, 2, 2), item('b', 0, 2, 2, 2))).toBe(false)
  })

  it('returns false for non-overlapping items', () => {
    expect(collides(item('a', 0, 0, 1, 1), item('b', 5, 5, 1, 1))).toBe(false)
  })

  it('detects single-cell overlap', () => {
    expect(collides(item('a', 0, 0, 3, 3), item('b', 2, 2, 1, 1))).toBe(true)
  })
})

describe('compact', () => {
  it('returns empty array for empty input', () => {
    expect(compact([], 12)).toEqual([])
  })

  it('moves a single item to y=0', () => {
    const result = compact([item('a', 0, 5, 2, 2)], 12)
    expect(result[0].y).toBe(0)
  })

  it('does not overlap items after compacting', () => {
    const items = [
      item('a', 0, 0, 4, 2),
      item('b', 0, 5, 4, 2),
    ]
    const result = compact(items, 12)
    expect(result[1].y).toBe(2)
  })

  it('clamps x to valid column range', () => {
    const result = compact([item('a', 15, 0, 4, 1)], 12)
    expect(result[0].x).toBe(8)
  })

  it('preserves item ids', () => {
    const items = [item('a', 0, 0, 1, 1), item('b', 1, 0, 1, 1)]
    const result = compact(items, 12)
    expect(result.map(i => i.id).sort()).toEqual(['a', 'b'])
  })
})

describe('resolveCollisions', () => {
  it('pushes unlocked items down when they collide with moved item', () => {
    const layout = [
      item('a', 0, 0, 4, 2),
      item('b', 0, 2, 4, 2),
    ]
    const moved = item('a', 0, 1, 4, 2)
    const result = resolveCollisions(layout, moved, 12)

    const b = result.find(i => i.id === 'b')!
    expect(b.y).toBeGreaterThanOrEqual(3)
  })

  it('does not move locked items', () => {
    const layout = [
      item('a', 0, 0, 4, 2),
      item('b', 0, 1, 4, 2),
    ]
    const moved = item('a', 0, 0, 4, 2)
    const locked = new Set(['b'])
    const result = resolveCollisions(layout, moved, 12, locked)

    const b = result.find(i => i.id === 'b')!
    expect(b.y).toBe(1)
  })

  it('clamps x to valid range', () => {
    const layout = [item('a', 15, 0, 4, 1)]
    const moved = item('a', 15, 0, 4, 1)
    const result = resolveCollisions(layout, moved, 12)
    expect(result[0].x).toBe(8)
  })
})

describe('clamp', () => {
  it('returns value when within range', () => {
    expect(clamp(5, 0, 10)).toBe(5)
  })

  it('returns min when value is below range', () => {
    expect(clamp(-5, 0, 10)).toBe(0)
  })

  it('returns max when value is above range', () => {
    expect(clamp(15, 0, 10)).toBe(10)
  })

  it('handles min equal to max', () => {
    expect(clamp(5, 3, 3)).toBe(3)
  })
})

describe('maxRow', () => {
  it('returns 0 for empty array', () => {
    expect(maxRow([])).toBe(0)
  })

  it('returns bottom edge of single item', () => {
    expect(maxRow([item('a', 0, 3, 2, 2)])).toBe(5)
  })

  it('returns highest bottom edge', () => {
    const items = [
      item('a', 0, 0, 2, 2),
      item('b', 0, 5, 2, 3),
    ]
    expect(maxRow(items)).toBe(8)
  })
})

import type { GridItem } from './types'

export function collides(a: GridItem, b: GridItem): boolean {
  if (a.id === b.id) return false
  return !(
    a.x + a.w <= b.x ||
    b.x + b.w <= a.x ||
    a.y + a.h <= b.y ||
    b.y + b.h <= a.y
  )
}

export function compact(items: GridItem[], columns: number): GridItem[] {
  const sorted = [...items].sort((a, b) => a.y - b.y || a.x - b.x)
  const placed: GridItem[] = []

  for (const item of sorted) {
    const candidate = { ...item }
    candidate.y = 0

    // Move up as far as possible without collision
    while (candidate.y < item.y && !placed.some(p => collides(candidate, p))) {
      // Try this position - if valid, try one higher
      const test = { ...candidate, y: candidate.y }
      if (placed.some(p => collides(test, p))) break
      candidate.y++
    }
    // Reset and find first valid row from top
    candidate.y = 0
    while (placed.some(p => collides(candidate, p))) {
      candidate.y++
    }

    // Clamp x
    candidate.x = Math.max(0, Math.min(candidate.x, columns - candidate.w))

    placed.push(candidate)
  }
  return placed
}

export function resolveCollisions(
  layout: GridItem[],
  moved: GridItem,
  columns: number,
  lockedIds: Set<string> = new Set(),
): GridItem[] {
  const locked = layout.filter(item => item.id !== moved.id && lockedIds.has(item.id))
  const unlocked = layout.filter(item => item.id !== moved.id && !lockedIds.has(item.id))
    .sort((a, b) => a.y - b.y || a.x - b.x)

  const result: GridItem[] = [...locked, moved]

  for (const item of unlocked) {
    let candidate = { ...item }
    while (result.some(p => collides(candidate, p))) {
      candidate = { ...candidate, y: candidate.y + 1 }
    }
    result.push(candidate)
  }

  return result.map(item => ({
    ...item,
    x: Math.max(0, Math.min(item.x, columns - item.w)),
  }))
}

export function clamp(val: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, val))
}

export function maxRow(items: GridItem[]): number {
  if (items.length === 0) return 0
  return Math.max(...items.map(i => i.y + i.h))
}

import { describe, it, expect } from 'vitest'
import { computePipelineLayout, type LayoutEdge, type LayoutNode } from './pipelineLayout'

// Uniform 100×50 nodes with zero origin and 100/50 gaps make the geometry exact: a column's x is
// `column * (100 + 100)` and the tallest column drives vertical centering.
const opts = { columnGap: 100, rowGap: 50, originX: 0, originY: 0 }
const n = (id: string): LayoutNode => ({ id, width: 100, height: 50 })

describe('computePipelineLayout', () => {
  it('returns an empty map for no nodes', () => {
    expect(computePipelineLayout([], [], opts).size).toBe(0)
  })

  it('places a single node at the origin', () => {
    expect(computePipelineLayout([n('a')], [], opts).get('a')).toEqual({ x: 0, y: 0 })
  })

  it('lays a linear chain out left to right, one column per hop', () => {
    const edges: LayoutEdge[] = [{ source: 'a', target: 'b' }, { source: 'b', target: 'c' }]
    const pos = computePipelineLayout([n('a'), n('b'), n('c')], edges, opts)
    expect(pos.get('a')!.x).toBe(0)
    expect(pos.get('b')!.x).toBe(200)
    expect(pos.get('c')!.x).toBe(400)
  })

  it('puts sibling branches in the same column on distinct rows', () => {
    const edges: LayoutEdge[] = [{ source: 'a', target: 'b' }, { source: 'a', target: 'c' }]
    const pos = computePipelineLayout([n('a'), n('b'), n('c')], edges, opts)
    expect(pos.get('b')!.x).toBe(200)
    expect(pos.get('c')!.x).toBe(200)
    expect(pos.get('b')!.y).not.toBe(pos.get('c')!.y)
  })

  it('places a diamond merge in the column after both branch tips', () => {
    const edges: LayoutEdge[] = [
      { source: 'a', target: 'b' },
      { source: 'a', target: 'c' },
      { source: 'b', target: 'd' },
      { source: 'c', target: 'd' },
    ]
    const pos = computePipelineLayout([n('a'), n('b'), n('c'), n('d')], edges, opts)
    expect(pos.get('a')!.x).toBe(0)
    expect(pos.get('b')!.x).toBe(200)
    expect(pos.get('c')!.x).toBe(200)
    expect(pos.get('d')!.x).toBe(400)
  })

  it('ignores edges that reference nodes outside the set (e.g. framed nodes)', () => {
    const pos = computePipelineLayout([n('a'), n('b')], [
      { source: 'a', target: 'b' },
      { source: 'a', target: 'ghost' },
      { source: 'ghost', target: 'b' },
    ], opts)
    expect(pos.get('a')!.x).toBe(0)
    expect(pos.get('b')!.x).toBe(200)
  })

  it('terminates and places every node even on a cycle', () => {
    const pos = computePipelineLayout([n('a'), n('b')], [
      { source: 'a', target: 'b' },
      { source: 'b', target: 'a' },
    ], opts)
    expect(pos.size).toBe(2)
  })
})

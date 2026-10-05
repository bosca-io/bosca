import { describe, it, expect } from 'vitest'
import { remapPastedGraph, type ClipboardEdge, type ClipboardGraph, type ClipboardGroup, type ClipboardNode, type RemapOptions } from './pipelineClipboard'

// Deterministic id minting: counters make the remapped ids exact (N1, N2, … / G1, … / E1, …) so a
// test can assert the full output rather than just its shape.
function makeIds() {
  let nodeN = 0
  let groupN = 0
  let edgeN = 0
  return {
    nextNodeId: () => `N${++nodeN}`,
    nextGroupId: () => `G${++groupN}`,
    nextEdgeId: () => `E${++edgeN}`,
  }
}
function opts(overrides: Partial<RemapOptions> = {}): RemapOptions {
  return { existingEndpoints: new Map<string, string>(), offset: 0, ...makeIds(), ...overrides }
}

const node = (id: string, type = 'jsonata', extra: Record<string, unknown> = {}): ClipboardNode => ({ type, id, position: { x: 10, y: 20 }, ...extra })
const edge = (id: string, source: string, target: string, extra: Partial<ClipboardEdge> = {}): ClipboardEdge => ({ id, source, target, ...extra })
const group = (id: string, nodeIds: string[] = []): ClipboardGroup => ({ id, label: 'G', x: 5, y: 6, width: 100, height: 80, collapsed: false, color: null, nodeIds })
const graph = (nodes: ClipboardNode[] = [], edges: ClipboardEdge[] = [], groups: ClipboardGroup[] = []): ClipboardGraph => ({ nodes, edges, groups })

describe('remapPastedGraph', () => {
  it('returns empty for an empty graph', () => {
    const r = remapPastedGraph(graph(), opts())
    expect(r).toEqual({ nodes: [], groups: [], edges: [] })
  })

  it('mints a fresh id for each node, preserving type and settings', () => {
    const r = remapPastedGraph(graph([node('a', 'jsonata', { expression: '$.x' }), node('b', 'condition')]), opts())
    expect(r.nodes.map(n => n.id)).toEqual(['N1', 'N2'])
    expect(r.nodes[0]).toMatchObject({ type: 'jsonata', expression: '$.x' })
    expect(r.nodes[1]!.type).toBe('condition')
  })

  it('applies the offset to node positions when merging', () => {
    const r = remapPastedGraph(graph([node('a')]), opts({ offset: 48 }))
    expect(r.nodes[0]!.position).toEqual({ x: 58, y: 68 })
  })

  it('keeps source coordinates when the offset is zero (empty canvas)', () => {
    const r = remapPastedGraph(graph([node('a')]), opts({ offset: 0 }))
    expect(r.nodes[0]!.position).toEqual({ x: 10, y: 20 })
  })

  it('defaults a missing position to the origin before offsetting', () => {
    const r = remapPastedGraph(graph([{ type: 'jsonata', id: 'a' }]), opts({ offset: 5 }))
    expect(r.nodes[0]!.position).toEqual({ x: 5, y: 5 })
  })

  it('reuses an existing Input endpoint instead of minting a duplicate, and rewires its edges', () => {
    const g = graph([node('in', 'input'), node('j', 'jsonata')], [edge('e1', 'in', 'j')])
    const r = remapPastedGraph(g, opts({ existingEndpoints: new Map([['input', 'EXISTING_IN']]) }))
    // The Input is not added (it already exists); only the functional node is.
    expect(r.nodes.map(n => n.id)).toEqual(['N1'])
    // Its outgoing edge is rewired onto the existing Input id.
    expect(r.edges).toEqual([{ id: 'E1', source: 'EXISTING_IN', target: 'N1' }])
  })

  it('reuses an existing Output endpoint, rewiring an inbound edge onto it', () => {
    const g = graph([node('j', 'jsonata'), node('out', 'output')], [edge('e1', 'j', 'out')])
    const r = remapPastedGraph(g, opts({ existingEndpoints: new Map([['output', 'EXISTING_OUT']]) }))
    expect(r.nodes.map(n => n.id)).toEqual(['N1'])
    expect(r.edges).toEqual([{ id: 'E1', source: 'N1', target: 'EXISTING_OUT' }])
  })

  it('mints a fresh Input when the canvas has none (the endpoint is brought along)', () => {
    const r = remapPastedGraph(graph([node('in', 'input')]), opts())
    expect(r.nodes).toHaveLength(1)
    expect(r.nodes[0]).toMatchObject({ id: 'N1', type: 'input' })
  })

  it('preserves source and target ports when rewiring an edge', () => {
    const g = graph([node('a'), node('b')], [edge('e1', 'a', 'b', { sourcePort: 'true', targetPort: 'left' })])
    const r = remapPastedGraph(g, opts())
    expect(r.edges[0]).toMatchObject({ source: 'N1', target: 'N2', sourcePort: 'true', targetPort: 'left' })
  })

  it('drops an edge whose endpoint is not in the payload', () => {
    const r = remapPastedGraph(graph([node('a')], [edge('e1', 'a', 'ghost')]), opts())
    expect(r.edges).toEqual([])
  })

  it('drops an edge that collapses to a self-loop when two endpoints merge onto one existing node', () => {
    // Two incoming Input nodes both reuse the single existing Input — an edge between them is a self-loop.
    const g = graph([node('in1', 'input'), node('in2', 'input')], [edge('e1', 'in1', 'in2')])
    const r = remapPastedGraph(g, opts({ existingEndpoints: new Map([['input', 'X']]) }))
    expect(r.nodes).toEqual([])
    expect(r.edges).toEqual([])
  })

  it('mints a fresh id for each group and offsets its frame', () => {
    const r = remapPastedGraph(graph([], [], [group('g1')]), opts({ offset: 48 }))
    expect(r.groups[0]).toMatchObject({ id: 'G1', x: 53, y: 54 })
  })

  it('remaps group membership to the pasted node ids', () => {
    const g = graph([node('a'), node('b')], [], [group('g1', ['a', 'b'])])
    const r = remapPastedGraph(g, opts())
    expect(r.groups[0]!.nodeIds).toEqual(['N1', 'N2'])
  })

  it('drops a reused endpoint from a pasted frame (it belongs to the existing canvas, not the new frame)', () => {
    const g = graph([node('in', 'input'), node('a')], [], [group('g1', ['in', 'a'])])
    const r = remapPastedGraph(g, opts({ existingEndpoints: new Map([['input', 'X']]) }))
    expect(r.groups[0]!.nodeIds).toEqual(['N1'])
  })

  it('drops an unknown member id from a pasted frame', () => {
    const g = graph([node('a')], [], [group('g1', ['a', 'missing'])])
    const r = remapPastedGraph(g, opts())
    expect(r.groups[0]!.nodeIds).toEqual(['N1'])
  })

  it('preserves nested group membership regardless of declaration order', () => {
    // The parent frame lists the child frame's id, but the child is declared after it. Membership must
    // still resolve to the child's freshly minted id (the remap assigns all ids before remapping members).
    const g = graph([node('a')], [], [group('parent', ['child']), group('child', ['a'])])
    const r = remapPastedGraph(g, opts())
    const parent = r.groups.find(grp => grp.id === 'G1')!
    const child = r.groups.find(grp => grp.id === 'G2')!
    expect(parent.nodeIds).toEqual(['G2'])
    expect(child.nodeIds).toEqual(['N1'])
  })

  it('does not mutate the input graph', () => {
    const original = graph(
      [node('a', 'jsonata', { expression: '$.x' })],
      [edge('e1', 'a', 'a')],
      [group('g1', ['a'])],
    )
    const snapshot = structuredClone(original)
    remapPastedGraph(original, opts({ offset: 48 }))
    expect(original).toEqual(snapshot)
  })
})

import { describe, it, expect } from 'vitest'
import {
  deriveRunVizNodes,
  mapStatusToVizState,
  resolveNodeName,
  resolveNodeTransitions,
  type RunGraphStruct,
  type RunNodeTypeMeta,
  type RunVizInput,
} from './pipelineRunViz'

describe('mapStatusToVizState', () => {
  it('maps each known run/node status to its visual state', () => {
    expect(mapStatusToVizState('OK')).toBe('ran')
    expect(mapStatusToVizState('FAILED')).toBe('error')
    expect(mapStatusToVizState('RUNNING')).toBe('running')
    expect(mapStatusToVizState('SUSPENDED')).toBe('awaiting')
    expect(mapStatusToVizState('CANCELLED')).toBe('skipped')
    expect(mapStatusToVizState('SKIPPED')).toBe('skipped')
  })

  it('returns null for an unrecognized status so the node stays neutral', () => {
    expect(mapStatusToVizState('WAT')).toBeNull()
    expect(mapStatusToVizState('')).toBeNull()
  })
})

describe('deriveRunVizNodes', () => {
  const empty: RunVizInput = { nodes: [], completedNodeIds: [], awaitingNodeIds: [] }

  it('returns nothing for an empty run', () => {
    expect(deriveRunVizNodes(empty)).toEqual([])
  })

  it('maps a single timeline entry, carrying its routed port', () => {
    const result = deriveRunVizNodes({
      ...empty,
      nodes: [{ nodeId: 'route', status: 'OK', port: 'true' }],
    })
    expect(result).toEqual([{ nodeId: 'route', state: 'ran', port: 'true' }])
  })

  it('defaults a missing port to null', () => {
    const result = deriveRunVizNodes({ ...empty, nodes: [{ nodeId: 'a', status: 'OK' }] })
    expect(result).toEqual([{ nodeId: 'a', state: 'ran', port: null }])
  })

  it('lets the latest timeline entry win for a node that ran more than once', () => {
    const result = deriveRunVizNodes({
      ...empty,
      nodes: [
        { nodeId: 'a', status: 'RUNNING', port: null },
        { nodeId: 'a', status: 'OK', port: 'out' },
      ],
    })
    expect(result).toEqual([{ nodeId: 'a', state: 'ran', port: 'out' }])
  })

  it('ignores an unrecognized status rather than emitting a node', () => {
    const result = deriveRunVizNodes({ ...empty, nodes: [{ nodeId: 'a', status: 'WAT' }] })
    expect(result).toEqual([])
  })

  it('shows a completed node with no timeline entry as ran', () => {
    const result = deriveRunVizNodes({ ...empty, completedNodeIds: ['a'] })
    expect(result).toEqual([{ nodeId: 'a', state: 'ran', port: null }])
  })

  it('does not override a node that already has a timeline state with the completed fallback', () => {
    const result = deriveRunVizNodes({
      ...empty,
      nodes: [{ nodeId: 'a', status: 'FAILED' }],
      completedNodeIds: ['a'],
    })
    expect(result).toEqual([{ nodeId: 'a', state: 'error', port: null }])
  })

  it('lets a current await override any timeline state, keeping the routed port', () => {
    const result = deriveRunVizNodes({
      ...empty,
      nodes: [{ nodeId: 'a', status: 'RUNNING', port: 'p' }],
      awaitingNodeIds: ['a'],
    })
    expect(result).toEqual([{ nodeId: 'a', state: 'awaiting', port: 'p' }])
  })

  it('resolves a full run into one entry per node', () => {
    const result = deriveRunVizNodes({
      nodes: [
        { nodeId: 'input', status: 'OK', port: null },
        { nodeId: 'route', status: 'OK', port: 'true' },
        { nodeId: 'fetch', status: 'RUNNING', port: null },
        { nodeId: 'other', status: 'SKIPPED', port: null },
      ],
      completedNodeIds: ['input', 'route'],
      awaitingNodeIds: ['fetch'],
    })
    expect(result).toEqual([
      { nodeId: 'input', state: 'ran', port: null },
      { nodeId: 'route', state: 'ran', port: 'true' },
      { nodeId: 'fetch', state: 'awaiting', port: null },
      { nodeId: 'other', state: 'skipped', port: null },
    ])
  })
})

describe('resolveNodeName', () => {
  const types: RunNodeTypeMeta[] = [
    { key: 'jsonata', label: 'JSONata' },
    { key: 'executeJob', label: 'Execute Job' },
  ]
  const graph: RunGraphStruct = {
    nodes: [
      { id: 'n1', type: 'jsonata', name: 'Normalize payload' },
      { id: 'n2', type: 'jsonata' },
      { id: 'n3', type: 'jsonata', name: '   ' },
      { id: 'n4', type: 'input' },
      { id: 'n5', type: 'output' },
      { id: 'n6', type: 'mystery' },
      { id: 'n7', type: 'jsonata', settings: { name: 'From settings' } },
    ],
  }

  it('prefers the operator-given top-level name', () => {
    expect(resolveNodeName('n1', graph, types)).toBe('Normalize payload')
  })

  it('falls back to the node type label when unnamed', () => {
    expect(resolveNodeName('n2', graph, types)).toBe('JSONata')
  })

  it('treats a blank/whitespace name as unnamed', () => {
    expect(resolveNodeName('n3', graph, types)).toBe('JSONata')
  })

  it('labels the structural input and output nodes', () => {
    expect(resolveNodeName('n4', graph, types)).toBe('Input')
    expect(resolveNodeName('n5', graph, types)).toBe('Output')
  })

  it('falls back to the raw type when no palette entry matches', () => {
    expect(resolveNodeName('n6', graph, types)).toBe('mystery')
  })

  it('reads a name nested under settings when there is no top-level name', () => {
    expect(resolveNodeName('n7', graph, types)).toBe('From settings')
  })

  it('falls back to the nodeId when the node is not in the graph', () => {
    expect(resolveNodeName('ghost', graph, types)).toBe('ghost')
    expect(resolveNodeName('n1', {}, types)).toBe('n1')
  })
})

describe('resolveNodeTransitions', () => {
  const types: RunNodeTypeMeta[] = [{ key: 'jsonata', label: 'JSONata' }]
  const graph: RunGraphStruct = {
    nodes: [
      { id: 'route', type: 'jsonata', name: 'Route' },
      { id: 'yes', type: 'jsonata', name: 'On yes' },
      { id: 'no', type: 'jsonata', name: 'On no' },
      { id: 'fan', type: 'jsonata', name: 'Fan out' },
      { id: 'a', type: 'jsonata', name: 'A' },
      { id: 'b', type: 'jsonata', name: 'B' },
    ],
    edges: [
      { source: 'route', target: 'yes', sourcePort: 'true' },
      { source: 'route', target: 'no', sourcePort: 'false' },
      { source: 'fan', target: 'a' },
      { source: 'fan', target: 'b' },
    ],
  }

  it('returns only the branch matching the routed port', () => {
    expect(resolveNodeTransitions('route', 'true', graph, types)).toEqual([
      { targetId: 'yes', targetName: 'On yes', port: 'true' },
    ])
  })

  it('returns every downstream target when the node did not route on a port', () => {
    expect(resolveNodeTransitions('fan', null, graph, types)).toEqual([
      { targetId: 'a', targetName: 'A', port: null },
      { targetId: 'b', targetName: 'B', port: null },
    ])
  })

  it('returns all branches when the port is unknown', () => {
    expect(resolveNodeTransitions('route', null, graph, types)).toEqual([
      { targetId: 'yes', targetName: 'On yes', port: 'true' },
      { targetId: 'no', targetName: 'On no', port: 'false' },
    ])
  })

  it('returns nothing for a node with no outgoing edges', () => {
    expect(resolveNodeTransitions('yes', null, graph, types)).toEqual([])
    expect(resolveNodeTransitions('route', 'true', {}, types)).toEqual([])
  })

  it('de-duplicates parallel edges to the same target on the same port', () => {
    const dupes: RunGraphStruct = {
      nodes: [{ id: 's', type: 'jsonata', name: 'S' }, { id: 't', type: 'jsonata', name: 'T' }],
      edges: [
        { source: 's', target: 't', sourcePort: null },
        { source: 's', target: 't', sourcePort: null },
      ],
    }
    expect(resolveNodeTransitions('s', null, dupes, types)).toEqual([
      { targetId: 't', targetName: 'T', port: null },
    ])
  })
})

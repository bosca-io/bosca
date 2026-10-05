/**
 * Pure graph transform behind the pipeline editor's copy/paste.
 *
 * Copy serialises the SAME stored graph the executor runs and the editor saves; paste merges a copied
 * graph back into the canvas. The merge can't reuse the source ids — pasting into the same pipeline
 * (or composing two pipelines) would collide — so every incoming node, group, and edge is re-minted.
 * Two rules make the merge safe:
 *  1. **Structural endpoints are singletons.** A pipeline has at most one Input and one Output node
 *     (the editor's accepted-input-type contract reads from the single Input). When the target canvas
 *     already has an endpoint of a given kind, the incoming one is dropped and its edges are rewired to
 *     the existing endpoint instead of minting a duplicate.
 *  2. **Membership and wiring follow the remap.** Group membership and edge source/target are rewritten
 *     through the same id map, so a pasted frame keeps its members and a pasted edge keeps its wiring —
 *     while a self-loop produced by collapsing two endpoints onto one id is dropped.
 *
 * Pure and side-effect-free: id minting is injected (so output is deterministic and unit-testable) and
 * the impure parts — clipboard I/O and Vue Flow hydration — stay in the editor. Inputs are never
 * mutated; nodes/groups/edges are returned as fresh objects.
 */

/** A stored pipeline node: `type` is the node kind, the rest of the keys are its typed settings. */
export interface ClipboardNode {
  type: string
  id: string
  position?: { x: number, y: number }
  [key: string]: unknown
}

export interface ClipboardEdge {
  id: string
  source: string
  target: string
  sourcePort?: string | null
  targetPort?: string | null
}

export interface ClipboardGroup {
  id: string
  label: string
  description?: string
  x: number
  y: number
  width: number
  height: number
  collapsed: boolean
  color: string | null
  nodeIds: string[]
}

export interface ClipboardGraph {
  nodes: ClipboardNode[]
  edges: ClipboardEdge[]
  groups: ClipboardGroup[]
}

/** The structural endpoint kinds that may appear at most once in a pipeline. */
const ENDPOINT_KINDS = new Set(['input', 'output'])

export interface RemapOptions {
  /** Endpoint kind ('input'/'output') → the id of the canvas node to reuse for it, when one exists. */
  existingEndpoints: Map<string, string>
  /** Pixels to shift every pasted node/group by, so a merge doesn't land exactly on existing nodes. */
  offset: number
  nextNodeId: () => string
  nextGroupId: () => string
  nextEdgeId: () => string
}

export interface RemapResult {
  nodes: ClipboardNode[]
  groups: ClipboardGroup[]
  edges: ClipboardEdge[]
}

/**
 * Re-mint a copied graph for insertion into the canvas. Returns only the nodes/groups/edges to add —
 * a reused structural endpoint is not returned as a node (it already exists), but its incoming edges
 * are rewired onto it. `nodes.length` is the count of functional nodes the paste actually adds.
 */
export function remapPastedGraph(graph: ClipboardGraph, opts: RemapOptions): RemapResult {
  const { existingEndpoints, offset, nextNodeId, nextGroupId, nextEdgeId } = opts

  // Every incoming id maps to a destination id: a reused existing endpoint (then dropped from the
  // output), or a fresh id. Groups always get a fresh id.
  const idMap = new Map<string, string>()
  const dropped = new Set<string>()
  const nodes: ClipboardNode[] = []
  for (const node of graph.nodes) {
    const reuse = ENDPOINT_KINDS.has(node.type) ? existingEndpoints.get(node.type) : undefined
    if (reuse) {
      idMap.set(node.id, reuse)
      dropped.add(node.id)
      continue
    }
    const id = nextNodeId()
    idMap.set(node.id, id)
    nodes.push({ ...node, id, position: { x: (node.position?.x ?? 0) + offset, y: (node.position?.y ?? 0) + offset } })
  }

  // Assign every group a fresh id first, so membership can be remapped against the complete id map —
  // a group's nodeIds may reference a nested group that appears later in the list.
  const groups: ClipboardGroup[] = graph.groups.map((group) => {
    const id = nextGroupId()
    idMap.set(group.id, id)
    return { ...group, id, x: group.x + offset, y: group.y + offset, nodeIds: [] as string[] }
  })
  groups.forEach((group, i) => {
    // A dropped endpoint or an id with no mapping (a member referencing a node not in the payload)
    // falls out of the frame.
    group.nodeIds = graph.groups[i]!.nodeIds
      .filter(memberId => idMap.has(memberId) && !dropped.has(memberId))
      .map(memberId => idMap.get(memberId)!)
  })

  const edges: ClipboardEdge[] = []
  for (const edge of graph.edges) {
    const source = idMap.get(edge.source)
    const target = idMap.get(edge.target)
    // Drop an edge whose endpoint isn't in the payload, or that collapses to a self-loop because both
    // ends mapped onto the same reused endpoint.
    if (!source || !target || source === target) continue
    edges.push({ ...edge, id: nextEdgeId(), source, target })
  }

  return { nodes, groups, edges }
}

/**
 * Dependency-free layered (Sugiyama-style) layout for a pipeline DAG.
 *
 * Vue Flow ships no layout algorithm — it renders nodes wherever you put them — so a graph that
 * arrives without positions (a seeded installer pipeline, a git-synced YAML, an API-created graph)
 * stacks every node at the origin. This computes a left-to-right layout the editor applies when the
 * author clicks Auto-arrange:
 *  1. **Column** = each node's longest path from a source, so flow reads left → right.
 *  2. **Order within a column** = the barycenter (mean row) of a node's predecessors, one pass, to
 *     reduce edge crossings — predecessors always sit in an earlier column, so they're already ordered.
 *  3. **Placement** = columns spaced by their widest node, each column centered vertically so rows
 *     line up across the canvas.
 *
 * Pure and side-effect-free (it only reads its inputs), so it unit-tests directly. Cycle-safe — a back
 * edge simply doesn't advance the column — though pipeline graphs are DAGs.
 */

export interface LayoutNode {
  id: string
  width: number
  height: number
}

export interface LayoutEdge {
  source: string
  target: string
}

export interface LayoutOptions {
  /** Horizontal gap between columns. */
  columnGap?: number
  /** Vertical gap between nodes within a column. */
  rowGap?: number
  /** Top-left origin of the laid-out block. */
  originX?: number
  originY?: number
}

export function computePipelineLayout(
  nodes: LayoutNode[],
  edges: LayoutEdge[],
  options: LayoutOptions = {},
): Map<string, { x: number, y: number }> {
  const columnGap = options.columnGap ?? 120
  const rowGap = options.rowGap ?? 48
  const originX = options.originX ?? 40
  const originY = options.originY ?? 40

  const positions = new Map<string, { x: number, y: number }>()
  if (nodes.length === 0) return positions

  const ids = new Set(nodes.map(n => n.id))
  const preds = new Map<string, string[]>()
  nodes.forEach(n => preds.set(n.id, []))
  for (const e of edges) {
    // Ignore self-loops and edges to nodes outside this set (e.g. a node inside a group frame).
    if (e.source === e.target || !ids.has(e.source) || !ids.has(e.target)) continue
    preds.get(e.target)!.push(e.source)
  }

  // Column = longest path from a source. Memoized; a node already on the recursion stack (a cycle)
  // contributes 0 rather than recursing forever.
  const column = new Map<string, number>()
  const onStack = new Set<string>()
  const columnOf = (id: string): number => {
    const cached = column.get(id)
    if (cached !== undefined) return cached
    if (onStack.has(id)) return 0
    onStack.add(id)
    let c = 0
    for (const p of preds.get(id) ?? []) c = Math.max(c, columnOf(p) + 1)
    onStack.delete(id)
    column.set(id, c)
    return c
  }
  nodes.forEach(n => columnOf(n.id))

  const inputOrder = new Map(nodes.map((n, i) => [n.id, i]))
  const maxCol = Math.max(...column.values())
  const byCol: LayoutNode[][] = Array.from({ length: maxCol + 1 }, () => [])
  nodes.forEach(n => byCol[column.get(n.id)!]!.push(n))

  // Row within a column: column 0 by input order; later columns by the barycenter of predecessor rows.
  const row = new Map<string, number>()
  for (let c = 0; c <= maxCol; c++) {
    const col = byCol[c]!
    if (c === 0) {
      col.sort((a, b) => inputOrder.get(a.id)! - inputOrder.get(b.id)!)
    }
    else {
      const barycenter = (id: string): number => {
        const ps = (preds.get(id) ?? []).filter(p => row.has(p))
        if (ps.length === 0) return inputOrder.get(id)!
        return ps.reduce((sum, p) => sum + row.get(p)!, 0) / ps.length
      }
      col.sort((a, b) => barycenter(a.id) - barycenter(b.id) || inputOrder.get(a.id)! - inputOrder.get(b.id)!)
    }
    col.forEach((n, i) => row.set(n.id, i))
  }

  // x: cumulative by each column's widest node. y: stack heights + gaps, centering every column on
  // the tallest so the rows read straight across.
  const colWidth = byCol.map(col => Math.max(0, ...col.map(n => n.width)) || 180)
  const colHeight = byCol.map(col => col.reduce((sum, n) => sum + n.height, 0) + Math.max(0, col.length - 1) * rowGap)
  const tallest = Math.max(0, ...colHeight)

  let x = originX
  for (let c = 0; c <= maxCol; c++) {
    let y = originY + (tallest - colHeight[c]!) / 2
    for (const n of byCol[c]!) {
      positions.set(n.id, { x, y })
      y += n.height + rowGap
    }
    x += colWidth[c]! + columnGap
  }
  return positions
}

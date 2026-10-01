export interface PromotionOrderable {
  id: string
  displayOrder: number
  promotionSourceIds?: string[] | null
}

/**
 * A program's environments can form several UNRELATED promotion families — helm-values environments
 * (Development → Staging → Production), Play tracks (internal → production), App Store stages — and an
 * arrow between two environments is only true within one family. This splits the environments into the
 * connected components of the promotion graph (walking `promotionSourceIds` edges both ways), orders
 * each component by its allowed promotion flow (topological, Kahn), and orders the components
 * themselves by their smallest displayOrder. Environments with no promotion edges are their own
 * single-stop chains; a misconfigured cycle appends its members by displayOrder rather than dropping them.
 */
export function promotionChains<T extends PromotionOrderable>(environments: T[]): T[][] {
  const ids = new Set(environments.map(e => e.id))
  const neighbors = new Map<string, Set<string>>(environments.map(e => [e.id, new Set()]))
  for (const env of environments) {
    for (const sourceId of env.promotionSourceIds ?? []) {
      if (!ids.has(sourceId)) continue
      neighbors.get(env.id)!.add(sourceId)
      neighbors.get(sourceId)!.add(env.id)
    }
  }

  const seen = new Set<string>()
  const components: T[][] = []
  for (const env of environments) {
    if (seen.has(env.id)) continue
    const member = new Set<string>()
    const queue = [env.id]
    while (queue.length) {
      const id = queue.shift()!
      if (member.has(id)) continue
      member.add(id)
      seen.add(id)
      queue.push(...neighbors.get(id) ?? [])
    }
    components.push(topoSort(environments.filter(e => member.has(e.id))))
  }

  return components.sort((a, b) =>
    Math.min(...a.map(e => e.displayOrder)) - Math.min(...b.map(e => e.displayOrder))
    || environments.indexOf(a[0]!) - environments.indexOf(b[0]!))
}

/** All environments in one list: chain by chain, each chain in promotion order. */
export function promotionOrder<T extends PromotionOrderable>(environments: T[]): T[] {
  return promotionChains(environments).flat()
}

/** Kahn topological sort by promotion edges, displayOrder breaking ties among same-depth environments. */
function topoSort<T extends PromotionOrderable>(environments: T[]): T[] {
  const ids = new Set(environments.map(e => e.id))
  const indegree = new Map<string, number>(environments.map(e => [e.id, 0]))
  const downstream = new Map<string, string[]>()
  for (const env of environments) {
    for (const sourceId of env.promotionSourceIds ?? []) {
      if (!ids.has(sourceId)) continue
      indegree.set(env.id, (indegree.get(env.id) ?? 0) + 1)
      downstream.set(sourceId, [...(downstream.get(sourceId) ?? []), env.id])
    }
  }
  const tiebreak = (a: T, b: T) =>
    a.displayOrder - b.displayOrder || environments.indexOf(a) - environments.indexOf(b)
  const ready = environments.filter(e => (indegree.get(e.id) ?? 0) === 0).sort(tiebreak)
  const ordered: T[] = []
  while (ready.length) {
    const next = ready.shift()!
    ordered.push(next)
    for (const id of downstream.get(next.id) ?? []) {
      const remaining = (indegree.get(id) ?? 0) - 1
      indegree.set(id, remaining)
      if (remaining === 0) {
        ready.push(environments.find(e => e.id === id)!)
        ready.sort(tiebreak)
      }
    }
  }
  if (ordered.length < environments.length) {
    const placed = new Set(ordered.map(e => e.id))
    ordered.push(...environments.filter(e => !placed.has(e.id)).sort(tiebreak))
  }
  return ordered
}

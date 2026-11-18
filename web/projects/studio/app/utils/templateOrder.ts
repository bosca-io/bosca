/**
 * Template attribute holding a template's position, set by dragging rows on
 * Settings → Templates. Same name collections use for drag-and-drop order.
 */
export const TEMPLATE_SORT_ATTRIBUTE = 'sort'

interface OrderableTemplate {
  name: string
  attributes?: Record<string, unknown> | null
}

/** The template's saved position, or null when it has never been ordered. */
export function templateSortValue(template: OrderableTemplate): number | null {
  const value = template.attributes?.[TEMPLATE_SORT_ATTRIBUTE]
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

/**
 * Ordered templates first, by their saved position. Templates without one
 * (e.g. newly created) follow, A–Z. Name breaks ties.
 */
export function compareTemplatesByOrder(a: OrderableTemplate, b: OrderableTemplate): number {
  const as = templateSortValue(a)
  const bs = templateSortValue(b)
  if (as !== null && bs !== null && as !== bs) return as - bs
  if (as !== null && bs === null) return -1
  if (as === null && bs !== null) return 1
  return a.name.localeCompare(b.name)
}

interface TypedTemplate extends OrderableTemplate {
  defaultAttributes?: Record<string, unknown> | null
}

/**
 * Maps each document `type` to its template's saved position, so lists keyed
 * by type (e.g. the Documents and Guides filter tabs) can follow the order set
 * on Settings → Templates. A template is matched by the `type` in its default
 * attributes and by its name. When several templates share a key, the
 * highest-placed one wins.
 */
export function templateOrderByType(templates: TypedTemplate[]): Map<string, number> {
  const order = new Map<string, number>()
  for (const template of templates) {
    const position = templateSortValue(template)
    if (position === null) continue
    const type = template.defaultAttributes?.type
    for (const key of [typeof type === 'string' ? type : null, template.name]) {
      if (!key) continue
      const current = order.get(key)
      if (current === undefined || position < current) order.set(key, position)
    }
  }
  return order
}

/**
 * Type facets ordered by template position. Facets with no matching template
 * keep their incoming order (most-used first) and follow the ordered ones.
 */
export function sortFacetsByTemplateOrder<T extends { value: string }>(facets: T[], order: Map<string, number>): T[] {
  return [...facets].sort((a, b) => {
    const ap = order.get(a.value)
    const bp = order.get(b.value)
    if (ap !== undefined && bp !== undefined) return ap - bp
    if (ap !== undefined) return -1
    if (bp !== undefined) return 1
    return 0
  })
}

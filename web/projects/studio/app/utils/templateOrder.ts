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

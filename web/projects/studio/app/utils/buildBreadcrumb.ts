import { SUBSYSTEMS } from '~/composables/useSubsystems'

type BreadcrumbItem = string | { label: string; to?: string }

const SUBSYSTEM_ROUTES: Record<string, string> = {}
const NAV_ROUTES: Record<string, Record<string, string>> = {}
const GROUP_ROUTES: Record<string, Record<string, string>> = {}

for (const sub of SUBSYSTEMS) {
  const firstItem = sub.nav[0]?.items?.[0]?.id || ''
  SUBSYSTEM_ROUTES[sub.label] = `/${sub.id}/${firstItem}`

  const items: Record<string, string> = {}
  const groups: Record<string, string> = {}
  for (const group of sub.nav) {
    if (group.items.length > 0) {
      groups[group.group] = `/${sub.id}/${group.items[0]!.id}`
    }
    for (const item of group.items) {
      items[item.label] = `/${sub.id}/${item.id}`
    }
  }
  NAV_ROUTES[sub.label] = items
  GROUP_ROUTES[sub.label] = groups
}

SUBSYSTEM_ROUTES['Studio'] = '/'

/**
 * Builds a breadcrumb array with auto-resolved navigation links.
 * Known subsystem and nav-item labels are converted to clickable links.
 * The last item is always rendered as plain text (current page).
 * Pass `{ label, to }` objects for items that need explicit routes
 * (e.g., dynamic detail pages).
 */
export function buildBreadcrumb(...items: (string | BreadcrumbItem)[]): BreadcrumbItem[] {
  return items.map((item, i) => {
    if (i === items.length - 1) {
      return typeof item === 'string' ? item : { label: item.label }
    }

    if (typeof item !== 'string') return item

    if (i === 0) {
      const to = SUBSYSTEM_ROUTES[item]
      return to ? { label: item, to } : item
    }

    const subsystemLabel = typeof items[0] === 'string' ? items[0] : items[0]!.label
    const navRoutes = NAV_ROUTES[subsystemLabel]
    const groupRoutes = GROUP_ROUTES[subsystemLabel]

    const to = navRoutes?.[item] || groupRoutes?.[item]
    return to ? { label: item, to } : item
  })
}

import { computed } from 'vue'
import { useRoute, useRouter } from '#imports'

/**
 * Subsystem-wide namespace filter. Reflected in the URL as `?ns=foo,bar`
 * so it persists across navigation and is shareable. An empty set means
 * "all namespaces" — pages should treat it as no-filter.
 *
 * Multiple pages can call this composable; they all see the same set via
 * the shared route query.
 */
export function useK8sNamespaceFilter() {
  const route = useRoute()
  const router = useRouter()

  const selected = computed<string[]>(() => {
    const raw = (route.query.ns as string | undefined) ?? ''
    if (!raw) return []
    return raw.split(',').filter(Boolean)
  })

  const has = (ns: string) => selected.value.includes(ns)
  const isEmpty = computed(() => selected.value.length === 0)
  const summary = computed(() => {
    if (selected.value.length === 0) return 'All namespaces'
    if (selected.value.length === 1) return selected.value[0]
    return `${selected.value.length} namespaces`
  })

  function write(ns: string[]) {
    const next = { ...route.query }
    if (ns.length === 0) delete next.ns
    else next.ns = ns.join(',')
    router.replace({ query: next })
  }

  function toggle(ns: string) {
    const set = new Set(selected.value)
    if (set.has(ns)) set.delete(ns)
    else set.add(ns)
    write(Array.from(set))
  }

  function clear() {
    write([])
  }

  function set(ns: string[]) {
    write(ns)
  }

  /** Returns true when the page-level namespace passes this filter (or the filter is empty). */
  function matches(ns: string | undefined | null): boolean {
    if (!selected.value.length) return true
    return !!ns && selected.value.includes(ns)
  }

  return { selected, isEmpty, summary, has, toggle, clear, set, matches }
}

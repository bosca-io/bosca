import type { DocumentNode } from 'graphql'

/**
 * Offset/limit paging over a `useGraphQL().useAsyncQuery` list. Fetches one row beyond the page size
 * so it can tell whether a next page exists without a separate count query, and resets to the first
 * page whenever a scoping variable (companyId, storeId, …) changes. `baseVars` may hold refs/computeds.
 */
export function usePagedList<T>(
  key: string,
  query: DocumentNode,
  baseVars: Record<string, unknown>,
  extract: (data: unknown) => T[] | undefined | null,
  pageSize = 25,
) {
  const { useAsyncQuery } = useGraphQL()
  const offset = ref(0)
  const limit = computed(() => pageSize + 1) // fetch one extra to detect a further page

  // Reset to page 1 when any scope var changes (otherwise an offset can point past the new result set).
  watch(
    () => Object.values(baseVars).map(v => unref(v)).join('|'),
    () => { offset.value = 0 },
  )

  const { data, status, refresh, error } = useAsyncQuery<unknown>(key, query, { ...baseVars, offset, limit })
  const raw = computed<T[]>(() => extract(data.value) ?? [])
  const rows = computed(() => raw.value.slice(0, pageSize))
  const hasMore = computed(() => raw.value.length > pageSize)

  return { rows, hasMore, offset, pageSize, status, refresh, error }
}

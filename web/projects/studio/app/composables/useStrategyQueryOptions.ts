import gql from 'graphql-tag'
import type { Ref } from 'vue'
import type { SelectOption } from '@bosca/ui'

/** What a query returns/accepts, as probed by the backend (`analytics.queries.all`). */
interface QueryInfo {
  id: string
  name: string
  columns: { name: string }[]
  parameters: { parameter: string }[]
}

/**
 * The output-column / parameter contract each strategy type's evaluator requires of its bound
 * analytics query (see RecommendationStrategyServiceImpl). `null` means the type doesn't use a query.
 * `anyColumns` = at least one must be present; `columns` = all must be present; `parameters` = all
 * must be declared.
 */
const REQUIREMENTS: Record<string, { anyColumns?: string[]; columns?: string[]; parameters?: string[]; label: string } | null> = {
  TRENDING: { anyColumns: ['metadata_id', 'collection_id'], label: 'metadata_id or collection_id (+ optional score, reason)' },
  CO_ENGAGEMENT: { columns: ['source_id', 'related_id'], label: 'source_id and related_id (+ optional score, reason)' },
  PERSONALIZED: null,
}

/**
 * Drives the analytics-query dropdown on the strategy forms: loads every query with its probed
 * columns/parameters and, for the currently-selected strategy [type], marks queries whose shape
 * doesn't satisfy that type's contract as disabled (with the reason), rather than hiding them — so
 * the requirement is discoverable. Queries whose columns couldn't be probed are left selectable.
 */
export function useStrategyQueryOptions(type: Ref<string>) {
  const { useAsyncQuery } = useGraphQL()
  const queriesGql = gql`
    query RecommendationStrategyQueryOptions {
      analytics {
        queries {
          all { id name columns { name } parameters { parameter } }
        }
      }
    }
  `
  const { data } = useAsyncQuery<{ analytics: { queries: { all: QueryInfo[] } } }>(
    'recommendation-strategy-query-options', queriesGql,
  )

  const requirement = computed(() => REQUIREMENTS[type.value] ?? null)
  const usesQuery = computed(() => requirement.value !== null)
  const requirementLabel = computed(() => requirement.value?.label ?? '')

  /** Returns null if the query satisfies the current type's contract (or columns are unknown), else the reason it doesn't. */
  function incompatibleReason(q: QueryInfo): string | null {
    const req = requirement.value
    if (!req) return null
    if (q.columns.length === 0) return null // columns undeterminable — don't block
    const cols = new Set(q.columns.map(c => c.name))
    const params = new Set(q.parameters.map(p => p.parameter))
    if (req.anyColumns && !req.anyColumns.some(c => cols.has(c))) {
      return `needs ${req.anyColumns.join(' or ')}`
    }
    const missingCols = (req.columns ?? []).filter(c => !cols.has(c))
    if (missingCols.length) return `needs ${missingCols.join(', ')}`
    const missingParams = (req.parameters ?? []).filter(p => !params.has(p))
    if (missingParams.length) return `needs ${missingParams.join(', ')} parameter`
    return null
  }

  const options = computed<SelectOption[]>(() => {
    const all = data.value?.analytics?.queries?.all ?? []
    const opts: SelectOption[] = [{ value: '', label: 'None — query-less' }]
    for (const q of all) {
      const reason = incompatibleReason(q)
      opts.push({ value: q.id, label: reason ? `${q.name} — ${reason}` : q.name, disabled: !!reason })
    }
    return opts
  })

  return { options, usesQuery, requirementLabel }
}

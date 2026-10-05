import gql from 'graphql-tag'

interface StatTile {
  label: string
  value: string
  sub?: string
  accent?: string
}

interface VisualizationInstance {
  id: string
  configuration: Record<string, unknown> | null
  visualization: {
    id: string
    key: string
    name: string
    description: string
    queryId: string | null
    type: string
    configuration: Record<string, unknown> | null
  }
}

interface DashboardResponse {
  analytics: {
    dashboards: {
      byKey: {
        id: string
        key: string
        name: string
        parameters: DashboardParam[]
        visualizations: VisualizationInstance[]
      } | null
    }
  }
}

interface QueryExecutionResponse {
  analytics: {
    queries: {
      execute: {
        records: Array<Record<string, unknown>>
      }
    }
  }
}

interface DashboardParam {
  parameter: string
  name: string
  type: string
  defaultValue: unknown
}

function resolveParameterDefaults(params: DashboardParam[]): { parameter: string; value: unknown }[] {
  return params
    .filter(p => p.defaultValue != null)
    .map(p => ({ parameter: p.parameter, value: p.defaultValue }))
}

const dashboardGql = gql`
  query GetDashboardByKey($key: String!) {
    analytics {
      dashboards {
        byKey(key: $key) {
          id
          key
          name
          parameters { parameter name type defaultValue }
          visualizations {
            id
            configuration
            visualization {
              id
              key
              name
              description
              queryId
              type
              configuration
            }
          }
        }
      }
    }
  }
`

const executeQueryGql = gql`
  query ExecuteAnalyticsQuery($queryId: UUID!, $parameters: [AnalyticsQueryExecutionParameterInput!]!) {
    analytics {
      queries {
        execute(queryId: $queryId, parameters: $parameters) {
          records
        }
      }
    }
  }
`

const queryByIdGql = gql`
  query GetQueryParamsForStats($id: UUID!) {
    analytics { queries { queryById(id: $id) { parameters { parameter } } } }
  }
`

/**
 * Fetches a dashboard by key and executes all NUMBER-type visualization queries,
 * returning reactive stat tiles ready for rendering.
 *
 * Each visualization's first query result record is read for `value` and optionally
 * `sub` (subtitle text like "+312 this week"). The visualization's `name` becomes
 * the tile label. Instance-level `configuration.accent` overrides the page accent.
 */
export function useDashboardStats(
  dashboardKey: string,
  options?: { accent?: MaybeRef<string> },
) {
  const { query } = useGraphQL()

  const tiles = ref<StatTile[]>([])
  const loading = ref(true)
  const error = ref<Error | null>(null)

  async function load() {
    loading.value = true
    error.value = null

    try {
      const dashResult = await query<DashboardResponse>(dashboardGql, { key: dashboardKey })
      const dashboard = dashResult.analytics.dashboards.byKey

      if (!dashboard) {
        tiles.value = []
        return
      }

      const resolvedParams = resolveParameterDefaults(dashboard.parameters ?? [])

      const numberVizs = dashboard.visualizations.filter(
        v => v.visualization.type === 'NUMBER' && v.visualization.queryId,
      )

      const queryParamCache: Record<string, Set<string>> = {}
      async function getAcceptedParams(queryId: string): Promise<Set<string>> {
        if (queryParamCache[queryId]) return queryParamCache[queryId]
        try {
          const r = await query<{ analytics?: { queries?: { queryById?: { parameters?: Array<{ parameter: string }> } } } }>(queryByIdGql, { id: queryId })
          const names = (r.analytics?.queries?.queryById?.parameters ?? []).map((p) => p.parameter)
          queryParamCache[queryId] = new Set<string>(names)
        } catch {
          queryParamCache[queryId] = new Set<string>()
        }
        return queryParamCache[queryId]
      }

      const results = await Promise.all(
        numberVizs.map(async (viz): Promise<StatTile> => {
          try {
            const accepted = await getAcceptedParams(viz.visualization.queryId!)
            const filteredParams = accepted.size > 0
              ? resolvedParams.filter(p => accepted.has(p.parameter))
              : []
            const result = await query<QueryExecutionResponse>(executeQueryGql, {
              queryId: viz.visualization.queryId,
              parameters: filteredParams,
            })

            const record = result.analytics.queries.execute.records[0]
            const rawValue = record?.value
            const formattedValue = typeof rawValue === 'number'
              ? rawValue.toLocaleString()
              : String(rawValue ?? '--')

            return {
              label: viz.visualization.name,
              value: formattedValue,
              sub: (record?.sub as string | undefined) ?? viz.visualization.description ?? undefined,
              accent: viz.configuration?.accent as string | undefined
                ?? viz.visualization.configuration?.accent as string | undefined
                ?? toValue(options?.accent),
            }
          } catch {
            return {
              label: viz.visualization.name,
              value: '--',
              accent: toValue(options?.accent),
            }
          }
        }),
      )

      tiles.value = results
    } catch (e) {
      error.value = e instanceof Error ? e : new Error(String(e))
      tiles.value = []
    } finally {
      loading.value = false
    }
  }

  if (import.meta.client) {
    onMounted(load)
  }

  return { tiles, loading, error, refresh: load }
}

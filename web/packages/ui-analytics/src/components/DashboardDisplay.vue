<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { GridItem } from '../grid/types'
import type { VisualizationType, QueryExecutionParameter } from '../types'
import { maxRow } from '../grid/layout'
import AnalyticsVisualization from './AnalyticsVisualization.vue'

const props = withDefaults(defineProps<{
  dashboardKey: string
  graphqlUrl?: string
  getHeaders?: () => Promise<Record<string, string>>
  columns?: number
  cellHeight?: number
  gap?: number
}>(), {
  graphqlUrl: '/graphql',
  columns: 48,
  cellHeight: 20,
  gap: 4,
})

interface VizState {
  id: string
  x: number
  y: number
  w: number
  h: number
  showTitle: boolean
  showBorder: boolean
  showBackground: boolean
  boldTitle: boolean
  titleSize: string
  titleOverride: string
  visualization: { name: string; type: string; queryId?: string | null; configuration?: Record<string, unknown> }
  data: Record<string, unknown>[]
  loading: boolean
  error: string
}

const vizStates = ref<VizState[]>([])
const dashboardLoading = ref(false)
const dashboardError = ref('')
const dashboardName = ref('')
const defaultParams = ref<QueryExecutionParameter[]>([])
const overrideParams = ref<QueryExecutionParameter[]>([])
const queryParamCache = new Map<string, Promise<Set<string>>>()

const layout = computed<GridItem[]>(() =>
  vizStates.value.map(v => ({ id: v.id, x: v.x, y: v.y, w: v.w, h: v.h }))
)

const rows = computed(() => maxRow(layout.value))

function vizForItem(item: GridItem) {
  return vizStates.value.find(v => v.id === item.id)
}

async function gql(query: string, variables: Record<string, unknown> = {}) {
  const authHeaders = props.getHeaders ? await props.getHeaders() : {}
  const res = await fetch(props.graphqlUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders },
    credentials: 'include',
    body: JSON.stringify({ query, variables }),
  })
  const json = await res.json()
  if (json.errors?.length) throw new Error(json.errors[0].message)
  return json.data
}

async function loadDashboard(key: string) {
  dashboardLoading.value = true
  dashboardError.value = ''
  vizStates.value = []

  try {
    const data = await gql(`
      query GetDashboard($key: String!) {
        analytics {
          dashboards {
            byKey(key: $key) {
              id key name description
              parameters { parameter name defaultValue }
              visualizations {
                id configuration
                visualization { id key name type queryId configuration }
              }
            }
          }
        }
      }
    `, { key })

    const d = data.analytics.dashboards.byKey
    if (!d) { dashboardError.value = `Dashboard "${key}" not found`; return }

    dashboardName.value = d.name

    defaultParams.value = (d.parameters ?? [])
      .filter((p: any) => p.defaultValue != null)
      .map((p: any) => ({ parameter: p.parameter, value: p.defaultValue }))
    overrideParams.value = []

    vizStates.value = (d.visualizations ?? []).map((v: any) => {
      const cfg = v.configuration || {}
      return {
        id: v.id,
        x: cfg.x ?? 0,
        y: cfg.y ?? 0,
        w: cfg.w ?? 24,
        h: cfg.h ?? 12,
        showTitle: cfg.showTitle ?? true,
        showBorder: cfg.showBorder ?? true,
        showBackground: cfg.showBackground ?? true,
        boldTitle: cfg.boldTitle ?? false,
        titleSize: cfg.titleSize ?? 'sm',
        titleOverride: cfg.titleOverride ?? '',
        visualization: v.visualization,
        data: [],
        loading: !!v.visualization.queryId,
        error: '',
      }
    })

    executeAllQueries()
  } catch (e: any) {
    dashboardError.value = e?.message ?? 'Failed to load dashboard'
  } finally {
    dashboardLoading.value = false
  }
}

const mergedParams = computed<QueryExecutionParameter[]>(() => {
  const merged = new Map<string, QueryExecutionParameter>()
  for (const p of defaultParams.value) merged.set(p.parameter, p)
  for (const p of overrideParams.value) merged.set(p.parameter, p)
  return [...merged.values()]
})

async function getAcceptedParameters(queryId: string): Promise<Set<string>> {
  const cached = queryParamCache.get(queryId)
  if (cached) return cached

  const request = gql(`
    query GetDashboardQueryParameters($queryId: UUID!) {
      analytics {
        queries {
          queryById(id: $queryId) {
            parameters { parameter }
          }
        }
      }
    }
  `, { queryId }).then(data =>
    new Set<string>(
      (data.analytics.queries.queryById.parameters ?? [])
        .map((parameter: { parameter: string }) => parameter.parameter),
    ),
  )
  queryParamCache.set(queryId, request)

  try {
    return await request
  } catch (error) {
    queryParamCache.delete(queryId)
    throw error
  }
}

function executeAllQueries() {
  const params = mergedParams.value
  for (const state of vizStates.value) {
    if (!state.visualization.queryId) continue
    state.loading = true
    state.error = ''
    getAcceptedParameters(state.visualization.queryId).then(acceptedParameters =>
      gql(`
        query ExecuteQuery($queryId: UUID!, $parameters: [AnalyticsQueryExecutionParameterInput!]!) {
          analytics { queries { execute(queryId: $queryId, parameters: $parameters) { records } } }
        }
      `, {
        queryId: state.visualization.queryId,
        parameters: params.filter(parameter => acceptedParameters.has(parameter.parameter)),
      }),
    ).then(result => {
      state.data = result.analytics.queries.execute.records ?? []
      state.loading = false
    }).catch((e: any) => {
      state.error = e?.message ?? 'Query failed'
      state.loading = false
    })
  }
}

function onParamChange(params: QueryExecutionParameter[]) {
  for (const p of params) {
    const idx = overrideParams.value.findIndex(o => o.parameter === p.parameter)
    if (idx >= 0) overrideParams.value[idx] = p
    else overrideParams.value.push(p)
  }
  executeAllQueries()
}

watch(() => props.dashboardKey, (key) => {
  if (key) loadDashboard(key)
}, { immediate: true })
</script>

<template>
  <div v-if="dashboardLoading" class="dashboard-display__state">Loading…</div>
  <div v-else-if="dashboardError" class="dashboard-display__state dashboard-display__state--error">{{ dashboardError }}</div>
  <div v-else-if="vizStates.length === 0" class="dashboard-display__state">No visualizations.</div>
  <div
    v-else
    class="dashboard-display"
    :style="{
      '--columns': columns,
      '--cell-height': `${cellHeight}px`,
      '--grid-gap': `${gap}px`,
      '--rows': rows,
    }"
  >
    <div
      v-for="item in layout"
      :key="item.id"
      class="dashboard-display__item"
      :class="{ 'dashboard-display__item--no-border': vizForItem(item)?.showBorder === false, 'dashboard-display__item--no-bg': vizForItem(item)?.showBackground === false }"
      :style="{
        gridColumn: `${item.x + 1} / span ${item.w}`,
        gridRow: `${item.y + 1} / span ${item.h}`,
      }"
    >
      <div
        v-if="vizForItem(item)?.showTitle !== false"
        class="dashboard-display__title"
        :class="[
          `dashboard-display__title--${vizForItem(item)?.titleSize ?? 'sm'}`,
          { 'dashboard-display__title--bold': vizForItem(item)?.boldTitle },
        ]"
      >
        {{ vizForItem(item)?.titleOverride || vizForItem(item)?.visualization.name }}
      </div>
      <div class="dashboard-display__chart">
        <!-- LIVE_SESSIONS_MAP is subscription-fed, not query-fed, and this component is
             transport-free — the host provides the live renderer (Studio slots in its
             LiveSessionsVisualization). The fallback renders the static empty map. -->
        <slot
          v-if="vizForItem(item)?.visualization.type === 'LIVE_SESSIONS_MAP'"
          name="live-sessions"
          :title="(vizForItem(item)?.titleOverride || vizForItem(item)?.visualization.name) ?? ''"
          :configuration="vizForItem(item)?.visualization.configuration"
        >
          <AnalyticsVisualization
            :name="(vizForItem(item)?.titleOverride || vizForItem(item)?.visualization.name) ?? ''"
            type="LIVE_SESSIONS_MAP"
            :configuration="vizForItem(item)?.visualization.configuration"
            :data="vizForItem(item)?.data"
            frameless
          />
        </slot>
        <AnalyticsVisualization
          v-else
          :name="(vizForItem(item)?.titleOverride || vizForItem(item)?.visualization.name) ?? ''"
          :type="(vizForItem(item)?.visualization.type as VisualizationType) ?? 'BAR'"
          :configuration="vizForItem(item)?.visualization.configuration"
          :data="vizForItem(item)?.data"
          :loading="vizForItem(item)?.loading ?? false"
          :error="vizForItem(item)?.error"
          :parameters="mergedParams"
          frameless
          @param-change="onParamChange"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.dashboard-display {
  display: grid;
  grid-template-columns: repeat(var(--columns), 1fr);
  grid-auto-rows: var(--cell-height);
  gap: var(--grid-gap);
  min-height: calc(var(--rows) * var(--cell-height) + max(0, var(--rows) - 1) * var(--grid-gap));
}

.dashboard-display__state {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  font-size: 14px;
  color: var(--fg-3, #666);
}

.dashboard-display__state--error {
  color: var(--err, #ff5d6c);
}

.dashboard-display__item {
  background: var(--bg-1, #1a1c22);
  border: 1px solid var(--line, #2a2d35);
  border-radius: var(--r-sm, 8px);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.dashboard-display__item--no-border {
  border-color: transparent;
}

.dashboard-display__item--no-bg {
  background: transparent;
}

.dashboard-display__title {
  padding: 6px 10px 2px;
  font-weight: 500;
  color: var(--fg-2, #999);
  flex-shrink: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-display__title--sm { font-size: 11px; }
.dashboard-display__title--md { font-size: 14px; }
.dashboard-display__title--lg { font-size: 18px; }
.dashboard-display__title--bold { font-weight: 600; color: var(--fg-0, #fff); }

.dashboard-display__chart {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

@media (max-width: 768px) {
  .dashboard-display {
    grid-template-columns: 1fr;
    grid-auto-rows: auto;
  }

  .dashboard-display__item {
    grid-column: 1 / -1 !important;
    grid-row: auto !important;
    min-height: 200px;
  }
}
</style>

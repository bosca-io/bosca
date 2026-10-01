<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'
import { resolveNodeName, type RunGraphStruct, type RunNodeTypeMeta } from '~/components/pipelines/pipelineRunViz'

/**
 * Per-node execution metrics for one pipeline: how often each node ran,
 * how often it failed, and its p50/p95 completion duration — for spotting slow or flaky nodes.
 * Metrics key on the raw nodeId; the same query pulls the pipeline graph + node-type palette so the
 * Node column reads as the operator-facing name (keeping the id as a subtitle), like the run timeline.
 */

const props = defineProps<{ pipelineId: string }>()
const { query: gqlQuery } = useGraphQL()
const toast = useToast()

interface NodeMetricsRow {
  nodeId: string
  executions: number
  failures: number
  failureRate: number
  p50DurationMs: number
  p95DurationMs: number
}

const metrics = ref<NodeMetricsRow[]>([])
const graphStruct = ref<RunGraphStruct | null>(null)
const nodeTypes = ref<RunNodeTypeMeta[]>([])
const loading = ref(false)

const QUERY = gql`
  query GetPipelineNodeMetrics($pipelineId: UUID!) {
    pipelines {
      nodeMetrics(pipelineId: $pipelineId) {
        nodeId executions failures failureRate p50DurationMs p95DurationMs
      }
      nodeTypes { key label }
      pipeline(id: $pipelineId) { id graph }
    }
  }
`

async function refresh() {
  loading.value = true
  try {
    const res = await gqlQuery<{
      pipelines: {
        nodeMetrics: NodeMetricsRow[]
        nodeTypes: RunNodeTypeMeta[]
        pipeline: { graph: unknown } | null
      }
    }>(QUERY, { pipelineId: props.pipelineId })
    metrics.value = res?.pipelines?.nodeMetrics ?? []
    nodeTypes.value = res?.pipelines?.nodeTypes ?? []
    // graph is a JSON scalar — it arrives already parsed.
    const g = (res?.pipelines?.pipeline?.graph ?? {}) as RunGraphStruct
    graphStruct.value = { nodes: g.nodes ?? [], edges: g.edges ?? [] }
  }
  catch {
    toast.error('Failed to load node metrics')
  }
  finally {
    loading.value = false
  }
}
onMounted(refresh)
defineExpose({ refresh })

// nodeId → operator-facing name, resolved once per loaded graph (falls back to the id for nodes the
// graph no longer has — e.g. a node deleted after its runs were recorded).
const nodeNameById = computed(() => {
  const map = new Map<string, string>()
  const g = graphStruct.value
  if (!g) return map
  for (const n of g.nodes ?? []) map.set(n.id, resolveNodeName(n.id, g, nodeTypes.value))
  return map
})
function nodeName(id: string): string {
  return nodeNameById.value.get(id) ?? id
}

const columns = computed<GlassTableColumn[]>(() => [
  { key: 'nodeId', label: 'Node', width: 'minmax(120px, 1fr)' },
  { key: 'executions', label: 'Runs', width: '64px', align: 'right' as const },
  { key: 'failureRate', label: 'Failures', width: '110px', align: 'right' as const },
  { key: 'p50DurationMs', label: 'p50', width: '72px', align: 'right' as const },
  { key: 'p95DurationMs', label: 'p95', width: '72px', align: 'right' as const },
])

function pct(r: number): string {
  return `${Math.round(r * 100)}%`
}
function ms(v: number): string {
  return v < 1000 ? `${Math.round(v)}ms` : `${(v / 1000).toFixed(1)}s`
}
function failureColor(r: number): string {
  return r > 0 ? 'var(--err, #f87171)' : 'var(--text-muted, #94a3b8)'
}
</script>

<template>
  <GlassTable
    :columns="columns"
    :rows="metrics"
    :arrow="false"
    :loading="loading"
    loading-text="Loading node metrics…"
    empty-text="No metrics yet — this pipeline hasn't recorded node executions."
  >
    <template #col-nodeId="{ row }">
      <span class="node-name">{{ nodeName(row.nodeId) }}</span>
      <span
        v-if="nodeName(row.nodeId) !== row.nodeId"
        class="node-id"
        :title="row.nodeId"
      >{{ row.nodeId }}</span>
    </template>
    <template #col-executions="{ row }">
      {{ row.executions }}
    </template>
    <template #col-failureRate="{ row }">
      <span :style="{ color: failureColor(row.failureRate) }">
        {{ pct(row.failureRate) }} <span class="muted">({{ row.failures }})</span>
      </span>
    </template>
    <template #col-p50DurationMs="{ row }">
      {{ ms(row.p50DurationMs) }}
    </template>
    <template #col-p95DurationMs="{ row }">
      {{ ms(row.p95DurationMs) }}
    </template>
  </GlassTable>
</template>

<style scoped>
.node-name {
  display: block;
  font-size: 12px;
  color: var(--text, #e2e8f0);
}
.node-id {
  display: block;
  font-family: var(--font-mono, monospace);
  font-size: 10px;
  color: var(--text-muted, #94a3b8);
}
.muted {
  color: var(--text-muted, #94a3b8);
  font-size: 11px;
}
</style>

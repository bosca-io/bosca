<script setup lang="ts">
import gql from 'graphql-tag'
import { pipelineEventLabel } from '~/components/pipelines/pipelineEventLabel'
import type { GlassTableColumn } from '@bosca/ui'

/**
 * In-flight durable runs (running or suspended) over `pipeline_run` — the live state, distinct from
 * the append-only run history. Shows each run's status, the node(s) it is parked on, how far it has
 * progressed (completed-node count), how long it has been waiting, and a Cancel control.
 */

const { query: gqlQuery, mutation } = useGraphQL()
const toast = useToast()
const emit = defineEmits<{ viewRun: [runId: string] }>()

interface ActiveRunRow {
  id: string
  pipelineId: string
  status: string
  eventName: string
  awaitingNodeIds: string[]
  completedNodeIds: string[]
  error: string | null
  createdAt: string
  modifiedAt: string
}

const PAGE_SIZE = 50
const runs = ref<ActiveRunRow[]>([])
const loading = ref(false)
const cancelling = ref<string | null>(null)

const ACTIVE_RUNS_QUERY = gql`
  query GetActivePipelineRuns($offset: Int!, $limit: Int!) {
    pipelines {
      activeRuns(offset: $offset, limit: $limit) {
        id pipelineId status eventName awaitingNodeIds completedNodeIds
        error createdAt modifiedAt
      }
    }
  }
`
const CANCEL_RUN_MUTATION = gql`
  mutation CancelPipelineRun($runId: UUID!) {
    pipelines { cancelRun(runId: $runId) }
  }
`

async function refresh() {
  loading.value = true
  try {
    const res = await gqlQuery<{ pipelines: { activeRuns: ActiveRunRow[] } }>(
      ACTIVE_RUNS_QUERY,
      { offset: 0, limit: PAGE_SIZE },
    )
    runs.value = res?.pipelines?.activeRuns ?? []
  }
  catch {
    toast.error('Failed to load active runs')
  }
  finally {
    loading.value = false
  }
}

async function cancel(run: ActiveRunRow) {
  cancelling.value = run.id
  try {
    await mutation(CANCEL_RUN_MUTATION, { runId: run.id })
    toast.success('Run cancelled')
    await refresh()
  }
  catch {
    toast.error('Failed to cancel the run')
  }
  finally {
    cancelling.value = null
  }
}

onMounted(refresh)
defineExpose({ refresh })

const columns = computed<GlassTableColumn[]>(() => [
  { key: 'status', label: 'Status', width: '104px' },
  { key: 'pipelineName', label: 'Pipeline', width: 'minmax(120px, 1fr)' },
  { key: 'eventName', label: 'Event', width: 'minmax(120px, 1fr)' },
  { key: 'awaitingNodeIds', label: 'Awaiting', width: 'minmax(140px, 1.4fr)' },
  { key: 'completedNodeIds', label: 'Done', width: '64px', align: 'right' as const },
  { key: 'modifiedAt', label: 'Waiting', width: '92px' },
  { key: 'actions', label: '', width: '160px', align: 'right' as const },
])

/** Compact "time since" for how long a run has been parked. */
function formatAge(d: string): string {
  const ms = Date.now() - new Date(d).getTime()
  const sec = Math.round(ms / 1000)
  if (sec < 60) return `${sec}s`
  const m = Math.floor(sec / 60)
  if (m < 60) return `${m}m`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h`
  return `${Math.floor(h / 24)}d`
}

function statusColor(status: string): string {
  switch (status) {
    case 'SUSPENDED': return 'var(--accent, #f59e0b)'
    case 'RUNNING': return 'var(--info, #38bdf8)'
    default: return 'var(--muted, #94a3b8)'
  }
}
</script>

<template>
  <div class="active-runs-table">
    <GlassTable
      :columns="columns"
      :rows="runs"
      :arrow="false"
      :loading="loading"
      loading-text="Loading active runs…"
      empty-text="No active runs — nothing is currently running or suspended."
    >
      <template #col-status="{ row }">
        <span
          class="status-badge"
          :style="{
            color: statusColor(row.status),
            background: `color-mix(in oklch, ${statusColor(row.status)} 16%, transparent)`,
          }"
        >
          {{ row.status }}
        </span>
      </template>
      <template #col-pipelineName="{ row }">
        <NuxtLink
          class="pipeline-link"
          :to="`/pipelines/${row.pipelineId}`"
        >
          Open
        </NuxtLink>
      </template>
      <template #col-eventName="{ row }">
        <span
          class="event-name"
          :title="row.eventName"
        >{{ pipelineEventLabel(row.eventName) }}</span>
      </template>
      <template #col-awaitingNodeIds="{ row }">
        <span
          v-if="row.awaitingNodeIds.length"
          class="awaiting"
          :title="row.awaitingNodeIds.join(', ')"
        >{{ row.awaitingNodeIds.join(', ') }}</span>
        <span v-else>—</span>
        <p v-if="row.error" class="attempt-error" role="alert">{{ row.error }}</p>
      </template>
      <template #col-completedNodeIds="{ row }">
        {{ row.completedNodeIds.length }}
      </template>
      <template #col-modifiedAt="{ row }">
        {{ formatAge(row.modifiedAt) }}
      </template>
      <template #col-actions="{ row }">
        <div class="row-actions">
          <Button
            size="xs"
            variant="ghost"
            icon="search"
            title="View run detail"
            @click="emit('viewRun', row.id)"
          />
          <Button
            size="xs"
            variant="ghost"
            icon="x"
            :loading="cancelling === row.id"
            @click="cancel(row)"
          >
            Cancel
          </Button>
        </div>
      </template>
    </GlassTable>
  </div>
</template>

<style scoped>
.active-runs-table {
  display: flex;
  flex-direction: column;
}
.status-badge {
  padding: 2px 8px;
  border-radius: 8px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.04em;
}
.pipeline-link {
  color: var(--text, #e2e8f0);
  text-decoration: none;
}
.pipeline-link:hover {
  color: var(--accent, #f59e0b);
  text-decoration: underline;
}
.event-name,
.awaiting {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.attempt-error { margin: 6px 0 0; color: var(--err); font-size: 12px; overflow-wrap: anywhere; }
.awaiting {
  font-family: var(--font-mono, monospace);
  font-size: 11px;
  color: var(--accent, #f59e0b);
}
.row-actions {
  display: inline-flex;
  gap: 6px;
  justify-content: flex-end;
}
</style>

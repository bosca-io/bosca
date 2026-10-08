<script setup lang="ts">
import gql from 'graphql-tag'
import { pipelineEventLabel } from '~/components/pipelines/pipelineEventLabel'
import type { GlassTableColumn } from '@bosca/ui'

/**
 * Paginated run-history table over the append-only pipeline_run_log, newest first.
 * With a `pipelineId` it shows that pipeline's runs; without one it shows every
 * pipeline's runs with a Pipeline column linking to each pipeline's editor.
 */

const props = defineProps<{
  /** Omit to show the global run history across all pipelines. */
  pipelineId?: string | null
}>()

const { query: gqlQuery } = useGraphQL()
const toast = useToast()
const emit = defineEmits<{ viewRun: [runId: string] }>()

interface PipelineRunRow {
  id: string
  pipelineId: string
  runId: string | null
  pipelineName: string
  eventName: string
  outcome: string
  startedAt: string
  finishedAt: string | null
  durationMs: number | null
  errorMessage: string | null
}

const PAGE_SIZE = 50
const runs = ref<PipelineRunRow[]>([])
const loading = ref(false)
const loadingMore = ref(false)
// True once a page comes back short — there is nothing further to fetch.
const exhausted = ref(false)

const RUN_FIELDS = 'id pipelineId runId pipelineName eventName outcome startedAt finishedAt durationMs errorMessage'
const PIPELINE_RUNS_QUERY = gql`
  query GetPipelineRuns($pipelineId: UUID!, $offset: Int!, $limit: Int!) {
    pipelines {
      runs(pipelineId: $pipelineId, offset: $offset, limit: $limit) { ${RUN_FIELDS} }
    }
  }
`
const ALL_RUNS_QUERY = gql`
  query GetAllPipelineRuns($offset: Int!, $limit: Int!) {
    pipelines {
      allRuns(offset: $offset, limit: $limit) { ${RUN_FIELDS} }
    }
  }
`

async function fetchPage(offset: number): Promise<PipelineRunRow[] | null> {
  try {
    if (props.pipelineId) {
      const res = await gqlQuery<{ pipelines: { runs: PipelineRunRow[] } }>(
        PIPELINE_RUNS_QUERY,
        { pipelineId: props.pipelineId, offset, limit: PAGE_SIZE },
      )
      return res?.pipelines?.runs ?? []
    }
    const res = await gqlQuery<{ pipelines: { allRuns: PipelineRunRow[] } }>(
      ALL_RUNS_QUERY,
      { offset, limit: PAGE_SIZE },
    )
    return res?.pipelines?.allRuns ?? []
  }
  catch {
    toast.error('Failed to load the run history')
    return null
  }
}

async function refresh() {
  loading.value = true
  const page = await fetchPage(0)
  if (page) {
    runs.value = page
    exhausted.value = page.length < PAGE_SIZE
  }
  loading.value = false
}

async function loadMore() {
  loadingMore.value = true
  const page = await fetchPage(runs.value.length)
  if (page) {
    runs.value = [...runs.value, ...page]
    exhausted.value = page.length < PAGE_SIZE
  }
  loadingMore.value = false
}

onMounted(refresh)
defineExpose({ refresh })

const columns = computed<GlassTableColumn[]>(() => [
  { key: 'outcome', label: 'Outcome', width: '92px' },
  ...(props.pipelineId
    ? []
    : [{ key: 'pipelineName', label: 'Pipeline', width: 'minmax(140px, 1fr)' }]),
  { key: 'eventName', label: 'Event', width: 'minmax(140px, 1fr)' },
  { key: 'startedAt', label: 'Started', width: '150px' },
  { key: 'durationMs', label: 'Duration', width: '80px', align: 'right' as const },
  { key: 'errorMessage', label: 'Error', width: 'minmax(160px, 1.6fr)', muted: true },
  { key: 'actions', label: '', width: '44px', align: 'right' as const },
])

function formatTime(d: string): string {
  return new Date(d).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function formatDuration(ms: number | null): string {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms}ms`
  const sec = Math.round(ms / 1000)
  if (sec < 60) return `${sec}s`
  const m = Math.floor(sec / 60)
  if (m < 60) return `${m}m ${sec % 60}s`
  return `${Math.floor(m / 60)}h ${m % 60}m`
}

function outcomeColor(outcome: string): string {
  return outcome === 'OK' ? 'var(--ok)' : 'var(--err)'
}
</script>

<template>
  <div class="runs-table">
    <GlassTable
      :columns="columns"
      :rows="runs"
      :arrow="false"
      :loading="loading"
      loading-text="Loading run history…"
      :empty-text="pipelineId
        ? 'No runs yet — this pipeline hasn\'t been triggered.'
        : 'No runs yet — no triggered pipeline has fired.'"
    >
      <template #col-outcome="{ row }">
        <span
          class="outcome-badge"
          :style="{
            color: outcomeColor(row.outcome),
            background: `color-mix(in oklch, ${outcomeColor(row.outcome)} 16%, transparent)`,
          }"
        >
          {{ row.outcome }}
        </span>
      </template>
      <template #col-pipelineName="{ row }">
        <NuxtLink
          class="pipeline-link"
          :to="`/pipelines/${row.pipelineId}`"
        >
          {{ row.pipelineName }}
        </NuxtLink>
      </template>
      <template #col-eventName="{ row }">
        <span
          class="event-name"
          :title="row.eventName"
        >{{ pipelineEventLabel(row.eventName) }}</span>
      </template>
      <template #col-startedAt="{ row }">
        {{ formatTime(row.startedAt) }}
      </template>
      <template #col-durationMs="{ row }">
        {{ formatDuration(row.durationMs) }}
      </template>
      <template #col-errorMessage="{ row }">
        <span
          v-if="row.errorMessage"
          class="error-cell"
          :title="row.errorMessage"
        >{{ row.errorMessage }}</span>
        <span v-else>—</span>
      </template>
      <template #col-actions="{ row }">
        <Button
          v-if="row.runId"
          size="xs"
          variant="ghost"
          icon="search"
          title="View run detail"
          @click="emit('viewRun', row.runId)"
        />
      </template>
    </GlassTable>

    <div
      v-if="!exhausted && !loading && runs.length > 0"
      class="load-more"
    >
      <Button
        size="xs"
        :loading="loadingMore"
        @click="loadMore"
      >
        Load more
      </Button>
    </div>
  </div>
</template>

<style scoped>
/* The table paints its own padding (it sits flush in a SectionCard, like every other
   GlassTable); only the load-more strip needs an inset so it clears the card edges. */
.runs-table {
  display: flex;
  flex-direction: column;
}
.outcome-badge {
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
.event-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.error-cell {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  color: var(--err, #f87171);
  font-size: 11px;
}
.load-more {
  display: flex;
  justify-content: center;
  padding: 12px 16px 14px;
}
</style>

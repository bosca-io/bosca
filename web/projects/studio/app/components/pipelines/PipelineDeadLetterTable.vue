<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

/**
 * The dead-letter queue: runs that terminally FAILED, newest first. Each
 * row shows the failure message and offers Restart — which starts a fresh durable run from the same
 * seed input against the current pipeline, so a fix can be retried without rebuilding the input.
 */

const { query: gqlQuery, mutation } = useGraphQL()
const toast = useToast()
const emit = defineEmits<{ viewRun: [runId: string] }>()

interface DeadLetterRow {
  id: string
  pipelineId: string
  status: string
  eventName: string
  error: string | null
  createdAt: string
  modifiedAt: string
}

const PAGE_SIZE = 50
const runs = ref<DeadLetterRow[]>([])
const loading = ref(false)
const restarting = ref<string | null>(null)
const deleting = ref<string | null>(null)

const DEAD_LETTER_QUERY = gql`
  query GetDeadLetterRuns($offset: Int!, $limit: Int!) {
    pipelines {
      deadLetter(offset: $offset, limit: $limit) {
        id pipelineId status eventName error createdAt modifiedAt
      }
    }
  }
`
const RESTART_RUN_MUTATION = gql`
  mutation RestartPipelineRun($runId: UUID!) {
    pipelines { restartRun(runId: $runId) { id status } }
  }
`
const DELETE_RUN_MUTATION = gql`
  mutation DeletePipelineRun($runId: UUID!) {
    pipelines { deleteRun(runId: $runId) }
  }
`

async function refresh() {
  loading.value = true
  try {
    const res = await gqlQuery<{ pipelines: { deadLetter: DeadLetterRow[] } }>(
      DEAD_LETTER_QUERY,
      { offset: 0, limit: PAGE_SIZE },
    )
    runs.value = res?.pipelines?.deadLetter ?? []
  }
  catch {
    toast.error('Failed to load the dead-letter queue')
  }
  finally {
    loading.value = false
  }
}

async function restart(run: DeadLetterRow) {
  restarting.value = run.id
  try {
    const res = await mutation<{ pipelines: { restartRun: { id: string } | null } }>(
      RESTART_RUN_MUTATION,
      { runId: run.id },
    )
    const newId = res?.pipelines?.restartRun?.id
    if (newId) {
      toast.success('Restart started')
      emit('viewRun', newId)
    }
    else {
      toast.error('Restart could not start — the run no longer exists')
    }
    await refresh()
  }
  catch {
    toast.error('Failed to restart the run')
  }
  finally {
    restarting.value = null
  }
}

async function remove(run: DeadLetterRow) {
  deleting.value = run.id
  try {
    const res = await mutation<{ pipelines: { deleteRun: boolean } }>(
      DELETE_RUN_MUTATION,
      { runId: run.id },
    )
    if (res?.pipelines?.deleteRun) {
      toast.success('Removed from the dead-letter queue')
      // Drop the row locally so it disappears immediately; the soft-deleted run won't reload.
      runs.value = runs.value.filter(r => r.id !== run.id)
    }
    else {
      toast.error('Could not remove the run — it may already be gone')
      await refresh()
    }
  }
  catch {
    toast.error('Failed to remove the run')
  }
  finally {
    deleting.value = null
  }
}

onMounted(refresh)
defineExpose({ refresh })

const columns = computed<GlassTableColumn[]>(() => [
  { key: 'pipelineName', label: 'Pipeline', width: 'minmax(120px, 1fr)' },
  { key: 'eventName', label: 'Event', width: 'minmax(120px, 1fr)' },
  { key: 'error', label: 'Failure', width: 'minmax(200px, 2fr)' },
  { key: 'modifiedAt', label: 'Failed', width: '92px' },
  { key: 'actions', label: '', width: '232px', align: 'right' as const },
])

function eventLabel(fqdn: string): string {
  const leaf = fqdn.includes('.') ? fqdn.slice(fqdn.lastIndexOf('.') + 1) : fqdn
  return leaf.replace(/([a-z0-9])([A-Z])/g, '$1 $2').trim() || fqdn
}

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
</script>

<template>
  <div class="dead-letter-table">
    <GlassTable
      :columns="columns"
      :rows="runs"
      :arrow="false"
      :loading="loading"
      loading-text="Loading dead-letter queue…"
      empty-text="No dead-lettered runs — nothing has failed."
    >
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
        >{{ eventLabel(row.eventName) }}</span>
      </template>
      <template #col-error="{ row }">
        <span
          class="error-msg"
          :title="row.error ?? ''"
        >{{ row.error ?? '—' }}</span>
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
            variant="primary"
            icon="refresh"
            :loading="restarting === row.id"
            :disabled="restarting === row.id"
            @click="restart(row)"
          >
            Restart
          </Button>
          <Button
            size="xs"
            variant="ghost"
            icon="trash"
            danger
            title="Remove from the dead-letter queue"
            :loading="deleting === row.id"
            :disabled="deleting === row.id"
            @click="remove(row)"
          />
        </div>
      </template>
    </GlassTable>
  </div>
</template>

<style scoped>
.dead-letter-table {
  display: flex;
  flex-direction: column;
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
.error-msg {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
}
.error-msg {
  font-size: 11px;
  color: var(--danger, #f87171);
}
.row-actions {
  display: inline-flex;
  gap: 6px;
  justify-content: flex-end;
}
</style>

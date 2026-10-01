<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const statusFilter = ref('')
const sourceFilter = ref('')
const offset = ref(0)
const limit = ref(10)

const jobsGql = gql`
  query JobHistory($limit: Int, $offset: Long, $status: ExecutionStatus, $source: JobHistorySource) {
    scheduler {
      history(limit: $limit, offset: $offset, status: $status, source: $source) {
        id name status source triggeredAt scheduledFor completedAt
        errorMessage wasCatchUp delayedUntil parentJobId
      }
      historyCount(status: $status, source: $source)
    }
  }
`

const cancelJobGql = gql`
  mutation CancelJob($jobId: UUID!) {
    jobs { cancel(jobId: $jobId) }
  }
`

interface Job {
  id: string; name: string; status: string; source: string
  triggeredAt: string | null; scheduledFor: string | null; completedAt: string | null
  errorMessage: string | null; wasCatchUp: boolean; delayedUntil: string | null
  parentJobId: string | null
}

const vars = computed(() => {
  const v: Record<string, unknown> = { limit: limit.value, offset: offset.value }
  if (statusFilter.value) v.status = statusFilter.value
  if (sourceFilter.value) v.source = sourceFilter.value
  return v
})

const { data, status, refresh } = useAsyncQuery<{
  scheduler: { history: Job[]; historyCount: number }
}>('job-history', jobsGql, vars)

const jobs = computed(() => data.value?.scheduler?.history ?? [])
const totalCount = computed(() => data.value?.scheduler?.historyCount ?? 0)
const isLoading = computed(() => status.value === 'pending')

const runningCount = computed(() => jobs.value.filter(j => j.status === 'RUNNING').length)
const pendingCount = computed(() => jobs.value.filter(j => j.status === 'PENDING').length)

const expandedJobId = ref<string | null>(null)

function toggleJobRow(id: string) {
  expandedJobId.value = expandedJobId.value === id ? null : id
}

function jobBadgeBg(state: string) {
  if (state === 'RUNNING') return 'color-mix(in oklch, var(--brand-2) 18%, transparent)'
  if (state === 'FAILED') return 'color-mix(in oklch, var(--err) 18%, transparent)'
  if (state === 'COMPLETED') return 'color-mix(in oklch, var(--ok) 18%, transparent)'
  if (state === 'PENDING') return 'color-mix(in oklch, var(--warn) 18%, transparent)'
  return 'var(--bg-3)'
}

function jobBadgeColor(state: string) {
  if (state === 'RUNNING') return 'var(--brand-2)'
  if (state === 'FAILED') return 'var(--err)'
  if (state === 'COMPLETED') return 'var(--ok)'
  if (state === 'PENDING') return 'var(--warn)'
  return 'var(--fg-3)'
}

function dotClass(state: string) {
  if (state === 'RUNNING') return 'dot info'
  if (state === 'FAILED') return 'dot err'
  if (state === 'COMPLETED') return 'dot ok'
  if (state === 'PENDING') return 'dot warn'
  return 'dot'
}

function humanizeName(name: string): string {
  return name.replace(/[._-]/g, ' ').replace(/\b\w/g, c => c.toUpperCase())
}

function formatTime(d: string | null): string {
  if (!d) return '—'
  return new Date(d).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

async function cancelJob(id: string) {
  try {
    await gqlMutation(cancelJobGql, { jobId: id })
    toast.success('Job cancelled')
    refresh()
  } catch { toast.error('Failed to cancel') }
}

const STATUS_OPTIONS = [
  { value: '', label: 'All' }, { value: 'PENDING', label: 'Pending' },
  { value: 'RUNNING', label: 'Running' }, { value: 'COMPLETED', label: 'Completed' },
  { value: 'FAILED', label: 'Failed' }, { value: 'SKIPPED', label: 'Skipped' },
  { value: 'CANCELLED', label: 'Cancelled' },
]

const SOURCE_OPTIONS = [
  { value: '', label: 'All' }, { value: 'SCHEDULER', label: 'Scheduler' },
  { value: 'EVENT', label: 'Event' },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Jobs')"
        title="Jobs"
        :subtitle="`${totalCount} total · ${runningCount} running · ${pendingCount} pending`"
      >
        <template #actions>
          <Button size="sm" icon="pulse" @click="refresh()">Refresh</Button>
        </template>
      </PageHeader>
    </template>

    <div class="filter-bar">
      <Select
        v-model="statusFilter"
        :options="STATUS_OPTIONS"
        placeholder="Status"
        size="sm" />
      <Select
        v-model="sourceFilter"
        :options="SOURCE_OPTIONS"
        placeholder="Source"
        size="sm" />
      <span style="flex: 1" />
      <span class="mono" style="font-size: 11px; color: var(--fg-3)">{{ totalCount }} results</span>
    </div>

    <SectionCard title="Job History" glass>
      <div v-if="isLoading && jobs.length === 0" style="padding: 32px; text-align: center; color: var(--fg-3)">Loading…</div>
      <div v-else-if="jobs.length === 0" style="padding: 32px; text-align: center; color: var(--fg-3)">No jobs found.</div>
      <div v-else>
        <div v-for="j in jobs" :key="j.id" class="job-item">
          <div class="job-row" :class="{ expanded: expandedJobId === j.id }" @click="toggleJobRow(j.id)">
            <Icon
              class="job-chevron"
              :name="expandedJobId === j.id ? 'chevron-down' : 'chevron-right'"
              :size="14"
              color="var(--fg-3)" />
            <span :class="dotClass(j.status)" />
            <div class="job-info">
              <div class="mono job-name">{{ humanizeName(j.name) }}</div>
              <div class="job-queue">{{ j.source }} · {{ formatTime(j.triggeredAt) }}</div>
            </div>
            <span class="mono tabular job-time">{{ formatTime(j.completedAt) || (j.delayedUntil ? `delayed → ${formatTime(j.delayedUntil)}` : '') }}</span>
            <span class="job-state-badge" :style="{ background: jobBadgeBg(j.status), color: jobBadgeColor(j.status) }">{{ j.status }}</span>
            <button v-if="j.status === 'PENDING' || j.status === 'RUNNING'" class="cancel-btn" @click.stop="cancelJob(j.id)">
              <Icon name="x" :size="12" color="var(--fg-3)" />
            </button>
          </div>
          <div v-if="expandedJobId === j.id" class="job-expanded">
            <div class="job-meta">
              <span><span class="kv-label">Status:</span> <span class="mono">{{ j.status }}</span></span>
              <span><span class="kv-label">Source:</span> <span class="mono">{{ j.source }}</span></span>
              <span><span class="kv-label">Triggered:</span> <span class="mono small">{{ formatTime(j.triggeredAt) }}</span></span>
              <span><span class="kv-label">Scheduled:</span> <span class="mono small">{{ formatTime(j.scheduledFor) }}</span></span>
              <span><span class="kv-label">Completed:</span> <span class="mono small">{{ formatTime(j.completedAt) }}</span></span>
              <span v-if="j.delayedUntil"><span class="kv-label">Delayed until:</span> <span class="mono small">{{ formatTime(j.delayedUntil) }}</span></span>
              <span><span class="kv-label">Catch-up:</span> <span class="mono">{{ j.wasCatchUp ? 'Yes' : 'No' }}</span></span>
              <span v-if="j.parentJobId"><span class="kv-label">Parent job:</span> <span class="mono small">{{ j.parentJobId }}</span></span>
              <span><span class="kv-label">Job ID:</span> <span class="mono small">{{ j.id }}</span></span>
            </div>
            <div v-if="j.errorMessage" class="job-error">
              <div class="job-error-title">Error</div>
              <div class="job-error-message mono">{{ j.errorMessage }}</div>
            </div>
          </div>
        </div>
      </div>
    </SectionCard>

    <div v-if="totalCount > limit" class="pagination">
      <Button size="sm" :disabled="offset <= 0" @click="offset = Math.max(0, offset - limit)">Prev</Button>
      <span class="mono" style="font-size: 12px; color: var(--fg-2)">{{ Math.floor(offset / limit) + 1 }} / {{ Math.ceil(totalCount / limit) }}</span>
      <Button size="sm" :disabled="offset + limit >= totalCount" @click="offset += limit">Next</Button>
    </div>
  </PageShell>
</template>

<style scoped>
.filter-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; }
.section-meta { font-size: 11px; color: var(--fg-3); }

.job-item { border-bottom: 1px solid var(--line); }
.job-item:last-child { border-bottom: none; }

.job-row { display: flex; align-items: center; gap: 14px; padding: 12px 16px; cursor: pointer; transition: background 0.15s; }
.job-row:hover { background: var(--bg-2); }
.job-row.expanded { background: var(--bg-2); }
.job-chevron { flex-shrink: 0; }
.job-info { flex: 1; min-width: 0; }
.job-name { font-size: 12.5px; color: var(--fg-0); font-weight: 500; }
.job-queue { font-size: 11px; color: var(--fg-3); margin-top: 2px; }
.job-time { font-size: 11px; width: 160px; text-align: right; color: var(--fg-2); }
.job-state-badge { font-size: 10.5px; padding: 3px 9px; border-radius: 8px; width: 90px; text-align: center; font-weight: 600; text-transform: uppercase; letter-spacing: 0.06em; }
.cancel-btn { width: 24px; height: 24px; border-radius: 6px; display: flex; align-items: center; justify-content: center; transition: background 0.15s; }
.cancel-btn:hover { background: color-mix(in oklch, var(--err) 10%, transparent); }

.job-expanded {
  background: var(--bg-2);
  border-top: 1px solid var(--line);
  padding: 12px 16px 14px 44px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.job-meta { display: flex; flex-wrap: wrap; gap: 10px 18px; font-size: 12px; color: var(--fg-2); }
.job-meta .kv-label { color: var(--fg-3); margin-right: 4px; }
.job-meta .small { font-size: 11px; }

.job-error {
  background: color-mix(in oklch, var(--err) 10%, transparent);
  border: 1px solid color-mix(in oklch, var(--err) 30%, transparent);
  border-radius: 6px;
  padding: 10px 12px;
}
.job-error-title { color: var(--err); font-weight: 600; font-size: 12.5px; }
.job-error-message { color: var(--err); font-size: 12px; margin-top: 4px; white-space: pre-wrap; word-break: break-word; }

.pagination { display: flex; align-items: center; justify-content: center; gap: 12px; padding: 14px; }
</style>
